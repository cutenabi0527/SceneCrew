"""평면도 초안(결과6 venueDraft.layout, S15P31A202-298).

요청 payload.floorPlan(V9-D54: 추출 결과3 floorPlanPages의 첫 장)의 원본을 BE 내부 문서 내려받기로 받아 그 쪽을 그림으로
렌더링하고, GMS 이미지 입력으로 구역 이름·위치·크기(0~1 비율 사각형)를 읽는다. 구역 이름은 계획 결과 zones의 이름만 쓴다.

    내려받기(sha256 대조) → 렌더링(긴 변 RENDER_LONG_SIDE px) → 격자 덧그림 → GMS 1회 → 이름 대응·범위 정리

평면도는 계획의 부가 결과라 어느 단계가 실패해도 계획 생성을 막지 않는다. 실패하면 venueDraft를 null로 두고
unresolved에 이유를 남긴다(화면이 기본 배치로 그린다). 자료에 없는 구역은 만들지 않고, 그림에서 찾지 못한 구역은 비운다.

설정(환경변수): AI_SERVICE_TOKEN(문서 내려받기 인증), AI_DOCUMENT_URL_PREFIXES(허용 주소 앞부분, 쉼표 구분.
예: https://service.p.example.test/internal/intake-documents/). GMS는 공통 클라이언트가 GMS_FLOOR_PLAN_MODEL → GMS_MODEL 순으로 쓴다.
"""

import base64
import hashlib
import io
import json
import os
from dataclasses import dataclass, field
from datetime import datetime, timezone

from contract import Unresolved

RENDER_LONG_SIDE = 1600   # 렌더링 긴 변 픽셀. 구역 글자를 읽을 만큼이고 이미지 토큰을 과하게 쓰지 않는다
MAX_DOCUMENT_BYTES = 20 * 1024 * 1024   # 문서 파일당 상한(backend/docs/file-storage.md)
MIN_SIDE = 0.01           # 이보다 좁은 사각형은 읽기 오류로 보고 버린다
MAX_CALL_SECONDS = 120
FETCH_SECONDS = 30
# 평면도를 읽은 뒤 계획 응답을 마치는 데 남겨 둘 시간. 평면도가 마감을 다 쓰면 계획 전체가 AI_TIMEOUT이 되므로
# 내려받기·모델 호출은 마감에서 이만큼 앞선 시각까지만 기다리고, 그보다 늦으면 읽지 않고 venueDraft=null로 둔다.
RESERVE_SECONDS = 10
GRID_STEPS = 10           # 모델 입력 그림에만 0.1 간격 격자와 눈금을 덧그린다(좌표 읽기 보조)

SYSTEM = """너는 공연장 평면도 그림에서 운영 구역의 위치를 읽는 담당이다. JSON 객체 하나만 출력한다.
입력은 평면도 그림 한 장과 이 행사의 구역 이름 목록(zoneNames)이다.

- zoneNames에 있는 구역만 찾는다. 그림에 이름이 글자로 적힌 영역이나 범례 색으로 구분된 영역 가운데 그 구역에 해당하는 것을 고른다.
- 각 구역을 감싸는 축 정렬 사각형을 그림 전체 기준 비율로 적는다. 왼쪽 위가 (0,0), 오른쪽 아래가 (1,1)이다.
  x·y는 사각형 왼쪽 위, width·height는 크기다. 그림에 0.1 간격 회색 격자와 눈금을 덧그려 두었으니 위치를 읽는 데 쓴다.
- 이름은 zoneNames의 값을 글자 그대로 복사한다. 범례·제목·설명 글자의 위치가 아니라 구역 영역 자체의 경계를 적는다.
- 그림에서 찾을 수 없는 구역은 zones에 넣지 말고 notFound에 이름을 적는다. 위치를 지어내지 않는다.
- zoneNames에 없는 영역(무대, 출입구, 로비, 복도, 사용하지 않는 공간 등)은 출력하지 않는다.

출력 형식
{"zones":[{"name":"구역 이름","x":0.0,"y":0.0,"width":0.0,"height":0.0}],"notFound":["구역 이름"]}"""


class FloorPlanError(Exception):
    """평면도를 읽지 못한 이유. reason은 unresolved 설명에 쓴다."""

    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


@dataclass
class VenueOutcome:
    draft: dict | None = None          # {"layout": {...}} 또는 None
    unresolved: list = field(default_factory=list)
    summary: str = ""


