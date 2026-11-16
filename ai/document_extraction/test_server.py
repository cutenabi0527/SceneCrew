from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
from io import BytesIO
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import httpx

from . import documents
from .server import PATH, create_app
from .source import DocumentError
from .test_extraction import PNG_HEADER

ORIGIN = "https://documents.example"
DOCUMENT_ID = "00000000-0000-0000-0000-000000000001"
JOB_ID = "00000000-0000-0000-0000-000000000002"
URL = f"{ORIGIN}/internal/intake-documents/{DOCUMENT_ID}"
CONTENT = "행사명: 쇼케이스\n날짜: 2026-11-24 (임시)\n운영 인원: 5명 또는 6명\n구역: A".encode()
TOKEN = "fixture-service-token"


def request4(content=CONTENT, *, mime="text/plain", name="fixture.txt", page_count=None,
             document_id=DOCUMENT_ID):
    payload = {"documents": [{
        "documentId": document_id, "name": name, "mime": mime,
        "bytes": len(content), "pageCount": page_count, "sha256": hashlib.sha256(content).hexdigest(),
        "url": f"{ORIGIN}/internal/intake-documents/{document_id}",
    }]}
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    return {
        "jobId": JOB_ID, "attempt": 2, "kind": "EXTRACT_EVENT_INPUT", "requestSchemaVersion": "4",
        "inputHash": hashlib.sha256(canonical).hexdigest(), "contextRevision": 7,
        "deadlineAt": (datetime.now(timezone.utc) + timedelta(seconds=20)).isoformat(),
        "scope": {
            "eventId": "00000000-0000-0000-0000-000000000003",
            "chiefMemberId": "00000000-0000-0000-0000-000000000004",
        },
        "payload": payload,
    }


