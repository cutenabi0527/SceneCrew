import hashlib
import importlib.util
from io import BytesIO
import json
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch
from uuid import UUID

import httpx

from .documents import FloorPlanPage, MAX_TEXT_CHARS, MAX_TOTAL_BYTES, Original, read_documents, render_pdf_page
from .execution import extract
from .rendering import render_with_poppler
from .source import MAX_FILE_BYTES, DocumentError, check_original, download_original

DOCUMENT = UUID(int=1)
JOB = UUID(int=2)
PNG_HEADER = b"\x89PNG\r\n\x1a\n"
HAS_PDF_TOOLS = all(importlib.util.find_spec(name) for name in ("pypdf", "reportlab", "PIL"))


def original(content, mime="text/plain", doc_id=DOCUMENT, digest=None):
    return Original(doc_id, mime, digest or hashlib.sha256(content).hexdigest(), content)


def sample_pdf(heading=None):
    from PIL import Image
    from reportlab.lib.utils import ImageReader
    from reportlab.pdfgen.canvas import Canvas

    buffer = BytesIO()
    pdf = Canvas(buffer, pagesize=(144, 144))
    pdf.drawString(8, 125, "Cover")
    pdf.showPage()
    if heading:
        pdf.drawString(8, 125, heading)
    if heading != "Zones":
        pdf.drawImage(ImageReader(Image.new("RGB", (20, 20), "black")), 8, 15, 60, 60)
    pdf.showPage()
    pdf.save()
    return buffer.getvalue()