def _unresolved_failure(fp, reason):
    return Unresolved(key="floor-plan", refs=["payload.floorPlan"],
                      description=f"평면도({fp.pageNumber}쪽)에서 구역 위치를 읽지 못했습니다({reason}). "
                                  "기본 배치로 그린 뒤 고쳐 주세요."[:200])


# ---- 내려받기·렌더링 ----

def check_url(fp, prefixes):
    """허용한 주소 앞부분 뒤에 documentId만 붙은 주소만 받는다(STT check_audio_url과 같은 SSRF 방지)."""
    if fp.url not in {p + fp.documentId for p in prefixes}:
        raise FloorPlanError("허용되지 않은 문서 주소")


def fetch(req, token, prefixes, client=None, timeout=FETCH_SECONDS):
    """원본 문서를 받는다. 인증·Job 헤더는 문서 추출과 같은 BE 내부 내려받기 규칙(#180)을 따른다."""
    import httpx

    fp = req.payload.floorPlan
    check_url(fp, prefixes)
    headers = {"Authorization": f"Bearer {token}", "X-SceneCrew-Job-Id": req.jobId,
               "X-SceneCrew-Job-Attempt": str(req.attempt)}
    own = client is None
    client = client or httpx.Client(timeout=timeout, follow_redirects=False)
    try:
        with client.stream("GET", fp.url, headers=headers) as res:
            if res.status_code != 200:
                raise FloorPlanError(f"문서 내려받기 HTTP {res.status_code}")
            data = bytearray()
            for chunk in res.iter_bytes():
                data += chunk
                if len(data) > MAX_DOCUMENT_BYTES:
                    raise FloorPlanError("문서가 20MB를 넘음")
    except httpx.HTTPError as e:
        raise FloorPlanError(f"문서 내려받기 실패 {type(e).__name__}") from None
    finally:
        if own:
            client.close()
    if hashlib.sha256(data).hexdigest() != fp.sha256:
        raise FloorPlanError("문서 sha256 불일치")
    return bytes(data)


def render(data, mime, page_number, long_side=RENDER_LONG_SIDE):
    """문서의 한 쪽을 RGB 그림(PIL.Image)으로 만든다. 긴 변을 long_side px에 맞춘다."""
    from PIL import Image

    if mime == "application/pdf":
        import pypdfium2 as pdfium
        try:
            pdf = pdfium.PdfDocument(data)
        except pdfium.PdfiumError:
            raise FloorPlanError("PDF를 열 수 없음") from None
        try:
            if not 1 <= page_number <= len(pdf):
                raise FloorPlanError(f"PDF에 {page_number}쪽이 없음")
            page = pdf[page_number - 1]
            w, h = page.get_size()
            image = page.render(scale=long_side / max(w, h)).to_pil()
        finally:
            pdf.close()
    else:
        if page_number != 1:
            raise FloorPlanError("그림 파일은 1쪽만 있음")
        try:
            image = Image.open(io.BytesIO(data))
            image.load()
        except Exception:
            raise FloorPlanError("그림을 열 수 없음") from None
        scale = long_side / max(image.size)
        if scale < 1:
            image = image.resize((round(image.width * scale), round(image.height * scale)))
    return image.convert("RGB")


def with_grid(image):
    """모델 입력용 사본에 0.1 간격 격자와 눈금을 덧그린다. 원본 크기를 바꾸지 않아 좌표 비율이 같다."""
    from PIL import ImageDraw

    out = image.copy()
    draw = ImageDraw.Draw(out, "RGBA")
    w, h = out.size
    for i in range(1, GRID_STEPS):
        x, y = round(w * i / GRID_STEPS), round(h * i / GRID_STEPS)
        draw.line([(x, 0), (x, h)], fill=(120, 120, 120, 90), width=1)
        draw.line([(0, y), (w, y)], fill=(120, 120, 120, 90), width=1)
        draw.text((x + 2, 2), f"{i / GRID_STEPS:.1f}", fill=(90, 90, 90, 200))
        draw.text((2, y + 2), f"{i / GRID_STEPS:.1f}", fill=(90, 90, 90, 200))
    return out


