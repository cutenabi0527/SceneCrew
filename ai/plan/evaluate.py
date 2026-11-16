"""최초 계획 품질 평가 (S15P31A202-252).

서로 다른 콘서트 조건의 요청(examples/eval/request-*.json)을 규칙 생성기와 GMS 생성기로 실행하고
필수 업무 누락 검사 기준의 지표를 비교한다.

    uv run python evaluate.py rule            # 규칙 결과 저장(모델 호출 없음, 고정 시각)
    uv run python evaluate.py gms [사례 ...]   # GMS 실행(사례당 최대 2회 호출). 사전 승인 후 실행한다
    uv run python evaluate.py demo-rule        # 11/09 시연 PDF 조건의 규칙 결과(examples/demo-pdf/, 모델 호출 없음)
    uv run python evaluate.py demo-gms floorplan|text|design <횟수>   # 시연 PDF 조건 GMS 실행(회당 계획 1~2회 + 평면도 1회)
    uv run python evaluate.py demo-gms design <횟수> --schedule examples/demo-pdf/schedule-p7.json   # 문서 일정표 함께 전달(408)
    uv run python evaluate.py demo-design-offline   # 대표 디자인 PDF 5쪽의 정답·렌더링·경계 보정 확인(모델 호출 없음)
    uv run --with pypdf python evaluate.py demo-candidates   # 추출기의 평면도 후보 순서(평면도 여러 장, 모델 호출 없음)

규칙 결과는 examples/eval/<사례>-rule.json이며 test_flow.py가 출력 일치를 확인한다.
GMS 결과는 examples/eval/<사례>-gms.json, 회차별 모델 원본 출력은 <사례>-gms-raw.json, 지표는 examples/eval/gms-runs.json에 실행마다 덧붙인다.
GMS 실행은 실제 시각을 쓰므로 요청의 deadlineAt을 실행 시각 + 600초로 바꿔 보낸다(저장 요청 파일은 바꾸지 않는다).
지표는 결정적으로 셀 수 있는 것만 담는다. 조건 반영 내용의 정확성·근거 없는 가정은 사람이 결과를 읽고 기록한다.
"""

import json
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import contract
import flow
import quality
import schedule

HERE = Path(__file__).parent
EVAL = HERE / "examples" / "eval"
RULE_NOW = datetime(2026, 11, 1, 3, 0, tzinfo=timezone.utc)

# (사례, 기대 분기)
CASES = [
    ("small-single-stage", "READY"),
    ("festival-multi-zone", "READY"),
    ("outdoor-long-existing-groups", "READY"),
    ("short-prep", "NEEDS_INFO"),
]


def load_request(case):
    return json.loads((EVAL / f"request-{case}.json").read_text(encoding="utf-8"))


def metrics(raw, response):
    """응답 하나의 결정적 지표."""
    branch, issues = contract.validate_response(raw, response)
    out = {"branch": branch, "contractIssues": [i["path"] for i in issues]}
    if "error" in response:
        out["error"] = {k: response["error"].get(k) for k in ("code", "details")}
        return out
    result = response["result"]
    if result["status"] == "NEEDS_INFO":
        out["questions"] = [q["key"] for q in result["questions"]]
        return out
    req = contract.parse_request(raw)
    e = req.payload.event
    tasks = result["tasks"]
    missing, _ = flow.coverage_issues(req, tasks)  # 조건 참조는 결과에 없으므로 근거(evidenceRefs)로 센다
    conditions = flow.confirmed_conditions(req)
    referenced = {r.removeprefix("condition:").split(">")[0] for r in result["coordination"]["evidenceRefs"]
                  if r.startswith("condition:")}
    programs = {x.id for x in req.payload.programs}
    linked = {t["programId"] for t in tasks if t["programId"]}
    out.update({
        # 결과6이면 평면도 읽기도 PLANNING 노드 하나로 남으므로 계획 회차에서 뺀다
        "planningRounds": sum(n["role"] == "PLANNING" for n in result["coordination"]["graph"]) - ("venueDraft" in result),
        "workGroups": len(result["workGroups"]),
        "tasks": len(tasks),
        "bundles": f"{len(result['bundles'])}/{e.totalCapacity - e.desiredReserveCount}",
        "zonesCovered": f"{len(req.payload.zones) - len({i['path'].split(':')[2] for i in missing if i['path'].startswith('coverage:zone')})}"
                        f"/{len(req.payload.zones)}",
        "entryAtOpen": "coverage:entry" not in [i["path"] for i in missing],
        "conditionsReferenced": f"{len(referenced & set(conditions))}/{len(conditions)}",
        "programsSupported": f"{len(linked & programs)}/{len(programs)}",
        "groupsCovered": groups_covered(req, tasks),
        "instructions": len(result["instructions"]),
        "assumptions": len(result["assumptions"]),
        "unresolved": len(result["unresolved"]),
    })
    out["phasesMissing"] = phases_missing(req, tasks)
    # 필수 업무 품질 게이트(S15P31A202-409): READY면 남은 누락은 항상 0이다. 게이트가 보완한 경로를 따로 센다
    out["requiredMissing"] = [i["path"] for i in quality.required_issues(req, tasks)]
    out["requiredFilled"] = [r.removeprefix("required:").split(">")[0] for r in result["coordination"]["evidenceRefs"]
                             if r.startswith("required:")]
    if "venueDraft" in result:
        out["venue"] = venue_metrics(result["venueDraft"])
    return out