class ExtractionTest(unittest.TestCase):
    def test_malformed_cue_number_does_not_discard_other_fields(self):
        for number in ("²", "9" * 5000):
            with self.subTest(length=len(number)):
                content = (
                    f"행사명: Demo\n| {number} | 10:00 | 10:01 | 둘째 곡 |\n"
                    "| 1 | 09:00 | 09:01 | 첫째 곡 |"
                ).encode()
                result = extract([original(content)]).result
                self.assertEqual(result["fields"]["name"], "Demo")
                self.assertEqual(result["fields"]["programOrder"], "첫째 곡")
                self.assertEqual(result["unreadableFiles"], [])

    def test_similar_labels_and_provisional_values_do_not_fill_required_fields(self):
        text = """리허설 날짜: 2026-11-23
날짜: 2026-11-24 (임시)
최소 운영 인원은 2명이다.
공연 종료: 18:34:30
운영 종료: 18:35 (미정)
준비 시작:  17:30
"""
        result = extract([original(text.encode())]).result
        self.assertEqual(result["fields"], {"performanceEnd": "18:34:30", "prepStartAt": "17:30:00"})
        self.assertEqual(result["conflictingValues"], [])
        self.assertEqual(result["missingRequired"], [
            "date", "openAt", "closeAt", "teardownEndAt", "locationName", "address", "totalCapacity",
        ])

    def test_document_conflicts_and_reprocessing_keep_document_boundaries(self):
        first = "날짜: 2026-11-24\n| 1 | 18:30 | 18:32 | 첫 곡 '한 곡' |"
        second = "날짜: 2026-11-25\n| 2 | 18:32 | 18:35 | 둘째 곡 '두 곡' |"
        documents = [original(first.encode()), original(second.encode(), doc_id=UUID(int=3))]
        output = extract(documents)
        self.assertEqual(output, extract(documents))
        self.assertEqual(output.result["fields"], {})
        self.assertEqual(output.result["conflictingValues"], [
            {"field": "date", "values": ["2026-11-24", "2026-11-25"]},
            {"field": "programOrder", "values": ["한 곡", "두 곡"]},
        ])

    def test_file_bundle_and_character_boundaries(self):
        file = original(b" " * MAX_FILE_BYTES)
        check_original(file.content, file.sha256)
        with self.assertRaisesRegex(DocumentError, "TOO_LARGE"):
            check_original(file.content + b"x", file.sha256)
        second = original(b" " * (MAX_TOTAL_BYTES - MAX_FILE_BYTES), doc_id=UUID(int=3))
        third = original(b"", doc_id=UUID(int=4))
        self.assertEqual(len(extract([file, second, third]).result["unreadableFiles"]), 3)
        with self.assertRaisesRegex(DocumentError, "BUNDLE_LIMIT"):
            extract([file, second, original(b"x", doc_id=third.document_id)])
        with self.assertRaisesRegex(DocumentError, "BUNDLE_LIMIT"):
            extract([file, second, third, original(b"", doc_id=UUID(int=5))])
        prefix = "날짜: 2026-11-24\n"
        text = prefix + "가" * (MAX_TEXT_CHARS - len(prefix))
        accepted = extract([original(text.encode())]).result
        self.assertEqual(accepted["fields"]["date"], "2026-11-24")
        self.assertEqual(accepted["unreadableFiles"], [])

    def test_sensitive_values_are_absent_from_repr_and_invalid_pdf_logs(self):
        raw = b"private-original"
        document = original(raw, "application/pdf")
        with self.assertNoLogs("pypdf", level="WARNING"):
            result = extract([document]).result
        self.assertEqual(result["unreadableFiles"][0]["code"], "INVALID_PDF")
        self.assertNotIn("private-original", repr(document))
        self.assertNotIn("private-original", repr(FloorPlanPage(DOCUMENT, 1, document.sha256, raw)))
        labeled = original(b"\xec\xa3\xbc\xec\x86\x8c: private-original")
        self.assertNotIn("private-original", repr(read_documents([labeled])))
        self.assertNotIn("private-original", repr(extract([labeled])))

    def test_txt_complete_with_normalized_values(self):
        text = """행사 이름: 씬크루
날짜: 2026.11.24
준비 시작: 17:30
관객 입장: 18:00
운영 종료: 18:35
철수 완료: 18:50
장소: 소공연장
주소: 서울
운영 인원: 총괄 포함 5명
리허설 날짜: 2026-11-23
공연 종료: 18:34:30
"""
        result = extract([original(text.replace("\n", "\r\n").encode("utf-8-sig"))]).result
        self.assertEqual(result["resultStatus"], "COMPLETE")
        self.assertEqual(result["fields"]["date"], "2026-11-24")
        self.assertEqual(result["fields"]["totalCapacity"], "5")
        self.assertEqual(result["fields"]["closeAt"], "18:35:00")
        self.assertEqual(result["fields"]["performanceEnd"], "18:34:30")
        self.assertEqual(result["conflictingValues"], [])

    def test_real_demo_manuscript_as_markdown(self):
        root = Path(__file__).resolve().parents[2]
        doc = root / "docs/output/demo-pdf-content-20261109.md"
        result = extract([original(doc.read_bytes(), "text/markdown")]).result
        fields = result["fields"]
        self.assertEqual(fields["name"], "씬크루 쇼케이스 라이브")
        self.assertEqual(fields["totalCapacity"], "5")
        self.assertEqual(fields["zones"], "A구역 무대 앞, B구역 객석 뒤쪽")
        self.assertEqual(fields["programOrder"], "첫 번째 불빛 → 밤의 끝에서")
        self.assertIn("4분 30초", fields["duration"])
        self.assertNotIn("date", fields)
        self.assertNotIn("performanceStart", fields)
        self.assertIn("date", result["missingRequired"])
        self.assertEqual(result["conflictingValues"], [])

    def test_appendix_key_value_table_becomes_plan_conditions(self):
        # 계획서 부록 "키 값" 표(#401 운영 확인): 키 줄부터 다음 키까지 이어 붙이고, 쪽 바닥글에서 멈춘다.
        # 기본정보 키는 부록에서 받지 않고, 사람이 적은 글이라 '미정' 같은 낱말이 있어도 버리지 않는다.
        text = """부록 · 계획 생성 조건
키   값
equipment   메인·예비 무선 마이크 수신기 QLXD4 각 1(믹서 입력 1·
            2번), 객석 카메라 1.
audienceCamera   객석 고정 카메라 1대가 A·B 구역을 비춘다.
date   2026-12-01
emergency   장비 이상은 먼저 보고한다. 종료 시각은 미정이면 총괄이 정한다.
씬크루 계획서 · 12 / 12쪽
note   바닥글 뒤 줄은 표가 아니다
long   """ + "가" * 1200 + """
"""
        fields = extract([original(text.encode("utf-8"))]).result["fields"]
        self.assertEqual(fields["equipment"], "메인·예비 무선 마이크 수신기 QLXD4 각 1(믹서 입력 1·2번), 객석 카메라 1.")
        self.assertEqual(fields["audienceCamera"], "객석 고정 카메라 1대가 A·B 구역을 비춘다.")
        self.assertEqual(fields["emergency"], "장비 이상은 먼저 보고한다. 종료 시각은 미정이면 총괄이 정한다.")
        self.assertNotIn("date", fields)
        self.assertNotIn("note", fields)
        self.assertNotIn("long", fields)

    def test_schedule_programs_and_groups_become_structured_results(self):
        # V9-D66: 공연 순서 시각, 업무 일정표 행(원문 쪽 번호), 직무 그룹(처음 나온 순서)을 구조로 낸다.
        text = """공연 날짜: 2026-11-13
| 순서 | 시작 | 종료 | 곡 |
| 1 | 12:20 | 12:25 | 첫 곡 '새벽을 여는 노래' |
| 시간 | 업무 | 직무 | 구역 | 담당 |
| 11:30~11:50 | A구역 동선 점검 | 안전 통제 | A구역(무대 앞) | 안전 A |
| 11:30-12:20 | 마이크 점검 | 장비 운영 | - | 장비 |
| 미정 | 수신기 예비 점검 | 장비 오류 대처 | B구역 | 오류 |
| 25:00~25:10 | 잘못된 시각 | 장비 운영 | B구역 | 장비 |
"""
        result = extract([original(text.encode())]).result
        self.assertEqual(result["programs"], [
            {"title": "새벽을 여는 노래", "startAt": "2026-11-13T12:20:00+09:00", "endAt": "2026-11-13T12:25:00+09:00"}])
        items = result["scheduleItems"]
        self.assertEqual([i["key"] for i in items], ["d1p1r1", "d1p1r2", "d1p1r3", "d1p1r4"])
        self.assertEqual(items[0], {
            "key": "d1p1r1", "title": "A구역 동선 점검",
            "startAt": "2026-11-13T11:30:00+09:00", "endAt": "2026-11-13T11:50:00+09:00",
            "timeText": "11:30~11:50", "workGroupName": "안전 통제", "zoneName": "A구역", "bundle": "안전 A",
            "source": {"documentId": str(DOCUMENT), "pageNumber": 1}})
        self.assertIsNone(items[1]["zoneName"])
        self.assertEqual((items[2]["startAt"], items[2]["endAt"], items[2]["timeText"]), (None, None, "미정"))
        self.assertEqual((items[3]["startAt"], items[3]["endAt"]), (None, None))   # 없는 시각은 만들지 않는다
        self.assertEqual(result["workGroups"], [{"name": "안전 통제"}, {"name": "장비 운영"}, {"name": "장비 오류 대처"}])
        self.assertEqual(result["resultStatus"], "PARTIAL")   # 필수칸이 비어 있다(구조 결과는 상태를 바꾸지 않는다)

    def test_schedule_steps_and_checks_come_from_slash_separated_cells(self):
        text = (
            "공연 날짜: 2026-11-13\n"
            "| 11:30~11:50 | 마이크 점검 | 장비 운영 | A구역 | 장비 | 전원 확인 / 짝 맞춤 / 소리 확인 | 메인 소리 / 예비 소리 |\n"
            "| 11:50~12:00 | 긴 절차 | 장비 운영 | A구역 | 장비 | " + "가" * 121 + " | - |\n"
        )
        items = extract([original(text.encode())]).result["scheduleItems"]
        self.assertEqual(items[0]["steps"], ["전원 확인", "짝 맞춤", "소리 확인"])
        self.assertEqual(items[0]["checklist"], ["메인 소리", "예비 소리"])
        self.assertNotIn("steps", items[1])       # 상한을 넘으면 자르지 않고 쓰지 않는다
        self.assertNotIn("checklist", items[1])

    def test_midnight_program_is_skipped_and_lists_are_capped(self):
        lines = ["공연 날짜: 2026-11-13", "1  23:50  00:10  첫 곡 '자정'"]
        lines += [f"{n + 2}  12:{n:02d}  12:{n + 1:02d}  {n + 2}번 곡 '곡{n}'" for n in range(25)]
        lines += [f"11:00~11:10  점검  그룹{n}  A구역" for n in range(40)]
        result = extract([original("\n".join(lines).encode())]).result
        self.assertEqual(len(result["programs"]), 20)
        self.assertNotIn("자정", [p["title"] for p in result["programs"]])
        self.assertEqual(len(result["workGroups"]), 31)

    def test_schedule_without_date_keeps_rows_without_times_and_no_programs(self):
        text = "1  12:20  12:25  첫 곡\n11:30~11:50  점검  안전 통제  A구역\n"
        result = extract([original(text.encode())]).result
        self.assertNotIn("programs", result)
        self.assertEqual(result["scheduleItems"][0]["startAt"], None)
        self.assertIsNone(result["scheduleItems"][0]["bundle"])

    def test_plain_documents_have_no_structured_keys(self):
        result = extract([original("공연 이름: 씬크루\n날짜: 2026-11-24\n".encode())]).result
        for key in ("programs", "scheduleItems", "workGroups"):
            self.assertNotIn(key, result)

    def test_read_documents_keeps_original_page_numbers(self):
        read = read_documents([original(b"first"), original(b"second", doc_id=UUID(int=3))])
        self.assertEqual(read.pages, ((DOCUMENT, 1, "first"), (UUID(int=3), 1, "second")))

    def test_appendix_value_is_cut_to_condition_limit(self):
        text = "키 값\nlong   " + "가" * 1200 + "\n"
        value = extract([original(text.encode("utf-8"))]).result["fields"]["long"]
        self.assertEqual(len(value), 1000)
        self.assertTrue(value.endswith("…"))

    def test_conflict_is_not_resolved_or_reported_as_missing(self):
        text = """날짜: 2026-11-24
행사 날짜: 2026-11-25
공연일: 2026.11.24
리허설 날짜: 2026-11-23
운영 인원: 7명 (임시)
공연팀: 보컬 1명
"""
        result = extract([original(text.encode())]).result
        self.assertEqual(result["conflictingValues"], [
            {"field": "date", "values": ["2026-11-24", "2026-11-25"]}
        ])
        self.assertNotIn("date", result["fields"])
        self.assertNotIn("date", result["missingRequired"])
        self.assertIn("totalCapacity", result["missingRequired"])
        self.assertEqual(result["resultStatus"], "PARTIAL")

    def test_distinguishes_encoding_empty_and_hash_errors(self):
        result = extract([
            original(b"\xff"),
            original(b" \r\n", doc_id=UUID(int=3)),
            original(b"text", doc_id=UUID(int=4), digest="0" * 64),
        ]).result
        self.assertEqual([entry["code"] for entry in result["unreadableFiles"]],
                         ["INVALID_ENCODING", "EMPTY", "FETCH_FAILED"])

    def test_capacity_conflict_excludes_role_breakdown(self):
        text = "운영 인원: 5명 또는 6명 (총괄 1명, 관객 안전 2명)"
        result = extract([original(text.encode())]).result
        self.assertEqual(result["conflictingValues"], [
            {"field": "totalCapacity", "values": ["5", "6"]}
        ])

    def test_text_limit_does_not_create_truncated_candidate(self):
        text = "공연 이름: " + "가" * 60_000
        result = extract([original(text.encode())]).result
        self.assertNotIn("name", result["fields"])
        self.assertEqual(result["unreadableFiles"][0]["code"], "TEXT_LIMIT")

    def test_result3_matches_existing_openapi(self):
        if not all(importlib.util.find_spec(name) for name in ("yaml", "jsonschema")):
            self.skipTest("Existing schema verification tools are unavailable")
        import jsonschema
        import yaml

        root = Path(__file__).resolve().parents[2]
        path = root / "docs/api-contract/openapi-implementation.yaml"
        yaml_doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        schema = yaml_doc["components"]["schemas"]["ExtractionPublicResult"]
        validator = jsonschema.Draft202012Validator({**schema, "components": yaml_doc["components"]})
        result = extract([original("날짜: 2026-11-24 / 2026-11-25".encode()),
                          original(b"\xff", doc_id=UUID(int=3))]).result
        validator.validate(result)
        self.assertFalse(validator.is_valid({**result, "questions": []}))

    @unittest.skipUnless(importlib.util.find_spec("pypdf") and shutil.which("pdftoppm"),
                         "PDF parser or Poppler unavailable")
    def test_handoff_fixture_binds_request_reference_to_the_original_pdf(self):
        root = Path(__file__).parent
        fixture = json.loads((root / "floor_plan_contract_fixtures.json").read_text(encoding="utf-8"))
        for case, entries in fixture["originals"].items():
            with self.subTest(case=case), self.assertNoLogs(level="INFO"):
                docs = [
                    Original(UUID(entry["documentId"]), "application/pdf", entry["sha256"],
                             (root / entry["file"]).read_bytes())
                    for entry in entries
                ]
                output = extract(docs, render_page=render_with_poppler)
                self.assertEqual({**output.result, "floorPlanPages": output.floor_plan_metadata()},
                                 fixture["proposedResult3"][case])
                if case != "single":
                    continue
                reference = fixture["proposed043PayloadFragment"]["floorPlan"]
                candidate, = output.floor_plan_pages
                self.assertEqual((str(candidate.document_id), candidate.source_sha256, candidate.page_number),
                                 (reference["documentId"], reference["sha256"], reference["pageNumber"]))
                source = Original(UUID(reference["documentId"]), "application/pdf",
                                  reference["sha256"], docs[0].content)
                self.assertEqual(render_pdf_page(source, reference["pageNumber"]), output.floor_plans[0].png)


