"""STT 내부 API 테스트 (모델 없이 실행된다).

가짜 전사기를 실제와 같이 별도 프로세스에서 실행하고, 음성 서버는 httpx.MockTransport로 대신한다.
요청4/결과2 계약은 contract.py 모델로 응답마다 다시 확인한다.
실행: cd ai/stt && uv run python -m unittest -v
"""

import asyncio
import copy
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
import wave
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

import httpx
import numpy as np
from fastapi.testclient import TestClient

import contract
import server
from audio import TARGET_SAMPLE_RATE
from transcribe import HINT_SEPARATOR, is_prompt_echo, select_hints
from worker import SttRunner

HERE = Path(__file__).resolve().parent
TOKEN = "test-token"
PREFIX = "https://service.p.example.test/internal/audio-assets/"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
JOB = "11111111-1111-4111-8111-111111111111"
HASH = "a" * 64
HINTS = {"zones": ["A구역", "메인 무대"], "equipment": ["2번 무선 마이크"], "programs": ["오프닝"], "groups": []}

# 가짜 전사기는 음성 길이로 동작을 고른다.
HANG_SECONDS = 7.0
CRASH_SECONDS = 6.0
ECHO_ONLY_SECONDS = 4.0
HINT_TEXT_SECONDS = 5.0
HANG_DECODE = b"HANG_DECODE"