PHASES = ("prep", "operation", "teardown")


def phases_missing(req, tasks):
    """구역·기존 직무 그룹마다 준비·운영·정리 업무가 있는지 본다(S15P31A202-407). 없는 칸을 "zone:이름:단계"로 낸다.

    준비는 개장 전에 시작, 운영은 [개장, 운영 종료)와 겹침, 정리는 운영 종료 뒤에 끝나는 업무다. 필수 업무 누락 판정
    (flow.coverage_issues)은 구역의 준비·정리만 보므로, 장비 직무의 공연 중 대기·철수처럼 그 판정에 걸리지 않는 빈칸을 센다.
    """
    e = req.payload.event

    def has(mine):
        spans = [(flow._at(t["startAt"]), flow._at(t["endAt"])) for t in mine]
        return {"prep": any(s < e.openAt for s, _ in spans),
                "operation": any(s < e.closeAt and x > e.openAt for s, x in spans),
                "teardown": any(x > e.closeAt for _, x in spans)}

    out = []
    for kind, items, key in (("zone", req.payload.zones, "zoneId"), ("group", req.payload.workGroups, "workGroupId")):
        for item in items:
            found = has([t for t in tasks if t[key] == item.id])
            out += [f"{kind}:{item.name}:{p}" for p in PHASES if not found[p]]
    return out


def groups_covered(req, tasks):
    """기존 직무 그룹(요청 workGroups) 가운데 업무를 1건 이상 받은 그룹 수(S15P31A202-298 장비 직무 누락 지표)."""
    used = {t["workGroupId"] for t in tasks}
    return f"{sum(g.id in used for g in req.payload.workGroups)}/{len(req.payload.workGroups)}"


def iou(a, b):
    x0, y0 = max(a["x"], b["x"]), max(a["y"], b["y"])
    x1, y1 = min(a["x"] + a["width"], b["x"] + b["width"]), min(a["y"] + a["height"], b["y"] + b["height"])
    inter = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    union = a["width"] * a["height"] + b["width"] * b["height"] - inter
    return inter / union if union else 0.0


def venue_metrics(venue, truth=None):
    """평면도 초안 지표. truth({구역 이름: 사각형})가 있으면 구역별 IoU를 더한다."""
    if venue is None:
        return {"layout": None}
    zones = {z["name"]: z for z in venue["layout"]["zones"]}
    out = {"zones": sorted(zones)}
    if truth:
        out["iou"] = {name: round(iou(zones[name], rect), 3) if name in zones else 0.0 for name, rect in truth.items()}
    return out


# ---- 11/09 확정 시연 PDF 조건 (S15P31A202-298) ----

