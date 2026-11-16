"""STT 모듈 회귀 테스트 (모델 없이 실행된다).

실행: cd ai/stt && uv run python -m unittest -v
"""

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np

import evaluate
import transcribe
from audio import TARGET_SAMPLE_RATE, convert_to_wav, load_audio, save_wav

LABEL_SAMPLES = Path(__file__).resolve().parents[1] / "labeling" / "samples" / "report_samples_v1.jsonl"

# ai/labeling 라벨 스키마 그대로의 샘플 (entities가 객체 배열)
LABEL_ROW = {
    "id": "RPT-0001",
    "text": "B 체험존 프로젝터가 꺼졌습니다.",
    "entities": [
        {"type": "ZONE", "start": 0, "end": 5, "text": "B 체험존"},
        {"type": "ITEM", "start": 6, "end": 10, "text": "프로젝터"},
    ],
    "inputChannel": "TEXT",
}


class ScoreTest(unittest.TestCase):
    def test_label_schema_entities(self):
        s = evaluate.score(LABEL_ROW, "비 체험존 프로젝터가 꺼졌습니다")
        self.assertEqual(s["entities"], 2)
        self.assertEqual(s["missedEntities"], ["B 체험존"])
        self.assertEqual(s["distance"], 1)

    def test_string_entities(self):
        row = {**LABEL_ROW, "entities": ["B 체험존", "프로젝터"]}
        self.assertEqual(evaluate.score(row, row["text"])["missedEntities"], [])

    def test_spacing_and_punctuation_ignored(self):
        self.assertEqual(evaluate.score(LABEL_ROW, "B체험존 프로젝터가 꺼졌습니다")["distance"], 0)

    @unittest.skipUnless(LABEL_SAMPLES.exists(), "ai/labeling 샘플 파일이 없음")
    def test_label_samples_file(self):
        with open(LABEL_SAMPLES, encoding="utf-8") as f:
            rows = [json.loads(line) for line in f if line.strip()]
        summary = evaluate.summarize([evaluate.score(r, r["text"]) for r in rows], 0)
        self.assertEqual(summary["samples"], len(rows))
        self.assertEqual(summary["cer"], 0)
        self.assertEqual(summary["entityRecall"], 1)


class MissingAudioTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.manifest = self.dir / "manifest.jsonl"
        rows = [LABEL_ROW, {**LABEL_ROW, "id": "RPT-0002"}]
        self.manifest.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows), encoding="utf-8")
        save_wav(np.zeros(TARGET_SAMPLE_RATE, np.float32), self.dir / "RPT-0001.wav")

    def tearDown(self):
        self.tmp.cleanup()

    def run_main(self, *extra):
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()), \
                mock.patch.object(evaluate, "evaluate", return_value=(evaluate.summarize([], 0), [])), \
                mock.patch.object(evaluate, "Transcriber"):
            code = evaluate.main([str(self.manifest), str(self.dir), *extra])
        return code, err.getvalue()

    def test_fails_on_missing(self):
        code, err = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("RPT-0002", err)

    def test_allow_missing(self):
        code, _ = self.run_main("--allow-missing")
        self.assertEqual(code, 0)

    def test_fails_when_no_audio(self):
        (self.dir / "RPT-0001.wav").unlink()
        code, err = self.run_main("--allow-missing")
        self.assertEqual(code, 1)
        self.assertIn("평가할 음성이 없습니다", err)


HINT_ITEMS = ["A구역", "메인 무대", "2번 무선 마이크", "믹서", "오프닝"]


class PromptEchoTest(unittest.TestCase):
    """힌트 항목 2개 이상을 이어 붙인 구간만 반복으로 본다(STT 명세 §5.1, §11)."""

    def test_full_list_echo_removed(self):
        self.assertTrue(transcribe.is_prompt_echo("A구역, 메인 무대, 2번 무선 마이크, 믹서, 오프닝.", HINT_ITEMS))

    def test_partial_list_echo_removed(self):
        """소음 구간에서 목록 일부가 이어져 나온 경우."""
        self.assertTrue(transcribe.is_prompt_echo("메인 무대 2번 무선 마이크 믹서", HINT_ITEMS))

    def test_single_hint_spoken_is_kept(self):
        self.assertFalse(transcribe.is_prompt_echo("메인 무대", HINT_ITEMS))

    def test_one_of_few_hints_spoken_is_kept(self):
        self.assertFalse(transcribe.is_prompt_echo("믹서", ["A구역", "믹서", "오프닝"]))

    def test_only_one_hint_never_echo(self):
        self.assertFalse(transcribe.is_prompt_echo("B 체험존", ["B 체험존"]))

    def test_out_of_order_is_not_echo(self):
        self.assertFalse(transcribe.is_prompt_echo("메인 무대 A구역", HINT_ITEMS))

    def test_normal_report(self):
        self.assertFalse(transcribe.is_prompt_echo("메인 무대 2번 무선 마이크가 안 나옵니다.", HINT_ITEMS))

    def test_no_hints(self):
        self.assertFalse(transcribe.is_prompt_echo("A구역", []))

    def test_cli_prompt_items(self):
        items = transcribe.prompt_items(transcribe.DEFAULT_PROMPT)
        self.assertEqual(items[:3], ["현장 보고입니다", "A구역", "B구역"])
        self.assertTrue(transcribe.is_prompt_echo("A구역, B구역, C구역, B 체험존", items))