def wav_bytes(seconds, amplitude=0.3):
    t = np.arange(int(seconds * TARGET_SAMPLE_RATE)) / TARGET_SAMPLE_RATE
    pcm = (amplitude * np.sin(2 * np.pi * 440 * t) * 32767).astype("<i2")
    buf = io.BytesIO()
    with wave.open(buf, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(TARGET_SAMPLE_RATE)
        f.writeframes(pcm.tobytes())
    return buf.getvalue()


def segment(id, start, end, text, prompt_echo=False):
    return {"id": id, "start": start, "end": end, "text": text,
            "avg_logprob": -0.15, "no_speech_prob": 0.01, "prompt_echo": prompt_echo}


class FakeTranscriber:
    """Transcriber.transcribe_hints와 같은 형식의 결과를 돌려준다. 자식 프로세스에서 실행된다.

    힌트 선택과 반복 판정은 실제 함수를 쓰고, 토큰 수는 글자 수로 대신한다.
    """

    def transcribe_hints(self, audio, hints):
        items = select_hints(hints, len)
        duration = round(len(audio) / TARGET_SAMPLE_RATE, 2)
        if duration == HANG_SECONDS:
            time.sleep(3600)
        if duration == CRASH_SECONDS:
            os._exit(1)
        if not np.any(audio):
            return {"text": "", "language": "ko", "duration": duration, "segments": []}
        listed = HINT_SEPARATOR.join(items)
        echo = segment(2, 1.0, duration, listed, is_prompt_echo(listed, items))
        if duration == ECHO_ONLY_SECONDS:
            return {"text": "", "language": "ko", "duration": duration, "segments": [echo]}
        if duration == HINT_TEXT_SECONDS:
            # 전달된 힌트를 그대로 돌려 순서·내용이 작업 프로세스까지 갔는지 본다.
            speech = segment(1, 0.0, 1.0, "|".join(items) or "힌트 없음")
            return {"text": speech["text"], "language": "ko", "duration": duration, "segments": [speech]}
        speech = segment(1, 0.0, 1.0, "메인 무대 프로젝터가 꺼졌습니다.")
        return {"text": speech["text"], "language": "ko", "duration": duration, "segments": [speech, echo]}


class FakeRunner(SttRunner):
    """디코딩은 실제로 하고 전사만 가짜로 한다. HANG_DECODE로 시작하는 파일은 디코딩에서 멈춘다."""

    def __init__(self):
        super().__init__(FakeTranscriber())

    def decode(self, path, max_seconds):
        if Path(path).read_bytes().startswith(HANG_DECODE):
            time.sleep(3600)
        return super().decode(path, max_seconds)


class SlowStartRunner(FakeRunner):
    """모델 로딩에 시간이 걸리는 경우. 재시작 중 요청이 AI_BUSY를 받는지 확인한다."""

    def __init__(self):
        time.sleep(2)
        super().__init__()


def aid(name):
    """테스트 이름에서 만든 audioId(UUID)."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, name))


def in_seconds(seconds):
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat()


def envelope(audio_id=None, url=None, **overrides):
    audio_id = aid("audio") if audio_id is None else audio_id
    body = {
        "jobId": JOB,
        "attempt": 1,
        "requestSchemaVersion": "4",
        "kind": "TRANSCRIBE",
        "inputHash": HASH,
        "contextRevision": 12,
        "deadlineAt": in_seconds(300),
        "scope": {"eventId": aid("event"), "uploaderMemberId": aid("member")},
        "payload": {
            "audioId": audio_id,
            "audioUrl": url or PREFIX + audio_id,
            "audioSha256": "b" * 64,
            "hints": copy.deepcopy(HINTS),
        },
    }
    body.update(overrides)
    return body


IDENTITY = {"jobId": JOB, "attempt": 1, "requestSchemaVersion": "4", "inputHash": HASH, "contextRevision": 12}
NO_IDENTITY = dict.fromkeys(IDENTITY)


class AppTestCase(unittest.TestCase):
    """클래스마다 앱 하나와 작업 프로세스 하나를 띄운다."""

    factory = FakeRunner
    timeout_sec = 90
    # 기존 시험은 1초 안팎의 마감으로 작업 프로세스까지 보내므로 추론 시작 하한을 낮춘다.
    min_run_sec = 0.2

    @classmethod
    def setUpClass(cls):
        cls.files = {}
        cls.requests = []

        async def handler(request):
            cls.requests.append(request)
            name = request.url.path.rsplit("/", 1)[-1]
            if name == aid("server-error"):
                return httpx.Response(500)
            if name == aid("redirect"):
                return httpx.Response(302, headers={"Location": "https://evil.example/"})
            if name == aid("connect-error"):
                raise httpx.ConnectError("연결 실패", request=request)
            if name == aid("read-timeout"):
                raise httpx.ReadTimeout("읽기 지연", request=request)
            if name == aid("slow"):
                await asyncio.sleep(30)
            if name == aid("late-download"):
                await asyncio.sleep(0.7)
            if name not in cls.files:
                return httpx.Response(404)
            return httpx.Response(200, content=cls.files[name])

        cls.settings = server.Settings(service_token=TOKEN, audio_url_prefixes=(PREFIX,), timeout_sec=cls.timeout_sec,
                                       min_run_sec=cls.min_run_sec)
        cls.worker = server.TranscriberWorker(cls.factory)
        app = server.create_app(cls.settings, cls.worker, httpx.MockTransport(handler))
        cls.client = TestClient(app)
        cls.client.__enter__()

    @classmethod
    def tearDownClass(cls):
        cls.client.__exit__(None, None, None)

    def post(self, body, headers=AUTH):
        return self.client.post("/internal/transcribe", json=body, headers=headers)

    def transcribe(self, name, data=None, headers=AUTH, **overrides):
        if data is not None:
            self.files[aid(name)] = data
        return self.post(envelope(aid(name), **overrides), headers)

    def assertSuccess(self, res):
        self.assertEqual(res.status_code, 200, res.text)
        body = res.json()
        contract.Success.model_validate(body)
        return body

    def assertError(self, res, status, code, retryable=False, reason=None, identity=IDENTITY):
        self.assertEqual(res.status_code, status, res.text)
        body = res.json()
        contract.Failure.model_validate(body)
        self.assertEqual(set(body), set(IDENTITY) | {"error"})
        self.assertEqual({k: body[k] for k in IDENTITY}, identity)
        self.assertEqual(body["error"]["code"], code)
        self.assertEqual(body["error"]["retryable"], retryable)
        self.assertTrue(body["error"]["message"])
        if reason:
            self.assertEqual(body["error"]["details"]["reason"], reason)
        return body

    def wait_ready(self, limit=60):
        end = time.monotonic() + limit
        while time.monotonic() < end:
            if self.client.get("/health").json()["status"] == "ok":
                return
            time.sleep(0.2)
        self.fail("작업 프로세스가 다시 준비되지 않았습니다.")


class ContractTest(AppTestCase):
    def test_success(self):
        body = self.assertSuccess(self.transcribe("ok-1", wav_bytes(2)))
        self.assertEqual(set(body), set(IDENTITY) | {"resultSchemaVersion", "modelVersion", "result", "executionMeta"})
        self.assertEqual({k: body[k] for k in IDENTITY}, IDENTITY)
        self.assertEqual(body["resultSchemaVersion"], "2")
        self.assertEqual(body["modelVersion"], "faster-whisper-large-v3-int8")
        self.assertEqual(body["result"], {
            "audioId": aid("ok-1"),
            "text": "메인 무대 프로젝터가 꺼졌습니다.",
            # 힌트 목록 반복 구간은 segments에서도 빠진다.
            "segments": [{"startSec": 0.0, "endSec": 1.0, "text": "메인 무대 프로젝터가 꺼졌습니다."}],
            "durationSec": 2.0,
        })
        meta = body["executionMeta"]
        self.assertEqual({k: meta[k] for k in ("pipeline", "denoiseModel", "fallbackReason")},
                         {"pipeline": "RAW", "denoiseModel": None, "fallbackReason": None})
        self.assertGreaterEqual(meta["processingMs"], 0)

    def test_identity_returned_as_given(self):
        overrides = {"attempt": 3, "contextRevision": 0, "inputHash": "0123456789abcdef" * 4}
        body = self.assertSuccess(self.transcribe("ok-attempt", wav_bytes(1), **overrides))
        self.assertEqual({k: body[k] for k in overrides}, overrides)

    def test_raw_requested(self):
        body = envelope(aid("raw"))
        body["payload"]["options"] = {"pipeline": "RAW"}
        self.files[aid("raw")] = wav_bytes(1)
        self.assertEqual(self.assertSuccess(self.post(body))["executionMeta"]["pipeline"], "RAW")

    def test_hints_reach_transcriber_in_order(self):
        body = self.assertSuccess(self.transcribe("hints", wav_bytes(HINT_TEXT_SECONDS)))
        self.assertEqual(body["result"]["text"], "A구역|메인 무대|2번 무선 마이크|오프닝")

    def test_empty_hints(self):
        body = envelope(aid("no-hints"))
        body["payload"]["hints"] = {"zones": [], "equipment": [], "programs": [], "groups": []}
        self.files[aid("no-hints")] = wav_bytes(HINT_TEXT_SECONDS)
        self.assertEqual(self.assertSuccess(self.post(body))["result"]["text"], "힌트 없음")

    def test_fetch_uses_service_token(self):
        self.transcribe("ok-2", wav_bytes(1))
        self.assertEqual(self.requests[-1].headers["authorization"], f"Bearer {TOKEN}")
        self.assertEqual(str(self.requests[-1].url), PREFIX + aid("ok-2"))

    def test_max_length_allowed(self):
        self.assertSuccess(self.transcribe("max", wav_bytes(30)))

    def test_too_long(self):
        self.assertError(self.transcribe("long", wav_bytes(30.5)), 422, "AUDIO_INVALID", reason="TOO_LONG")

    def test_too_large(self):
        self.assertError(self.transcribe("large", b"\0" * (10 * 1024 * 1024 + 1)), 422, "AUDIO_INVALID",
                         reason="TOO_LARGE")

    def test_not_audio(self):
        self.assertError(self.transcribe("text", b"not an audio file" * 100), 422, "AUDIO_INVALID",
                         reason="DECODE_FAILED")

    def test_empty(self):
        self.assertError(self.transcribe("empty", b""), 422, "AUDIO_INVALID", reason="EMPTY")

    def test_fetch_not_found(self):
        body = self.assertError(self.transcribe("missing"), 422, "AUDIO_INVALID", reason="FETCH_FAILED")
        self.assertEqual(body["error"]["details"]["status"], 404)

    def test_fetch_redirect_not_followed(self):
        self.requests.clear()
        self.assertError(self.transcribe("redirect"), 422, "AUDIO_INVALID", reason="FETCH_FAILED")
        self.assertEqual(len(self.requests), 1)

    def test_fetch_server_error_is_unavailable(self):
        self.assertError(self.transcribe("server-error"), 503, "AI_UNAVAILABLE", retryable=True,
                         reason="FETCH_FAILED")

    def test_fetch_connect_error_is_unavailable(self):
        self.assertError(self.transcribe("connect-error"), 503, "AI_UNAVAILABLE", retryable=True,
                         reason="FETCH_FAILED")

    def test_fetch_read_timeout_before_deadline_is_unavailable(self):
        """다운로드 한 번이 늦어도 전체 마감 전이면 일시 장애다(O-4)."""
        self.assertError(self.transcribe("read-timeout"), 503, "AI_UNAVAILABLE", retryable=True,
                         reason="FETCH_FAILED")

    def test_download_past_deadline_is_timeout(self):
        started = time.monotonic()
        self.assertError(self.transcribe("slow", deadlineAt=in_seconds(1)), 504, "AI_TIMEOUT")
        self.assertLess(time.monotonic() - started, 3)

    def test_silence(self):
        self.assertError(self.transcribe("silence", wav_bytes(3, amplitude=0)), 422, "NO_SPEECH")

    def test_prompt_echo_only_is_no_speech(self):
        self.assertError(self.transcribe("echo", wav_bytes(ECHO_ONLY_SECONDS)), 422, "NO_SPEECH")

    def test_deadline_passed_after_inference(self):
        """추론은 마감 안에 끝났지만 결과를 만드는 사이 마감을 넘기면 성공이 아니라 AI_TIMEOUT이다."""
        real = server.to_result

        def slow_to_result(raw, audio_id):
            time.sleep(1.5)
            return real(raw, audio_id)

        with mock.patch.object(server, "to_result", side_effect=slow_to_result):
            res = self.transcribe("late-result", wav_bytes(1), deadlineAt=in_seconds(1))
        self.assertError(res, 504, "AI_TIMEOUT")

    def test_deadline_passed(self):
        self.requests.clear()
        self.assertError(self.transcribe("late", wav_bytes(1), deadlineAt=in_seconds(-1)), 504, "AI_TIMEOUT")
        self.assertEqual(self.requests, [], "마감이 지난 요청은 음성을 받지 않는다")

    def test_example_request(self):
        """examples/request.json(BE와 공유할 fixture)을 마감만 바꿔 보내면 결과2 성공이다."""
        body = json.loads((HERE / "examples" / "request.json").read_text(encoding="utf-8"))
        body["deadlineAt"] = in_seconds(300)
        self.files[body["payload"]["audioId"]] = wav_bytes(2)
        res = self.assertSuccess(self.post(body))
        self.assertEqual(res["result"]["audioId"], body["payload"]["audioId"])


class RequestValidationTest(AppTestCase):
    def assertInvalid(self, body, identity=IDENTITY, path=None):
        self.requests.clear()
        res = self.assertError(self.post(body), 400, "INVALID_REQUEST", identity=identity)
        if path is not None:
            self.assertIn(path, [f["path"] for f in res["error"]["details"]["fieldErrors"]])
        self.assertEqual(self.requests, [], "음성 서버로 요청이 나가면 안 된다")
        return res

    def test_no_token(self):
        self.assertError(self.transcribe("a", wav_bytes(1), headers={}), 401, "UNAUTHENTICATED", identity=NO_IDENTITY)

    def test_wrong_token(self):
        self.assertError(self.transcribe("a", wav_bytes(1), headers={"Authorization": "Bearer wrong"}),
                         401, "UNAUTHENTICATED", identity=NO_IDENTITY)

    def test_auth_checked_before_body(self):
        self.assertError(self.client.post("/internal/transcribe", json={}), 401, "UNAUTHENTICATED",
                         identity=NO_IDENTITY)

    def test_not_json(self):
        res = self.client.post("/internal/transcribe", content=b"{not json",
                               headers={**AUTH, "Content-Type": "application/json"})
        self.assertError(res, 400, "INVALID_REQUEST", identity=NO_IDENTITY)

    def test_missing_field(self):
        body = envelope()
        del body["payload"]
        self.assertInvalid(body, path="payload")

    def test_legacy_request3_rejected(self):
        """요청3은 받지 않는다(Q3). 식별 필드 중 형식이 맞는 것만 돌려준다."""
        legacy = {
            "jobId": JOB, "attempt": 1, "schemaVersion": "3", "actorMemberId": "m", "eventId": "e",
            "deadlineAt": in_seconds(300), "payload": {"audioId": aid("a"), "audioUrl": PREFIX + aid("a")},
        }
        self.assertInvalid(legacy, identity={**NO_IDENTITY, "jobId": JOB, "attempt": 1})

    def test_request_schema_version(self):
        for bad in ("3", 4, None):
            with self.subTest(version=bad):
                self.assertInvalid(envelope(requestSchemaVersion=bad),
                                   identity={**IDENTITY, "requestSchemaVersion": None}, path="requestSchemaVersion")

    def test_kind(self):
        self.assertInvalid(envelope(kind="ANALYZE_REPORT"), path="kind")

    def test_job_id_must_be_uuid(self):
        for bad in ("job-1", aid("job").upper(), JOB.replace("-", ""), 1):
            with self.subTest(jobId=bad):
                self.assertInvalid(envelope(jobId=bad), identity={**IDENTITY, "jobId": None}, path="jobId")

    def test_attempt(self):
        for bad in (0, -1, "1", 1.5, True):
            with self.subTest(attempt=bad):
                self.assertInvalid(envelope(attempt=bad), identity={**IDENTITY, "attempt": None}, path="attempt")

    def test_input_hash(self):
        for bad in ("A" * 64, "a" * 63, "g" * 64, None):
            with self.subTest(inputHash=bad):
                self.assertInvalid(envelope(inputHash=bad), identity={**IDENTITY, "inputHash": None},
                                   path="inputHash")

    def test_context_revision(self):
        for bad in (-1, "12", None):
            with self.subTest(contextRevision=bad):
                self.assertInvalid(envelope(contextRevision=bad), identity={**IDENTITY, "contextRevision": None},
                                   path="contextRevision")

    def test_deadline_required(self):
        body = envelope()
        del body["deadlineAt"]
        self.assertInvalid(body, path="deadlineAt")

    def test_naive_deadline(self):
        self.assertInvalid(envelope(deadlineAt="2026-10-21T10:00:00"), path="deadlineAt")

    def test_scope(self):
        body = envelope()
        del body["scope"]["uploaderMemberId"]
        self.assertInvalid(body, path="scope.uploaderMemberId")
        self.assertInvalid(envelope(scope={"eventId": "e", "uploaderMemberId": aid("m")}), path="scope.eventId")

    def test_audio_sha256(self):
        body = envelope()
        body["payload"]["audioSha256"] = "B" * 64
        self.assertInvalid(body, path="payload.audioSha256")

    def test_unknown_field(self):
        self.assertInvalid(envelope(actorMemberId="m"), path="actorMemberId")

    def test_hints_all_keys_required(self):
        body = envelope()
        del body["payload"]["hints"]["groups"]
        self.assertInvalid(body, path="payload.hints.groups")

    def test_hints_limits(self):
        for name, value in (("count", ["이름"] * 51), ("length", ["가" * 31]), ("empty", [""]), ("type", [1])):
            with self.subTest(name):
                body = envelope()
                body["payload"]["hints"]["zones"] = value
                self.assertInvalid(body)

    def test_hints_at_limits(self):
        body = envelope(aid("hint-limit"))
        body["payload"]["hints"]["equipment"] = [f"{i:02d}" + "가" * 28 for i in range(50)]
        self.files[aid("hint-limit")] = wav_bytes(1)
        self.assertSuccess(self.post(body))

    def test_options_pipeline(self):
        body = envelope()
        body["payload"]["options"] = {"pipeline": "DENOISED"}
        self.assertInvalid(body, path="payload.options.pipeline")

    def assertBadUrl(self, url, audio_id=None, path="payload.audioUrl"):
        self.requests.clear()
        body = envelope(aid("a") if audio_id is None else audio_id, url=url)
        res = self.assertError(self.post(body), 400, "INVALID_REQUEST")
        details = res["error"]["details"]
        self.assertIn(path, [details.get("field")] + [f["path"] for f in details.get("fieldErrors", [])])
        self.assertEqual(self.requests, [], "음성 서버로 요청이 나가면 안 된다")

    def test_audio_id_must_be_uuid(self):
        for bad in ("", "abc", aid("a").upper(), aid("a").replace("-", "")):
            with self.subTest(audio_id=bad):
                self.assertBadUrl(PREFIX + (bad or "x"), audio_id=bad, path="payload.audioId")

    def test_other_host(self):
        self.assertBadUrl("https://evil/internal/audio-assets/" + aid("a"))
        self.assertBadUrl("https://service.p.example.test.evil/internal/audio-assets/" + aid("a"))
        self.assertBadUrl("http://service.p.example.test/internal/audio-assets/" + aid("a"))

    def test_url_without_audio_id(self):
        self.assertBadUrl(PREFIX + aid("b"))

    def test_path_traversal(self):
        a = aid("a")
        for url in (
            PREFIX + "../admin/" + a,
            PREFIX + "%2e%2e%2fadmin/" + a,
            PREFIX + "%2E%2E/admin/" + a,
            PREFIX + a + "%2f..%2fadmin",
            PREFIX + "..\\admin/" + a,
            PREFIX + a + "\x07",
            PREFIX + a + " x",
            PREFIX + "한글/" + a,
            PREFIX + "./" + a,
            PREFIX + "/" + a,
            PREFIX + a + "#x",
        ):
            with self.subTest(url=url):
                self.assertBadUrl(url)

    def test_extra_path_segments(self):
        """합의한 경로는 정확히 `/internal/audio-assets/{audioId}`다. 앞뒤에 구간이 붙으면 거절한다."""
        a = aid("a")
        for url in (
            PREFIX + "not-the-audio/" + a,
            PREFIX + "not-the-audio/" + a + "/metadata",
            PREFIX + a + "/metadata",
            PREFIX + a + "/",
            PREFIX + a + a,
            "https://service.p.example.test/internal/" + a,
        ):
            with self.subTest(url=url):
                self.assertBadUrl(url)

    def test_query_rejected(self):
        """BE 다운로드 주소에는 쿼리가 없다(Q9: URL 자체 유효기간 없음). 쿼리가 붙으면 거절한다."""
        self.assertBadUrl(PREFIX + aid("a") + "?expires=1&sig=abc")
        self.assertBadUrl(PREFIX + aid("a") + "?")


class TimeoutTest(AppTestCase):
    """시간 초과·비정상 종료 시 작업 프로세스를 실제로 끝내고 다음 요청을 다시 받는지 확인한다."""

    factory = SlowStartRunner
    timeout_sec = 1

    def assertTimesOutAndRecovers(self, data):
        self.wait_ready()
        hung = self.worker.proc
        started = time.monotonic()
        self.assertError(self.transcribe("hang", data), 504, "AI_TIMEOUT")
        self.assertLess(time.monotonic() - started, self.timeout_sec + 1)
        self.assertFalse(hung.is_alive())

        # 모델을 다시 불러오는 동안은 AI_BUSY. 요청 식별은 그대로 돌려준다.
        self.assertError(self.transcribe("ok", wav_bytes(1)), 503, "AI_BUSY", retryable=True)
        self.wait_ready()
        self.assertIsNot(self.worker.proc, hung)
        self.assertSuccess(self.transcribe("ok", wav_bytes(1)))

    def test_transcribe_timeout(self):
        self.assertTimesOutAndRecovers(wav_bytes(HANG_SECONDS))

    def test_decode_timeout(self):
        self.assertTimesOutAndRecovers(HANG_DECODE + wav_bytes(1))

    def test_crash_recovers(self):
        self.wait_ready()
        crashed = self.worker.proc
        body = self.assertError(self.transcribe("crash", wav_bytes(CRASH_SECONDS)), 503, "AI_UNAVAILABLE",
                                retryable=True)
        self.assertNotIn("details", body["error"], "예외 원문을 응답에 넣지 않는다")
        self.assertFalse(crashed.is_alive())
        self.wait_ready()
        self.assertSuccess(self.transcribe("ok", wav_bytes(1)))


class DeadlineTest(AppTestCase):
    """서버 상한(90초)보다 요청의 deadlineAt이 이르면 deadlineAt에서 끊는다."""

    def test_deadline_shorter_than_server_limit(self):
        self.wait_ready()
        hung = self.worker.proc
        started = time.monotonic()
        res = self.transcribe("hang-deadline", wav_bytes(HANG_SECONDS), deadlineAt=in_seconds(1.5))
        self.assertError(res, 504, "AI_TIMEOUT")
        self.assertLess(time.monotonic() - started, 3)
        self.assertFalse(hung.is_alive())
        self.wait_ready()
        self.assertSuccess(self.transcribe("after", wav_bytes(1)))


class MinRunTest(AppTestCase):
    """다운로드 뒤 남은 시간이 추론 시작 하한보다 짧으면 작업 프로세스에 보내지 않는다."""

    min_run_sec = 1

    def test_short_remaining_time_keeps_worker(self):
        self.wait_ready()
        before = self.worker.proc
        self.files[aid("late-download")] = wav_bytes(1)
        res = self.post(envelope(aid("late-download"), deadlineAt=in_seconds(1.2)))
        self.assertError(res, 504, "AI_TIMEOUT")
        # 모델을 다시 불러오지 않으므로 다음 요청을 바로 처리한다.
        self.assertIs(self.worker.proc, before)
        self.assertTrue(before.is_alive())
        self.assertEqual(self.client.get("/health").json()["status"], "ok")
        self.assertSuccess(self.transcribe("after-short", wav_bytes(1)))


ORPHAN_PARENT = """
import sys, threading
from worker import TranscriberWorker
from test_server import FakeRunner

if __name__ == "__main__":
    worker = TranscriberWorker(FakeRunner)
    worker.start()
    threading.Thread(target=worker.run, args=(sys.argv[1], 30, {}), kwargs={"timeout": 3600}, daemon=True).start()
    print(worker.proc.pid, flush=True)
    threading.Event().wait()
"""


def pid_alive(pid):
    if os.name == "nt":
        import ctypes
        kernel32 = ctypes.windll.kernel32
        handle = kernel32.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE
        if not handle:
            return False
        try:
            return kernel32.WaitForSingleObject(handle, 0) == 0x102  # WAIT_TIMEOUT
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


def kill_pid(pid):
    if os.name == "nt":
        subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True)
    else:
        os.kill(pid, 9)


class OrphanTest(unittest.TestCase):
    def test_worker_exits_when_parent_is_killed(self):
        """서버가 강제 종료되면 작업 중이던 자식 프로세스도 끝나는지 확인한다."""
        with tempfile.TemporaryDirectory() as tmp:
            script, audio = Path(tmp) / "parent.py", Path(tmp) / "hang"
            script.write_text(ORPHAN_PARENT, encoding="utf-8")
            audio.write_bytes(HANG_DECODE)
            env = {**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parent)}
            parent = subprocess.Popen([sys.executable, str(script), str(audio)], stdout=subprocess.PIPE, env=env)
            try:
                child = int(parent.stdout.readline())
                self.assertTrue(pid_alive(child))
            finally:
                parent.kill()
                parent.wait()
                parent.stdout.close()
            end = time.monotonic() + 10
            while pid_alive(child) and time.monotonic() < end:
                time.sleep(0.1)
            if pid_alive(child):
                # 남은 프로세스가 테스트 출력 파이프를 잡고 있으면 테스트 실행이 끝나지 않는다.
                kill_pid(child)
                self.fail("부모가 종료된 뒤에도 작업 프로세스가 남아 있습니다.")


class SettingsTest(unittest.TestCase):
    ENV = {"AI_SERVICE_TOKEN": TOKEN, "STT_AUDIO_URL_PREFIXES": PREFIX}

    def test_defaults(self):
        with mock.patch.dict(os.environ, self.ENV, clear=True):
            s = server.Settings.from_env()
        self.assertEqual((s.model, s.device, s.compute_type, s.cpu_threads), ("large-v3", "cpu", "int8", 2))
        self.assertEqual((s.timeout_sec, s.max_seconds, s.max_bytes, s.download_timeout_sec), (90, 30, 10 * 1024 * 1024, 5))
        self.assertEqual(s.min_run_sec, 1)
        with mock.patch.dict(os.environ, {**self.ENV, "STT_MIN_RUN_SEC": "2.5"}, clear=True):
            self.assertEqual(server.Settings.from_env().min_run_sec, 2.5)

    def test_token_required(self):
        with mock.patch.dict(os.environ, {"STT_AUDIO_URL_PREFIXES": PREFIX}, clear=True), \
                self.assertRaises(RuntimeError):
            server.Settings.from_env()

    def test_prefix_required(self):
        with mock.patch.dict(os.environ, {"AI_SERVICE_TOKEN": TOKEN}, clear=True), self.assertRaises(RuntimeError):
            server.Settings.from_env()

    def test_prefix_must_end_with_slash(self):
        env = {**self.ENV, "STT_AUDIO_URL_PREFIXES": PREFIX.rstrip("/")}
        with mock.patch.dict(os.environ, env, clear=True), self.assertRaises(RuntimeError):
            server.Settings.from_env()


if __name__ == "__main__":
    unittest.main()