DEMO_PDF = HERE / "examples" / "demo-pdf"
FLOOR_PLAN_PNG = HERE.parents[1] / "docs" / "output" / "demo-venue-floorplan-20261109.png"
# 평면도 원본 SVG(viewBox 1000×700)의 구역 사각형. 대체 PDF 쪽 좌표로 바꿔 정답으로 쓴다
SVG_SIZE = (1000, 700)
SVG_ZONES = {"A구역 무대 앞": (685, 190, 225, 110), "B구역 객석 뒤쪽": (685, 320, 225, 110)}
STANDIN_PAGE = (2480, 1754)            # A4 가로 비율(300dpi 픽셀)
STANDIN_IMAGE = (140, 120, 2200, 1540)  # 평면도 그림을 놓는 위치·크기(x, y, w, h)
DEMO_CASES = ("floorplan", "text", "design")
# 신기주(#377) 대표 시연 PDF v2(평면도 포함판 12쪽). 5쪽(가로 A4)에 평면도 PNG가 들어 있다(S15P31A202-407)
DESIGN_PDF = HERE.parents[1] / "docs" / "output" / "demo-pdf-20261111" / "씬크루_쇼케이스_라이브_운영계획서_평면도포함_v2.pdf"


def design_truth(pdf, page_number):
    """디자인 PDF 평면도 쪽의 정답 사각형. 쪽 안 그림 개체의 위치에 SVG 구역 좌표를 옮긴다(쪽 기준 0~1).

    렌더링(floor_plan.render)도 쪽 전체를 그리므로 같은 기준이다. 그림은 SVG viewBox와 가로세로 비가 같은 PNG 한 장이어야 한다.
    """
    import pypdfium2 as pdfium

    page = pdfium.PdfDocument(pdf)[page_number - 1]
    pw, ph = page.get_size()
    images = [o for o in page.get_objects(max_depth=5) if o.type == pdfium.raw.FPDF_PAGEOBJ_IMAGE]
    if len(images) != 1:
        raise ValueError(f"{page_number}쪽 그림이 {len(images)}개입니다. 평면도 그림 하나여야 합니다.")
    left, bottom, right, top = images[0].get_bounds()
    iw, ih = right - left, top - bottom
    sw, sh = SVG_SIZE
    if abs(iw / ih - sw / sh) > 0.01:
        raise ValueError("평면도 그림의 가로세로 비가 SVG와 다릅니다.")
    return {name: {"x": (left + x / sw * iw) / pw, "y": (ph - top + y / sh * ih) / ph,
                   "width": w / sw * iw / pw, "height": h / sh * ih / ph}
            for name, (x, y, w, h) in SVG_ZONES.items()}


def floor_plan_source(raw):
    """사례의 평면도 PDF 바이트와 정답. 평면도 없는 요청은 (None, None)."""
    fp = raw["payload"].get("floorPlan")
    if fp is None:
        return None, None
    if fp["pageNumber"] == 2 and fp["sha256"] == contract_sha(standin_pdf()[0]):
        return standin_pdf()
    pdf = DESIGN_PDF.read_bytes()
    if fp["sha256"] != contract_sha(pdf):
        raise ValueError("요청 floorPlan.sha256이 대체 PDF·디자인 PDF와 맞지 않습니다.")
    return pdf, design_truth(pdf, fp["pageNumber"])


def contract_sha(data):
    import hashlib

    return hashlib.sha256(data).hexdigest()


def model_iou(raw_outputs, truth):
    """보정 전 모델 원본 사각형의 IoU. 이름은 띄어쓰기를 무시해 맞춘다(floor_plan.to_layout과 같은 기준)."""
    if not raw_outputs or not isinstance(raw_outputs[-1], dict):
        return None
    key = lambda s: "".join(str(s).split())
    zones = {key(z.get("name")): z for z in raw_outputs[-1].get("zones") or [] if isinstance(z, dict)}
    out = {}
    for name, rect in truth.items():
        z = zones.get(key(name))
        try:
            out[name] = round(iou({k: float(z[k]) for k in ("x", "y", "width", "height")}, rect), 3) if z else 0.0
        except (KeyError, TypeError, ValueError):
            out[name] = 0.0
    return out