def hints(zones=(), equipment=(), programs=(), groups=()):
    return {"zones": list(zones), "equipment": list(equipment), "programs": list(programs), "groups": list(groups)}


class SelectHintsTest(unittest.TestCase):
    """토큰 수는 글자 수로 대신한다."""

    def test_order_is_zones_equipment_programs_groups(self):
        h = hints(groups=["음향팀"], programs=["오프닝"], equipment=["믹서"], zones=["A구역"])
        self.assertEqual(transcribe.select_hints(h, len), ["A구역", "믹서", "오프닝", "음향팀"])

    def test_keeps_given_order_within_kind(self):
        self.assertEqual(transcribe.select_hints(hints(zones=["나", "가"]), len), ["나", "가"])

    def test_stops_at_budget(self):
        h = hints(zones=["가" * 5, "나" * 5], equipment=["다"])
        # "가가가가가, 나나나나나"는 12글자라 예산 10을 넘는다. 뒤의 짧은 이름으로 건너뛰지 않는다.
        self.assertEqual(transcribe.select_hints(h, len, budget=10), ["가" * 5])

    def test_empty(self):
        self.assertEqual(transcribe.select_hints(hints(), len), [])

    def test_same_name_once(self):
        self.assertEqual(transcribe.select_hints(hints(zones=["메인 무대"], programs=["메인 무대"]), len),
                         ["메인 무대"])

    def test_deterministic(self):
        h = hints(zones=[f"구역{i}" for i in range(50)], groups=[f"팀{i}" for i in range(50)])
        self.assertEqual(transcribe.select_hints(h, len), transcribe.select_hints(h, len))

    def test_budget_matches_faster_whisper(self):
        self.assertEqual(transcribe.PROMPT_TOKEN_BUDGET, 448 // 2 - 1)


class HintPromptTest(unittest.TestCase):
    """Transcriber.transcribe_hints가 고른 힌트로 prompt를 만들고, 힌트가 없으면 prompt 없이 부르는지 확인한다."""

    def make(self):
        t = transcribe.Transcriber.__new__(transcribe.Transcriber)
        t.model = mock.Mock()
        t.model.transcribe.return_value = ([], mock.Mock(language="ko", duration=1.0))
        t.count_tokens = len
        return t

    def test_prompt_from_hints(self):
        t = self.make()
        t.transcribe_hints(np.zeros(16000, np.float32), hints(zones=["A구역"], equipment=["믹서"]))
        self.assertEqual(t.model.transcribe.call_args.kwargs["initial_prompt"], "A구역, 믹서")

    def test_no_prompt_without_hints(self):
        t = self.make()
        t.transcribe_hints(np.zeros(16000, np.float32), hints())
        self.assertIsNone(t.model.transcribe.call_args.kwargs["initial_prompt"])


class FakeTranscriber:
    model_name = "fake"
    device = "cpu"

    def transcribe(self, audio, **kwargs):
        return {"text": "ok", "language": "ko", "duration": round(len(audio) / TARGET_SAMPLE_RATE, 2), "segments": []}


class CliOutputTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.files = []
        for name in ("a.wav", "b.wav"):
            save_wav(np.zeros(TARGET_SAMPLE_RATE, np.float32), self.dir / name)
            self.files.append(str(self.dir / name))

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), \
                mock.patch.object(transcribe, "Transcriber", return_value=FakeTranscriber()), \
                mock.patch("sys.argv", ["transcribe.py", *argv]):
            transcribe.main()
        return out.getvalue()

    def test_single_file_is_object(self):
        self.assertIsInstance(json.loads(self.run_cli(self.files[0])), dict)

    def test_multiple_files_is_array(self):
        result = json.loads(self.run_cli(*self.files))
        self.assertEqual([r["source"] for r in result], self.files)

    def test_multiple_files_to_output(self):
        out = self.dir / "out.json"
        self.run_cli(*self.files, "-o", str(out))
        self.assertEqual(len(json.loads(out.read_text(encoding="utf-8"))), 2)


class AudioTest(unittest.TestCase):
    def test_convert_stereo_44k_to_16k_mono(self):
        import wave
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "src.wav", Path(tmp) / "dst.wav"
            t = np.arange(44100) / 44100
            tone = (0.3 * np.sin(2 * np.pi * 440 * t) * 32767).astype("<i2")
            with wave.open(str(src), "wb") as f:
                f.setnchannels(2)
                f.setsampwidth(2)
                f.setframerate(44100)
                f.writeframes(np.repeat(tone, 2).tobytes())
            duration = convert_to_wav(src, dst)
            with wave.open(str(dst)) as f:
                self.assertEqual((f.getframerate(), f.getnchannels(), f.getsampwidth()), (16000, 1, 2))
            self.assertAlmostEqual(duration, 1.0, places=2)
            self.assertEqual(len(load_audio(dst)), len(load_audio(src)))


if __name__ == "__main__":
    unittest.main()