@unittest.skipUnless(HAS_PDF_TOOLS, "PDF fixture tools are unavailable")
class PdfTest(unittest.TestCase):
    def test_pdf_page_limit_is_aggregate_and_stops_before_ocr(self):
        from pypdf import PdfWriter

        def pdf(count, doc_id):
            writer = PdfWriter()
            for _ in range(count):
                writer.add_blank_page(width=144, height=144)
            buffer = BytesIO()
            writer.write(buffer)
            return original(buffer.getvalue(), "application/pdf", doc_id=doc_id)

        calls = []

        def render(content, page, dpi):
            calls.append(page)
            return PNG_HEADER

        docs = [pdf(29, DOCUMENT), pdf(1, UUID(int=3)), pdf(1, UUID(int=4))]
        output = extract(docs, render_page=render, ocr=lambda png: "날짜: 2026-11-24")
        self.assertEqual(calls, list(range(1, 30)) + [1])
        self.assertEqual(output.result["unreadableFiles"], [
            {"documentId": str(UUID(int=4)), "code": "PAGE_LIMIT"},
        ])

    def test_an_ordinary_page_with_an_image_is_not_a_floor_plan(self):
        document = original(sample_pdf("Equipment photo"), "application/pdf")
        output = extract([document], render_page=render_with_poppler)
        self.assertEqual(output.floor_plans, [])
        self.assertEqual(output.floor_plan_metadata(), [])

    def test_single_reference_survives_without_an_in_memory_png(self):
        document = original(sample_pdf("Floor plan"), "application/pdf")
        output = extract([document])
        self.assertEqual(output.floor_plans, [])
        self.assertEqual(output.floor_plan_metadata(), [{"documentId": str(DOCUMENT), "pageNumber": 2}])
        candidate, = output.floor_plan_pages
        self.assertEqual((candidate.document_id, candidate.page_number, candidate.source_sha256),
                         (DOCUMENT, 2, document.sha256))

    def test_references_preserve_all_candidates_in_document_then_page_order(self):
        from pypdf import PdfWriter

        content = sample_pdf("Floor plan")
        writer = PdfWriter()
        writer.append(BytesIO(content))
        writer.append(BytesIO(content))
        buffer = BytesIO()
        writer.write(buffer)
        first_id = UUID(int=9)
        docs = [original(buffer.getvalue(), "application/pdf", doc_id=first_id),
                original(content, "application/pdf")]
        output = extract(docs, render_page=lambda *args: PNG_HEADER)
        self.assertEqual(output.floor_plan_metadata(), [
            {"documentId": str(first_id), "pageNumber": 2},
            {"documentId": str(first_id), "pageNumber": 4},
            {"documentId": str(DOCUMENT), "pageNumber": 2},
        ])
        self.assertEqual(output.floor_plan_pages, extract(docs).floor_plan_pages)

    def test_duplicate_page_candidates_collapse(self):
        from . import documents

        pdf_pages = documents._pdf_pages

        def repeat(*args):
            for page in pdf_pages(*args):
                yield page
                if page[-1] is not None:
                    yield page

        document = original(sample_pdf("Floor plan"), "application/pdf")
        with patch.object(documents, "_pdf_pages", side_effect=repeat):
            output = extract([document], render_page=lambda *args: PNG_HEADER)
        self.assertEqual(output.floor_plan_metadata(), [{"documentId": str(DOCUMENT), "pageNumber": 2}])

    def test_handoff_render_rejects_bad_page_or_source_before_poppler(self):
        def unexpected(*args):
            self.fail("Invalid handoff must not invoke the renderer")

        document = original(sample_pdf("Floor plan"), "application/pdf")
        for page in (0, -1, 3, True, 2.0, "2"):
            with self.subTest(page=page), self.assertRaisesRegex(DocumentError, "^INVALID_PAGE$"):
                render_pdf_page(document, page, render_page=unexpected)
        changed = original(sample_pdf("Other page"), "application/pdf", digest=document.sha256)
        with self.assertRaisesRegex(DocumentError, "^FETCH_FAILED$"):
            render_pdf_page(changed, 2, render_page=unexpected)
        self.assertEqual(extract([changed], render_page=unexpected).floor_plan_metadata(), [])

    def test_korean_text_pdf_extracts_labeled_event_fields(self):
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        from reportlab.pdfgen.canvas import Canvas

        buffer = BytesIO()
        pdf = Canvas(buffer)
        pdfmetrics.registerFont(UnicodeCIDFont("HYSMyeongJo-Medium"))
        pdf.setFont("HYSMyeongJo-Medium", 12)
        pdf.drawString(40, 760, "날짜: 2026-11-24")
        pdf.drawString(40, 740, "운영 인원: 총괄 포함 5명")
        pdf.save()

        result = extract([original(buffer.getvalue(), "application/pdf")]).result
        self.assertEqual(result["fields"]["date"], "2026-11-24")
        self.assertEqual(result["fields"]["totalCapacity"], "5")
        self.assertEqual(result["unreadableFiles"], [])

    def test_pdf_reads_in_order_and_selects_only_image_page(self):
        from .documents import read_documents

        calls = []

        def render(content, number, dpi):
            calls.append((number, dpi))
            return PNG_HEADER

        document = original(sample_pdf("Floor plan"), "application/pdf")
        output = read_documents([document], render_page=render)
        self.assertEqual(output.document_pages, [["Cover", "Floor plan"]])
        self.assertEqual(output.unreadable, [])
        self.assertEqual(calls, [(2, 150)])
        image, = output.floor_plans
        self.assertEqual((image.document_id, image.page_number, image.source_sha256, image.png),
                         (DOCUMENT, 2, document.sha256, PNG_HEADER))

    def test_scan_invokes_ocr_for_textless_page_only(self):
        calls = []

        def render(content, number, dpi):
            calls.append((number, dpi))
            return PNG_HEADER

        document = original(sample_pdf(), "application/pdf")
        output = extract([document], render_page=render, ocr=lambda png: "날짜: 2026-11-24")
        self.assertEqual(calls, [(2, 150)])
        self.assertEqual(output.result["fields"]["date"], "2026-11-24")
        self.assertEqual(output.floor_plans, [])
        missing_runtime = extract([document])
        self.assertEqual(missing_runtime.result["unreadableFiles"][0]["code"], "OCR_UNAVAILABLE")

    def test_no_floor_plan_does_not_render(self):
        def unexpected(*args):
            self.fail("A text-only zones page must not be rendered")

        document = original(sample_pdf("Zones"), "application/pdf")
        self.assertEqual(extract([document], render_page=unexpected).floor_plans, [])

    @unittest.skipUnless(shutil.which("pdftoppm"), "Existing Poppler executable is unavailable")
    def test_existing_poppler_renders_selected_page_at_150_dpi(self):
        from PIL import Image

        document = original(sample_pdf("Floor plan"), "application/pdf")
        output = extract([document], render_page=render_with_poppler)
        image, = output.floor_plans
        with Image.open(BytesIO(image.png)) as raster:
            self.assertEqual(raster.size, (300, 300))
            raster.verify()
        candidate, = output.floor_plan_pages
        self.assertEqual(render_pdf_page(document, candidate.page_number), image.png)