def standin_pdf():
    """신기주(#377)의 디자인 PDF가 저장소에 없어, 평면도 PNG를 2쪽에 여백을 두고 넣은 대체 PDF를 만든다.

    1쪽은 빈 개요 쪽이다. 반환: (PDF 바이트, 쪽 기준 0~1 정답 사각형). 정답은 SVG 구역 좌표를 그림 배치로 옮긴 값이다.
    """
    import io

    from PIL import Image, ImageDraw

    pw, ph = STANDIN_PAGE
    ix, iy, iw, ih = STANDIN_IMAGE
    cover = Image.new("RGB", STANDIN_PAGE, "white")
    ImageDraw.Draw(cover).rectangle([140, 120, pw - 140, 260], outline="black", width=4)
    plan = Image.new("RGB", STANDIN_PAGE, "white")
    plan.paste(Image.open(FLOOR_PLAN_PNG).convert("RGB").resize((iw, ih)), (ix, iy))
    buf = io.BytesIO()
    fixed = time.strptime("2026-11-09", "%Y-%m-%d")  # 생성 시각을 고정해 sha256을 매번 같게 한다
    cover.save(buf, format="PDF", save_all=True, append_images=[plan], resolution=300.0,
               creationDate=fixed, modDate=fixed)
    sw, sh = SVG_SIZE
    truth = {name: {"x": (ix + x / sw * iw) / pw, "y": (iy + y / sh * ih) / ph,
                    "width": w / sw * iw / pw, "height": h / sh * ih / ph}
             for name, (x, y, w, h) in SVG_ZONES.items()}
    return buf.getvalue(), truth


def input_hash(payload):
    """BE AiJobInputs와 같은 inputHash: 키를 재귀 정렬한 공백 없는 JSON(UTF-8, 한글 그대로)의 SHA-256."""
    import hashlib

    text = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_demo(case):
    return json.loads((DEMO_PDF / f"request-{case}.json").read_text(encoding="utf-8"))


def run_demo_rule():
    """시연 PDF 조건을 규칙 생성기로 실행한다(모델 호출 없음). 평면도는 읽지 않아 venueDraft=null이다."""
    rows = {}
    for case in DEMO_CASES:
        raw = load_demo(case)
        response = flow.run(raw, demo_now(raw))
        _write(DEMO_PDF / f"{case}-rule.json", response)
        rows[case] = metrics(raw, response)
    return rows


DEMO_NOW = datetime(2026, 11, 24, 8, 0, tzinfo=timezone.utc)


def demo_now(raw):
    """규칙 실행 시각. 요청 마감 10분 전이다(floorplan·text는 DEMO_NOW와 같고, design은 11/18 행사다)."""
    return contract.parse_request(raw).deadlineAt - timedelta(minutes=10)