class ExtractionHttpTest(unittest.IsolatedAsyncioTestCase):
    async def test_gateway_lifespan_and_health_without_ocr(self):
        app = create_app(service_token=TOKEN, document_base_url=ORIGIN, render_page=lambda *_: PNG_HEADER)
        pending = iter([{"type": "lifespan.startup"}, {"type": "lifespan.shutdown"}])
        replies = []

        async def receive():
            return next(pending)

        async def send(message):
            replies.append(message["type"])

        await app({"type": "lifespan"}, receive, send)
        self.assertEqual(replies, ["lifespan.startup.complete", "lifespan.shutdown.complete"])
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://ai.example") as client:
            response = await client.get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "unavailable")
        self.assertIn("OCR_UNAVAILABLE", response.json()["missing"])

    async def test_bad_document_origin_fails_startup(self):
        with self.assertRaisesRegex(ValueError, "configured document service origin"):
            create_app(service_token=TOKEN, document_base_url=ORIGIN + "/internal/")

    async def test_only_configured_prefix_and_exact_uuid_can_be_fetched(self):
        second_prefix = "https://other-docs.example/internal/intake-documents/"
        seen = []

        def upstream(request):
            seen.append(str(request.url))
            return httpx.Response(200, content=CONTENT)

        app = create_app(
            service_token=TOKEN,
            document_url_prefixes=f"{URL.removesuffix(DOCUMENT_ID)},{second_prefix}",
            transport=httpx.MockTransport(upstream),
        )
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://ai.example") as client:
            for suffix in (DOCUMENT_ID, DOCUMENT_ID + "?v=1", DOCUMENT_ID + "#view", DOCUMENT_ID + "/../other"):
                request = request4()
                request["payload"]["documents"][0]["url"] = second_prefix + suffix
                response = await client.post(PATH, json=request, headers={"Authorization": f"Bearer {TOKEN}"})
                self.assertEqual(response.status_code, 200 if suffix == DOCUMENT_ID else 400)
        self.assertEqual(seen, [second_prefix + DOCUMENT_ID])

    async def call(self, request, upstream, token=TOKEN, **settings):
        app = create_app(service_token=TOKEN, document_base_url=ORIGIN,
                         transport=httpx.MockTransport(upstream), **settings)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://ai.example") as client:
            return await client.post(PATH, json=request, headers={"Authorization": f"Bearer {token}"})

    async def test_request4_and_result3_are_deterministic(self):
        def upstream(request):
            self.assertEqual(str(request.url), URL)
            self.assertEqual(request.headers["Authorization"], f"Bearer {TOKEN}")
            self.assertEqual(request.headers["X-SceneCrew-Job-Id"], JOB_ID)
            self.assertEqual(request.headers["X-SceneCrew-Job-Attempt"], "2")
            return httpx.Response(200, content=CONTENT)

        request = request4()
        response = await self.call(request, upstream)
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body, (await self.call(request, upstream)).json())
        for key in ("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision"):
            self.assertEqual(body[key], request[key])
        self.assertEqual(body["resultSchemaVersion"], "3")
        self.assertEqual(body["result"], {
            "fields": {"name": "쇼케이스", "zones": "A"},
            "missingRequired": ["date", "prepStartAt", "openAt", "closeAt", "teardownEndAt",
                                "locationName", "address"],
            "conflictingValues": [{"field": "totalCapacity", "values": ["5", "6"]}],
            "unreadableFiles": [], "resultStatus": "PARTIAL",
        })
        self.assertNotIn("floor_plans", body)

    async def test_result3_transmits_ordered_zone_and_program_conditions(self):
        content = """행사명: 쇼케이스
날짜: 2026-11-24 (미정)
준비 시작: 17:30 (임시)
장소: 공연장
운영 인원: 5명
공연 시작: 18:00
공연 길이: 4분
| A구역 | 무대 앞 | 입장 |
| B구역 | 객석 뒤 | 퇴장 |
| A구역 | 무대 앞 | 입장 |
| 2 | 18:02 | 18:04 | 둘째 곡 '두 번째 빛' |
| ² | 18:04 | 18:06 | 둘째 곡 '잘못된 행' |
| 1 | 18:00 | 18:02 | 첫째 곡 '첫 번째 빛' |
""".encode()
        response = await self.call(request4(content), lambda req: httpx.Response(200, content=content))
        self.assertEqual(response.status_code, 200)
        result = response.json()["result"]
        self.assertEqual(result["fields"], {
            "name": "쇼케이스", "locationName": "공연장", "totalCapacity": "5",
            "zones": "A구역, B구역", "programOrder": "첫 번째 빛 → 두 번째 빛",
        })
        self.assertEqual(result["missingRequired"], [
            "date", "prepStartAt", "openAt", "closeAt", "teardownEndAt", "address",
        ])
        self.assertEqual(result["conflictingValues"], [])
        self.assertEqual(result["resultStatus"], "PARTIAL")

    async def test_result3_transmits_appendix_conditions_but_not_other_keys(self):
        # 계획서 부록 "키 값" 표의 조건은 기본 키 목록 밖이어도 결과3으로 넘긴다(#401 운영 확인).
        # 부록이 아닌 공연 시작·길이(performanceStart·duration)는 지금처럼 넘기지 않는다.
        content = """행사명: 쇼케이스
공연 시작: 18:00
공연 길이: 4분
키   값
audienceCamera   객석 고정 카메라 1대가 A·B 구역을 비춘다.
equipment   무선 수신기 QLXD4 2대,
            믹서 1대.
계획서 · 12 / 12쪽
""".encode()
        response = await self.call(request4(content), lambda req: httpx.Response(200, content=content))
        self.assertEqual(response.status_code, 200)
        fields = response.json()["result"]["fields"]
        self.assertEqual(fields["audienceCamera"], "객석 고정 카메라 1대가 A·B 구역을 비춘다.")
        self.assertEqual(fields["equipment"], "무선 수신기 QLXD4 2대, 믹서 1대.")
        self.assertNotIn("performanceStart", fields)
        self.assertNotIn("duration", fields)

    async def test_result3_transmits_zone_and_program_conflicts_across_documents(self):
        first = """장소: 공연장
| A구역 | 무대 앞 | 입장 |
| B구역 | 객석 뒤 | 퇴장 |
| 2 | 18:02 | 18:04 | 둘째 곡 '두 번째 빛' |
| 1 | 18:00 | 18:02 | 첫째 곡 '첫 번째 빛' |
""".encode()
        second = """장소: 다른 공연장
| A구역 | 무대 앞 | 입장 |
| C구역 | 객석 옆 | 퇴장 |
| 1 | 18:00 | 18:02 | 첫째 곡 '두 번째 빛' |
| 2 | 18:02 | 18:04 | 둘째 곡 '첫 번째 빛' |
""".encode()
        second_id = "00000000-0000-0000-0000-000000000005"
        request = request4(first)
        request["payload"]["documents"].extend(request4(second, document_id=second_id)["payload"]["documents"])
        canonical = json.dumps(request["payload"], sort_keys=True, separators=(",", ":")).encode()
        request["inputHash"] = hashlib.sha256(canonical).hexdigest()
        originals = {doc["url"]: content for doc, content in zip(request["payload"]["documents"], (first, second))}
        response = await self.call(request, lambda req: httpx.Response(200, content=originals[str(req.url)]))
        self.assertEqual(response.status_code, 200)
        result = response.json()["result"]
        self.assertEqual(result["fields"], {})
        self.assertEqual(result["conflictingValues"], [
            {"field": "locationName", "values": ["공연장", "다른 공연장"]},
            {"field": "zones", "values": ["A구역, B구역", "A구역, C구역"]},
            {"field": "programOrder", "values": ["첫 번째 빛 → 두 번째 빛", "두 번째 빛 → 첫 번째 빛"]},
        ])
        self.assertNotIn("locationName", result["missingRequired"])
        self.assertEqual(result["resultStatus"], "PARTIAL")

    async def test_rejects_bad_envelope_and_document_before_fetch(self):
        def upstream(request):
            self.fail("Invalid request reached document service")

        for key, value in (("kind", "GENERATE_PLAN"), ("attempt", True), ("attempt", 0),
                           ("requestSchemaVersion", 4), ("jobId", "invalid"), ("inputHash", "invalid")):
            with self.subTest(key=key, value=value):
                request = {**request4(), key: value}
                self.assertEqual((await self.call(request, upstream)).status_code, 400)
        for key, value in (("url", URL + "/../other"), ("url", URL.replace(ORIGIN, "https://other.example")),
                           ("sha256", None), ("bytes", 0), ("pageCount", 1)):
            with self.subTest(key=key, value=value):
                request = request4()
                request["payload"]["documents"][0][key] = value
                self.assertEqual((await self.call(request, upstream)).status_code, 400)
        self.assertEqual((await self.call(request4(), upstream, token="wrong")).status_code, 401)
        self.assertEqual((await self.call({**request4(), "deadlineAt": "2000-01-01T00:00:00Z"},
                                          upstream)).status_code, 504)

    async def test_fetch_errors_follow_be_envelope(self):
        request = request4()
        for status, expected_status, code, retryable in (
            (200, 422, "FETCH_FAILED", False), (302, 422, "FETCH_FAILED", False),
            (401, 422, "FETCH_FAILED", False), (404, 422, "FETCH_FAILED", False),
            (503, 503, "AI_UNAVAILABLE", True),
        ):
            with self.subTest(status=status):
                calls = []

                def upstream(req):
                    calls.append(req.url)
                    return httpx.Response(status, content=b"wrong", headers={"Location": "https://other.example"})

                response = await self.call(request, upstream)
                self.assertEqual(response.status_code, expected_status)
                self.assertEqual(response.json()["error"]["code"], code)
                self.assertIs(response.json()["error"]["retryable"], retryable)
                self.assertEqual(response.json()["inputHash"], request["inputHash"])
                self.assertEqual(len(calls), 1)

    async def test_oversized_candidate_is_not_sent_to_be(self):
        content = ("행사명: " + "가" * 1001).encode()
        response = await self.call(request4(content), lambda req: httpx.Response(200, content=content))
        self.assertEqual(response.status_code, 200)
        self.assertNotIn("name", response.json()["result"]["fields"])
        self.assertEqual(response.json()["result"]["resultStatus"], "PARTIAL")

    @unittest.skipUnless(importlib.util.find_spec("pypdf"), "PDF parser unavailable")
    async def test_floor_plan_candidates_in_http_result3(self):
        fixtures = Path(__file__).parent / "fixtures"
        for filename, expected_pages in (("floor-plan-page-2.pdf", [2]), ("ordinary-image.pdf", [])):
            with self.subTest(filename=filename):
                content = (fixtures / filename).read_bytes()
                request = request4(content, name=filename, mime="application/pdf", page_count=2)
                response = await self.call(request, lambda req: httpx.Response(200, content=content),
                                           render_page=lambda *_: PNG_HEADER)
                self.assertEqual(response.status_code, 200)
                result = response.json()["result"]
                expected = {
                    "fields": {}, "missingRequired": ["date", "prepStartAt", "openAt", "closeAt", "teardownEndAt",
                                                "locationName", "address", "totalCapacity"],
                    "conflictingValues": [], "unreadableFiles": [], "resultStatus": "PARTIAL",
                }
                if expected_pages:
                    expected["floorPlanPages"] = [
                        {"documentId": DOCUMENT_ID, "pageNumber": page} for page in expected_pages
                    ]
                self.assertEqual(result, expected)

    @unittest.skipUnless(importlib.util.find_spec("pypdf"), "PDF parser unavailable")
    async def test_floor_plan_candidates_preserve_document_and_page_order(self):
        from pypdf import PdfWriter

        original = (Path(__file__).parent / "fixtures" / "floor-plan-page-2.pdf").read_bytes()
        writer = PdfWriter()
        writer.append(BytesIO(original))
        writer.append(BytesIO(original))
        combined = BytesIO()
        writer.write(combined)
        first_id = "00000000-0000-0000-0000-000000000009"
        request = request4(combined.getvalue(), mime="application/pdf", page_count=4, document_id=first_id)
        second = request4(original, mime="application/pdf", page_count=2)
        request["payload"]["documents"].extend(second["payload"]["documents"])
        text_id = "00000000-0000-0000-0000-000000000008"
        text = b"# Floor plan"
        markdown = request4(text, name="notes.md", mime="text/markdown", document_id=text_id)
        request["payload"]["documents"].extend(markdown["payload"]["documents"])
        canonical = json.dumps(request["payload"], sort_keys=True, separators=(",", ":")).encode()
        request["inputHash"] = hashlib.sha256(canonical).hexdigest()
        originals = {first_id: combined.getvalue(), DOCUMENT_ID: original, text_id: text}
        detect_pages = documents._pdf_pages

        def repeat_candidate(*args):
            for page in detect_pages(*args):
                yield page
                if page[-1] is not None:
                    yield page

        with patch.object(documents, "_pdf_pages", side_effect=repeat_candidate):
            response = await self.call(request,
                                       lambda req: httpx.Response(200, content=originals[req.url.path.split("/")[-1]]),
                                       render_page=lambda *_: PNG_HEADER)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["result"]["floorPlanPages"], [
            {"documentId": first_id, "pageNumber": 2},
            {"documentId": first_id, "pageNumber": 4},
            {"documentId": DOCUMENT_ID, "pageNumber": 2},
        ])

    @unittest.skipUnless(importlib.util.find_spec("pypdf"), "PDF parser unavailable")
    async def test_floor_plan_candidate_must_fit_request_page_count(self):
        content = (Path(__file__).parent / "fixtures" / "floor-plan-page-2.pdf").read_bytes()
        response = await self.call(
            request4(content, mime="application/pdf", page_count=1),
            lambda req: httpx.Response(200, content=content),
            render_page=lambda *_: PNG_HEADER,
        )
        self.assertEqual(response.status_code, 200)
        self.assertNotIn("floorPlanPages", response.json()["result"])

    @unittest.skipUnless(importlib.util.find_spec("pypdf"), "PDF parser unavailable")
    async def test_floor_plan_metadata_preserves_partial_result_when_ocr_fails(self):
        from pypdf import PdfWriter

        original = (Path(__file__).parent / "fixtures" / "floor-plan-page-2.pdf").read_bytes()
        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        blank = BytesIO()
        writer.write(blank)
        scan = blank.getvalue()
        scan_id = "00000000-0000-0000-0000-000000000006"
        request = request4(original, mime="application/pdf", page_count=2)
        other = request4(scan, mime="application/pdf", page_count=1, document_id=scan_id)
        request["payload"]["documents"].extend(other["payload"]["documents"])
        canonical = json.dumps(request["payload"], sort_keys=True, separators=(",", ":")).encode()
        request["inputHash"] = hashlib.sha256(canonical).hexdigest()

        def fail_ocr(_):
            raise DocumentError("OCR_FAILED")

        response = await self.call(
            request,
            lambda req: httpx.Response(200, content=scan if req.url.path.endswith(scan_id) else original),
            render_page=lambda *_: PNG_HEADER, ocr=fail_ocr,
        )
        self.assertEqual(response.status_code, 200)
        result = response.json()["result"]
        self.assertEqual(result["floorPlanPages"], [{"documentId": DOCUMENT_ID, "pageNumber": 2}])
        self.assertEqual(result["unreadableFiles"], [{"documentId": scan_id, "code": "OCR_FAILED"}])
        self.assertEqual(result["resultStatus"], "PARTIAL")

    @unittest.skipUnless(importlib.util.find_spec("pypdf"), "PDF parser unavailable")
    async def test_floor_plan_metadata_preserves_partial_result_when_render_fails(self):
        content = (Path(__file__).parent / "fixtures" / "floor-plan-page-2.pdf").read_bytes()

        def fail_render(*_):
            raise DocumentError("RENDER_FAILED")

        response = await self.call(
            request4(content, mime="application/pdf", page_count=2),
            lambda req: httpx.Response(200, content=content), render_page=fail_render,
        )
        self.assertEqual(response.status_code, 200)
        result = response.json()["result"]
        self.assertEqual(result["floorPlanPages"], [{"documentId": DOCUMENT_ID, "pageNumber": 2}])
        self.assertEqual(result["unreadableFiles"], [{"documentId": DOCUMENT_ID, "code": "RENDER_FAILED"}])
        self.assertEqual(result["resultStatus"], "PARTIAL")