class DownloadTest(unittest.IsolatedAsyncioTestCase):
    async def fetch(self, handler, **overrides):
        values = {
            "base_url": "https://documents.example",
            "document_id": DOCUMENT,
            "job_id": JOB,
            "attempt": 2,
            "service_token": "fixture-token",
            "sha256": hashlib.sha256(b"original").hexdigest(),
            "timeout": 1,
            "transport": httpx.MockTransport(handler),
        }
        return await download_original(**(values | overrides))

    async def test_download_binds_job_attempt_and_validates_bytes(self):
        def handler(request):
            self.assertEqual(str(request.url), f"https://documents.example/internal/intake-documents/{DOCUMENT}")
            self.assertEqual(request.headers["Authorization"], "Bearer fixture-token")
            self.assertEqual(request.headers["X-SceneCrew-Job-Id"], str(JOB))
            self.assertEqual(request.headers["X-SceneCrew-Job-Attempt"], "2")
            return httpx.Response(200, content=b"original")

        self.assertEqual(await self.fetch(handler), b"original")
        with self.assertRaisesRegex(DocumentError, "FETCH_FAILED") as error:
            await self.fetch(handler, sha256="0" * 64)
        self.assertFalse(error.exception.retryable)

    async def test_redirect_is_not_followed_and_503_is_retryable(self):
        for status in (302, 401, 404, 503):
            with self.subTest(status=status):
                calls = []

                def handler(request):
                    calls.append(request.url)
                    return httpx.Response(status, headers={"Location": "https://other.example/"})

                with self.assertRaisesRegex(DocumentError, "FETCH_FAILED") as error:
                    await self.fetch(handler)
                self.assertEqual(error.exception.retryable, status == 503)
                self.assertEqual(len(calls), 1)

    async def test_rejects_path_in_origin_or_identifier_before_network(self):
        def unexpected(*args):
            self.fail("Invalid input must not reach HTTP")

        for origin in ("https://documents.example/other", "https://documents.example/?",
                       "https://docs.example@other.example", "https://documents.example/%2e"):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                await self.fetch(unexpected, base_url=origin)
        with self.assertRaises(ValueError):
            await self.fetch(unexpected, document_id="../other")
        with self.assertRaises(ValueError):
            await self.fetch(unexpected, base_url="https://documents.example\n")
        with self.assertRaises(ValueError):
            await self.fetch(unexpected, service_token="fixture-token\n")
        with self.assertRaises(ValueError):
            await self.fetch(unexpected, timeout=float("nan"))

    async def test_slow_response_exceeds_total_download_timeout(self):
        import asyncio

        async def handler(request):
            await asyncio.sleep(0.05)
            return httpx.Response(200, content=b"original")

        with self.assertRaisesRegex(DocumentError, "FETCH_FAILED") as error:
            await self.fetch(handler, timeout=0.01)
        self.assertTrue(error.exception.retryable)