def run_demo_gms(cases, runs, out_dir=None, schedule_items=None):
    """시연 PDF 조건을 GMS로 실행한다(GMS 계획 1~2회 + 평면도 1회).

    floorplan은 대체 PDF 2쪽, design은 대표 디자인 PDF 5쪽을 평면도로 읽는다. 평면도는 보정 전(modelIou)·후(venue.iou)를 함께 잰다.
    실패해도 요청·응답·모델 원본·변환 예외를 같은 이름으로 남긴다. out_dir는 시험용 저장 폴더다(기본 examples/demo-pdf/gms-runs).
    schedule_items(문서 일정표, S15P31A202-408)를 주면 생성기에 함께 넘기고 원문 시각 보존(scheduleKept)을 잰다.
    """
    from floor_plan import GmsFloorPlanReader
    from gms_generator import GmsGenerator

    out_dir = out_dir or DEMO_PDF / "gms-runs"
    out_dir.mkdir(exist_ok=True)
    rows = []
    for case in cases:
        pdf, truth = floor_plan_source(load_demo(case))
        for _ in range(runs):
            raw = load_demo(case)
            raw["deadlineAt"] = (datetime.now(timezone.utc) + timedelta(seconds=600)).isoformat().replace("+00:00", "Z")
            gen = GmsGenerator(schedule=schedule_items)
            reader = GmsFloorPlanReader(fetcher=lambda req, pdf=pdf: pdf)
            started = time.monotonic()
            response = flow.run(raw, None, gen, reader)
            seconds = round(time.monotonic() - started, 1)
            history = json.loads((out_dir / "runs.json").read_text(encoding="utf-8")) if (out_dir / "runs.json").exists() else []
            name = f"{len(history) + 1}-{case}" + ("-schedule" if schedule_items else "")
            # 공식 요청4 스냅샷. 시험 경로 scheduleItems를 포함한 실제 모델 조건은 raw.json의 modelInputs에 남긴다.
            _write(out_dir / f"{name}-request.json", raw)
            _write(out_dir / f"{name}.json", response)
            _write(out_dir / f"{name}-raw.json", {"plan": gen.raw_outputs, "modelInputs": gen.model_inputs,
                                                   "floorPlan": reader.raw_outputs, "errors": gen.errors})
            row = metrics(raw, response)
            if isinstance(row.get("venue"), dict) and row["venue"].get("layout", 1) is not None:
                row["venue"] = venue_metrics(response["result"]["venueDraft"], truth)
            if truth and reader.raw_outputs:
                row["modelIou"] = model_iou(reader.raw_outputs, truth)
            if gen.errors:
                row["convertErrors"] = gen.errors
            if schedule_items and response.get("result", {}).get("status") == "READY":
                row["scheduleKept"] = schedule.kept(schedule_items, contract.parse_request(raw), response["result"]["tasks"])
                row["scheduleApplied"] = gen.schedule_results
            row.update({"run": name, "modelVersion": response.get("modelVersion", gen.model_version),
                        "planCalls": gen.calls, "planUsage": gen.usage, "floorPlanCalls": reader.calls,
                        "floorPlanUsage": reader.usage, "seconds": seconds,
                        # 평면도는 GMS_FLOOR_PLAN_MODEL로 계획과 다른 모델을 쓸 수 있어 응답이 돌려준 모델 이름을 따로 남긴다
                        "floorPlanModelVersion": reader.model})
            history.append({"ranAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), **row})
            _write(out_dir / "runs.json", history)
            rows.append(row)
    return rows


def run_design_offline():
    """디자인 PDF 평면도 쪽을 렌더링하고, 모델 사각형이 틀어진 여러 경우에 경계 보정 뒤 IoU를 잰다(모델 호출 없음).

    가정한 모델 출력: 정답 그대로, 0.1 격자로 바깥 반올림, 사방 0.05 넓힘, 아래·오른쪽으로 0.05 밀림, A·B가 서로 바뀜.
    """
    import math

    from floor_plan import color_regions, render, snap_zones

    raw = load_demo("design")
    fp = raw["payload"]["floorPlan"]
    pdf, truth = floor_plan_source(raw)
    image = render(pdf, fp["mime"], fp["pageNumber"])
    regions = color_regions(image)

    def clip(r):
        x, y = max(0.0, r["x"]), max(0.0, r["y"])
        return {"x": x, "y": y, "width": min(1.0, r["x"] + r["width"]) - x, "height": min(1.0, r["y"] + r["height"]) - y}

    def grid(r):
        x, y = math.floor(r["x"] * 10) / 10, math.floor(r["y"] * 10) / 10
        return {"x": x, "y": y, "width": math.ceil((r["x"] + r["width"]) * 10) / 10 - x,
                "height": math.ceil((r["y"] + r["height"]) * 10) / 10 - y}

    names = list(truth)
    cases = {
        "exact": {n: truth[n] for n in names},
        "grid0.1": {n: grid(truth[n]) for n in names},
        "pad0.05": {n: clip({"x": r["x"] - 0.05, "y": r["y"] - 0.05, "width": r["width"] + 0.1, "height": r["height"] + 0.1})
                    for n, r in truth.items()},
        "shift0.05": {n: clip({**r, "x": r["x"] + 0.05, "y": r["y"] + 0.05}) for n, r in truth.items()},
        "swapped": {names[0]: truth[names[1]], names[1]: truth[names[0]]},
    }
    rows = {"page": fp["pageNumber"], "imageSize": list(image.size), "regions": len(regions),
            "truth": {n: {k: round(v, 4) for k, v in r.items()} for n, r in truth.items()}}
    for label, rects in cases.items():
        zones = [{"name": n, **r} for n, r in rects.items()]
        snapped, fixed = snap_zones(zones, regions)
        rows[label] = {"before": {z["name"]: round(iou(z, truth[z["name"]]), 3) for z in zones},
                       "after": {z["name"]: round(iou(z, truth[z["name"]]), 3) for z in snapped}, "refined": fixed}
    return rows


def run_floor_plan_candidates():
    """문서 추출기(#284)가 내는 평면도 후보 순서를 디자인 PDF로 확인한다(모델 호출·렌더링 없음, pypdf 필요).

    BE(#391)는 후보 중 첫 유효 후보 한 장만 043 floorPlan에 싣고, 이 폴더는 그 한 장만 읽는다.
    렌더러를 넘기지 않아 후보마다 unreadable RENDER_UNAVAILABLE이 붙지만 후보 목록은 그대로 나온다.
    """
    import hashlib
    import io
    import uuid

    sys.path.insert(0, str(HERE.parent))
    from document_extraction.documents import PDF, Original, read_documents
    from pypdf import PdfReader, PdfWriter

    included = DESIGN_PDF.read_bytes()
    excluded = DESIGN_PDF.with_name(DESIGN_PDF.name.replace("평면도포함", "평면도없음")).read_bytes()

    def two_plans(src):   # 5쪽 평면도를 3쪽 자리에 한 장 더 넣어 평면도 쪽이 두 장인 문서를 만든다
        reader, writer = PdfReader(io.BytesIO(src)), PdfWriter()
        for i, page in enumerate(reader.pages):
            if i == 2:
                writer.add_page(reader.pages[4])
            writer.add_page(page)
        buf = io.BytesIO()
        writer.write(buf)
        return buf.getvalue()

    def original(data, n):
        return Original(uuid.UUID(int=n), PDF, hashlib.sha256(data).hexdigest(), data)

    cases = {"included": [original(included, 1)], "excluded": [original(excluded, 2)],
             "included-two-plan-pages": [original(two_plans(included), 3)],
             "excluded-then-included": [original(excluded, 2), original(included, 1)]}
    return {name: [{"document": int(r.document_id), "pageNumber": r.page_number}
                   for r in read_documents(docs).floor_plan_pages] for name, docs in cases.items()}


def run_rule():
    rows = {}
    for case, _ in CASES:
        raw = load_request(case)
        response = flow.run(raw, RULE_NOW)
        _write(EVAL / f"{case}-rule.json", response)
        rows[case] = metrics(raw, response)
    return rows


def run_gms(cases):
    from gms_generator import GmsGenerator

    class Recording(GmsGenerator):
        """보완 요청으로 넘어온 누락 검사 위반을 기록한다."""

        def __init__(self):
            super().__init__()
            self.feedback = []
            self.raw = []   # 회차별 모델 원본 출력(실패 원인 확인용)

        def draft(self, req, feedback=None):
            if feedback:
                self.feedback.append([i["path"] for i in feedback])
            try:
                return super().draft(req, feedback)
            finally:
                self.raw.append(self._last)

    rows = {}
    for case in cases:
        raw = load_request(case)
        raw["deadlineAt"] = (datetime.now(timezone.utc) + timedelta(seconds=600)).isoformat().replace("+00:00", "Z")
        gen = Recording()
        started = time.monotonic()
        response = flow.run(raw, None, gen)
        seconds = round(time.monotonic() - started, 1)
        _write(EVAL / f"{case}-gms.json", response)
        _write(EVAL / f"{case}-gms-raw.json", gen.raw)
        row = metrics(raw, response)
        row.update({"modelVersion": response.get("modelVersion", gen.model_version),
                    "gmsCalls": gen.calls, "usage": gen.usage, "seconds": seconds,
                    "round1Missing": gen.feedback[0] if gen.feedback else []})
        rows[case] = row
        _append_run(case, row)
    return rows


def _write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _append_run(case, row):
    path = EVAL / "gms-runs.json"
    runs = json.loads(path.read_text(encoding="utf-8")) if path.exists() else []
    runs.append({"case": case, "ranAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), **row})
    _write(path, runs)


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    if argv[:1] == ["rule"]:
        rows = run_rule()
    elif argv[:1] == ["gms"]:
        names = [c for c, _ in CASES]
        unknown = [c for c in argv[1:] if c not in names]
        if unknown:
            print(f"알 수 없는 사례: {unknown}. 사용 가능: {names}")
            return 2
        rows = run_gms(argv[1:] or names)
    elif argv[:1] == ["demo-rule"]:
        rows = run_demo_rule()
    elif argv[:1] == ["demo-design-offline"]:
        rows = run_design_offline()
    elif argv[:1] == ["demo-candidates"]:
        rows = run_floor_plan_candidates()
    elif argv[:1] == ["demo-gms"] and len(argv) == 5 and argv[3] == "--schedule" and argv[2].isdigit()             and argv[1] in DEMO_CASES:
        rows = run_demo_gms([argv[1]], int(argv[2]), schedule_items=schedule.load(argv[4]))
    elif argv[:1] == ["demo-gms"] and len(argv) == 3 and argv[1] in DEMO_CASES and argv[2].isdigit():
        rows = run_demo_gms([argv[1]], int(argv[2]))
    else:
        print(__doc__)
        return 2
    print(json.dumps(rows, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