def png_data_url(image):
    buf = io.BytesIO()
    image.save(buf, format="PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("ascii")


# ---- 경계 보정 ----

REFINE_LONG_SIDE = 400    # 색 영역을 찾을 때 줄인 그림의 긴 변
COLOR_STEP = 24           # 같은 색으로 볼 채널 간격
MIN_AREA_RATIO = 0.15     # 후보 영역 크기: 모델 사각형 넓이의 0.15~1.2배
MAX_AREA_RATIO = 1.2
MIN_CONTAINED = 0.6       # 후보 영역의 이 비율 이상이 모델 사각형 안에 있어야 한다
MIN_FILL = 0.5            # 후보 영역이 자기 외곽 사각형을 이만큼 채워야 한다(선·글자가 아닌 면)
# 엄격 보정이 안 될 때만 쓰는 가까운 색 영역 찾기(2026-11-12 운영 GMS: B구역 사각형이 실제 상자보다 약 0.1~0.25 아래로 밀려
# 겹침이 0~40%라 보정되지 않았다). 회색·흰색 면(복도·로비)은 구역 색으로 보지 않는다.
MIN_CHROMA = 20          # 영역 평균색의 (최대 채널 - 최소 채널). 복도·로비 회색은 12~14, 구역 연한 빨강·초록은 27 안팎
MAX_SHIFT = 0.3          # 모델 사각형 중심과 후보 영역 중심의 최대 거리(0~1 좌표)
MAX_ASPECT_FACTOR = 4    # 후보 영역과 모델 사각형의 가로세로 비가 이 배수 안이어야 한다(테두리 같은 가늘고 긴 면 제외).
                         # 운영 GMS가 세로로 긴 사각형을 낸 경우 구역 상자와 3.0배였다. 테두리 띠는 10배 이상이다


def color_regions(image):
    """줄인 그림에서 같은 색(채널 COLOR_STEP 간격)으로 이어진 영역의 외곽 사각형(0~1)·채움 비율·평균색 채도를 구한다.

    그림 테두리에 닿는 영역(바탕)은 뺀다. 반환: [(x, y, width, height, fill, chroma)]
    """
    scale = REFINE_LONG_SIDE / max(image.size)
    small = image.resize((max(1, round(image.width * scale)), max(1, round(image.height * scale))))
    w, h = small.size
    rgb = small.convert("RGB")
    pixels = list(rgb.get_flattened_data() if hasattr(rgb, "get_flattened_data") else rgb.getdata())  # Pillow 14에서 getdata 제거
    keys = [(r // COLOR_STEP, g // COLOR_STEP, b // COLOR_STEP) for r, g, b in pixels]
    seen = bytearray(w * h)
    out = []
    for start in range(w * h):
        if seen[start]:
            continue
        seen[start] = 1
        key, stack, count = keys[start], [start], 0
        x0, y0, x1, y1 = w, h, -1, -1
        sums = [0, 0, 0]
        while stack:
            i = stack.pop()
            count += 1
            r, g, b = pixels[i]
            sums[0] += r
            sums[1] += g
            sums[2] += b
            x, y = i % w, i // w
            x0, y0, x1, y1 = min(x0, x), min(y0, y), max(x1, x), max(y1, y)
            for j in ((i - 1) if x > 0 else -1, (i + 1) if x < w - 1 else -1, i - w, i + w):
                if 0 <= j < w * h and not seen[j] and keys[j] == key:
                    seen[j] = 1
                    stack.append(j)
        if x0 == 0 or y0 == 0 or x1 == w - 1 or y1 == h - 1 or count < 20:
            continue
        bw, bh = x1 - x0 + 1, y1 - y0 + 1
        mean = [v / count for v in sums]
        out.append((x0 / w, y0 / h, bw / w, bh / h, count / (bw * bh), max(mean) - min(mean)))
    return out


def refine(rect, regions):
    """모델 사각형 안에 주로 들어 있는 면 영역 가운데 가장 큰 것으로 경계를 바꾼다. 맞는 영역이 없으면 None이다.

    모델은 0.1 격자 근처로 크게 잡는 경향이 있어(2026-11-11 시연 실행 IoU 0.2~0.3) 그림의 실제 구역 상자로 맞춘다.
    """
    return _moved(rect, _contained_region(rect, regions))


def _contained_region(rect, regions):
    px, py, pw, ph = rect["x"], rect["y"], rect["width"], rect["height"]
    area = pw * ph
    best = None
    for x, y, w, h, fill, *_ in regions:
        a = w * h
        inter = max(0.0, min(x + w, px + pw) - max(x, px)) * max(0.0, min(y + h, py + ph) - max(y, py))
        if not (MIN_AREA_RATIO * area <= a <= MAX_AREA_RATIO * area) or fill < MIN_FILL or inter < MIN_CONTAINED * a:
            continue
        if best is None or a > best[2] * best[3]:
            best = (x, y, w, h)
    return best


def _moved(rect, box):
    if box is None:
        return None
    x, y, w, h = box
    return {**rect, "x": round(x, 4), "y": round(y, 4), "width": round(w, 4), "height": round(h, 4)}


def _nearest_region(rect, regions, taken):
    """엄격 보정이 안 된 사각형에 가장 가까운 색칠된 면 영역. 맞는 영역이 없으면 None이다.

    모델이 구역 위치를 크게 밀어 잡으면(겹침 60% 미만) refine이 모델 값을 그대로 둔다. 같은 크기 범위의 색칠된 면
    가운데 다른 구역이 이미 차지하지 않았고(taken), 가로세로 비가 비슷하며, 중심이 MAX_SHIFT 안인 것을 고른다.
    """
    px, py, pw, ph = rect["x"], rect["y"], rect["width"], rect["height"]
    area, cx, cy = pw * ph, px + pw / 2, py + ph / 2
    best = None
    for x, y, w, h, fill, chroma in regions:
        a = w * h
        if (x, y, w, h) in taken or fill < MIN_FILL or chroma < MIN_CHROMA:
            continue
        if not (MIN_AREA_RATIO * area <= a <= MAX_AREA_RATIO * area):
            continue
        aspect = (w / h) / (pw / ph)
        if not (1 / MAX_ASPECT_FACTOR <= aspect <= MAX_ASPECT_FACTOR):
            continue
        d = ((x + w / 2 - cx) ** 2 + (y + h / 2 - cy) ** 2) ** 0.5
        if d <= MAX_SHIFT and (best is None or d < best[0]):
            best = (d, x, y, w, h)
    return None if best is None else best[1:]


def snap_zones(zones, regions):
    """구역 사각형들을 그림의 면 영역으로 맞춘다. 먼저 겹침으로 맞추고(refine), 남은 것만 가까운 영역으로 맞춘다.

    반환: (새 구역 목록, 보정한 수). 한 영역은 한 구역에만 쓴다.
    """
    out, taken, pending, fixed = list(zones), set(), [], 0
    for i, z in enumerate(zones):
        box = _contained_region(z, regions)
        if box is None:
            pending.append(i)
            continue
        out[i], fixed = _moved(z, box), fixed + 1
        taken.add(box)
    for i in pending:
        box = _nearest_region(zones[i], regions, taken)
        if box is not None:
            out[i], fixed = _moved(zones[i], box), fixed + 1
            taken.add(box)
    return out, fixed


# ---- 모델 출력 정리 ----

def _norm(name):
    return "".join(str(name).split())


def to_layout(data, zone_names, source, size):
    """모델 JSON을 layout으로 옮긴다. 요청 구역 이름에 대응하지 않는 항목은 버리고, 범위를 0~1 안으로 자른다.

    이름은 공백을 무시하고 맞춘 뒤 요청의 이름 그대로 쓴다. 같은 구역이 두 번 나오면 처음 것만 쓴다.
    반환: (layout 또는 None, 찾지 못한 구역 이름 목록, 버린 항목 수)
    """
    by_norm = {_norm(n): n for n in zone_names}
    zones, seen, dropped = [], set(), 0
    for item in data.get("zones") or []:
        try:
            name = by_norm.get(_norm(item["name"]))
            x, y, w, h = (float(item[k]) for k in ("x", "y", "width", "height"))
        except (KeyError, TypeError, ValueError):
            dropped += 1
            continue
        if name is None or name in seen:
            dropped += 1
            continue
        x0, y0 = min(max(x, 0.0), 1.0), min(max(y, 0.0), 1.0)
        x1, y1 = min(max(x + w, 0.0), 1.0), min(max(y + h, 0.0), 1.0)
        if x1 - x0 < MIN_SIDE or y1 - y0 < MIN_SIDE:
            dropped += 1
            continue
        seen.add(name)
        zones.append({"name": name, "x": round(x0, 4), "y": round(y0, 4),
                      "width": round(x1 - x0, 4), "height": round(y1 - y0, 4)})
    missing = [n for n in zone_names if n not in seen]
    if not zones:
        return None, missing, dropped
    width, height = size
    return {"source": source, "width": width, "height": height, "zones": zones}, missing, dropped


# ---- 실행 ----

class GmsFloorPlanReader:
    """요청 하나의 평면도를 읽는다. 호출 수·사용량·원본 출력을 남긴다(GmsGenerator와 같은 기록 방식)."""

    def __init__(self, token=None, prefixes=None, model_var="GMS_FLOOR_PLAN_MODEL", fetcher=None, gms=None, clock=None):
        self.token = token if token is not None else os.environ.get("AI_SERVICE_TOKEN", "")
        env_prefixes = os.environ.get("AI_DOCUMENT_URL_PREFIXES", "")
        self.prefixes = prefixes if prefixes is not None else [p.strip() for p in env_prefixes.split(",") if p.strip()]
        self.model_var = model_var
        self.fetcher = fetcher      # 테스트: (req) -> bytes
        self._gms = gms             # 테스트: chat_json을 가진 객체
        self.clock = clock or (lambda: datetime.now(timezone.utc))   # 테스트: 고정 시각
        self.calls = 0
        self.usage = None
        self.raw_outputs = []
        self.model = None

    def _client(self):
        if self._gms is None:
            from gms_generator import _load_gms
            self._gms = _load_gms()
        return self._gms

    def read(self, req, zone_names):
        """VenueOutcome을 돌려준다. 예외를 밖으로 내지 않는다."""
        fp = req.payload.floorPlan
        if fp is None:
            return VenueOutcome(summary="평면도 없음(venueDraft=null)")
        try:
            budget = self._budget(req)
            data = self.fetcher(req) if self.fetcher else fetch(req, self.token, self.prefixes,
                                                               timeout=min(FETCH_SECONDS, budget))
            image = render(data, fp.mime, fp.pageNumber)
            raw = self._ask(req, image, zone_names)
            source = {"documentId": fp.documentId, "pageNumber": fp.pageNumber}
            layout, missing, dropped = to_layout(raw, zone_names, source, image.size)
            refined = 0
            if layout is not None:
                layout["zones"], refined = snap_zones(layout["zones"], color_regions(image))
        except FloorPlanError as e:
            return VenueOutcome(unresolved=[_unresolved_failure(fp, e.reason)],
                                summary=f"평면도를 읽지 못함({e.reason})")
        except Exception as e:  # 평면도 실패가 계획 생성을 막지 않게 한다
            return VenueOutcome(unresolved=[_unresolved_failure(fp, type(e).__name__)],
                                summary=f"평면도를 읽지 못함({type(e).__name__})")
        if layout is None:
            return VenueOutcome(unresolved=[_unresolved_failure(fp, "구역을 찾지 못함")],
                                summary="평면도에서 계획 구역을 찾지 못함")
        unresolved = []
        if missing:
            ids = {z.name: z.id for z in req.payload.zones}
            unresolved.append(Unresolved(
                key="floor-plan-zones", refs=[ids[n] for n in missing if n in ids],
                description=(f"평면도에서 구역 {', '.join(missing)}의 위치를 찾지 못했습니다. 화면에서 직접 그려 주세요.")[:200]))
        summary = f"평면도 {fp.pageNumber}쪽에서 구역 {len(layout['zones'])}/{len(zone_names)}개 위치를 읽음"
        if refined:
            summary += f", 그림의 구역 상자로 경계 보정 {refined}건"
        if dropped:
            summary += f", 계획 구역과 맞지 않는 {dropped}건 제외"
        return VenueOutcome(draft={"layout": layout}, unresolved=unresolved, summary=summary)

    def _budget(self, req):
        """평면도에 쓸 수 있는 남은 초. 마감에서 RESERVE_SECONDS를 뺀 값이 1초보다 적으면 읽지 않는다."""
        left = (req.deadlineAt - self.clock()).total_seconds() - RESERVE_SECONDS
        if left < 1:
            raise FloorPlanError("마감 임박")
        return left

    def _ask(self, req, image, zone_names):
        gms = self._client()
        budget = self._budget(req)
        messages = [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": [
                {"type": "text", "text": json.dumps({"zoneNames": list(zone_names)}, ensure_ascii=False)},
                {"type": "image_url", "image_url": {"url": png_data_url(with_grid(image)), "detail": "high"}},
            ]},
        ]
        try:
            data, model, usage = gms.chat_json(messages, temperature=0, retries=0, model_var=self.model_var,
                                               timeout=max(1, min(MAX_CALL_SECONDS, int(budget))))
        except getattr(gms, "GmsError", RuntimeError) as e:
            raise FloorPlanError(f"모델 호출 실패: {e}"[:80]) from None
        self.calls += 1
        self.model = model
        self.usage = {k: (self.usage or {}).get(k, 0) + v for k, v in (usage or {}).items() if isinstance(v, int)}
        self.raw_outputs.append(data)
        if not isinstance(data, dict):
            raise FloorPlanError("모델 출력 형식 오류")
        return data
