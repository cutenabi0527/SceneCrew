// 미확정 초안의 공연장 평면도(venueDraft.layout, V9-D45·#372).
// AI 결과6이 준 구역 도형이나 구역 이름으로 만든 기본 배치를 서비스 도형으로 그리고, 총괄이 고친 뒤
// 미확정 초안으로 저장한다. 발행 때 BE가 안전 지도(zonePolygons)로 옮긴다(UUID 구역만).

export interface Point {
    x: number;
    y: number;
}
export interface Rect {
    x: number;
    y: number;
    width: number;
    height: number;
}
export interface DraftPolygon {
    zoneId: string;
    points: Point[];
}
export interface Evidence {
    sourceKind:
        | 'MANUAL_CONFIRMATION'
        | 'REHEARSAL_MEASUREMENT'
        | 'AUTOMATIC_MEASUREMENT'
        | 'SIMULATION_ASSUMPTION';
    sourceRef: string;
    observedAt: string | null;
    note?: string;
}
export interface DraftLayout {
    coordinateSystem: 'normalized-image';
    width: number;
    height: number;
    zonePolygons: DraftPolygon[];
    source: Evidence;
}
/** 도형을 붙일 구역(계획 초안의 id 또는 tempId와 이름) */
export interface ZoneRef {
    key: string;
    name: string;
}

/** 서버 점 개수 상한(EXT11). 사각형 도형은 4개다. */
export const MAX_POINTS = 50;
/** 기본 배치·사각형 편집의 그림 비율(가로 1000 : 세로 600) */
export const DEFAULT_SIZE = { width: 1000, height: 600 };
/** 너무 작은 도형을 막는 최소 한 변(정규화 값) */
export const MIN_SIDE = 0.03;

const round = (v: number) => Math.round(v * 1000) / 1000;
const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

/** 점들의 바깥 사각형. 편집은 사각형으로 한다. */
export function rectOf(points: Point[]): Rect {
    const xs = points.map((p) => p.x);
    const ys = points.map((p) => p.y);
    const x = Math.min(...xs);
    const y = Math.min(...ys);
    return {
        x: round(x),
        y: round(y),
        width: round(Math.max(...xs) - x),
        height: round(Math.max(...ys) - y),
    };
}

/** 0~1 안으로 자르고 최소 크기를 지킨 사각형 */
export function clampRect(r: Rect): Rect {
    const width = Math.min(1, Math.max(MIN_SIDE, r.width));
    const height = Math.min(1, Math.max(MIN_SIDE, r.height));
    const x = Math.min(1 - width, clamp01(r.x));
    const y = Math.min(1 - height, clamp01(r.y));
    return { x: round(x), y: round(y), width: round(width), height: round(height) };
}

/** 사각형 → 시계 방향 네 꼭짓점 */
export function pointsOf(r: Rect): Point[] {
    const c = clampRect(r);
    const right = round(c.x + c.width);
    const bottom = round(c.y + c.height);
    return [
        { x: c.x, y: c.y },
        { x: right, y: c.y },
        { x: right, y: bottom },
        { x: c.x, y: bottom },
    ];
}

/**
 * 구역 이름만 있을 때의 기본 배치(V9-D45): 위쪽은 무대 자리로 비워 두고, 무대 앞에 구역을 한 줄로
 * 나란히 놓는다. 자료에 없는 위치·크기를 꾸며 내지 않는 단순 배치이며 총괄이 고친다.
 */
export function defaultLayout(zones: ZoneRef[]): DraftLayout | null {
    if (!zones.length) return null;
    const gap = 0.03;
    const width = (1 - gap * (zones.length + 1)) / zones.length;
    return {
        coordinateSystem: 'normalized-image',
        ...DEFAULT_SIZE,
        zonePolygons: zones.map((z, i) => ({
            zoneId: z.key,
            points: pointsOf({ x: gap + i * (width + gap), y: 0.3, width, height: 0.6 }),
        })),
        source: {
            sourceKind: 'MANUAL_CONFIRMATION',
            sourceRef: 'plan-draft:default-arrangement',
            observedAt: null,
            note: '구역 이름으로 만든 기본 배치. 총괄이 확인하고 고친다.',
        },
    };
}

/** 028 저장 응답의 id 대응으로 임시 구역(tmp-)을 새 구역 id로 바꾼다. 바뀐 것이 없으면 그대로 돌려준다. */
export function remapLayout(
    layout: DraftLayout,
    mappings: { tempId: string; id: string }[],
): DraftLayout {
    const map = new Map(mappings.map((m) => [m.tempId, m.id]));
    if (!layout.zonePolygons.some((p) => map.has(p.zoneId))) return layout;
    return {
        ...layout,
        zonePolygons: layout.zonePolygons.map((p) => ({
            ...p,
            zoneId: map.get(p.zoneId) ?? p.zoneId,
        })),
    };
}

/** 도형이 가리키는 구역 가운데 지금 계획에 없는 것, 아직 저장되지 않은(tmp-) 것 */
export function layoutGaps(layout: DraftLayout, zones: ZoneRef[]) {
    const keys = new Set(zones.map((z) => z.key.toLowerCase()));
    const missing = layout.zonePolygons.filter((p) => !keys.has(p.zoneId.toLowerCase()));
    const temporary = layout.zonePolygons.filter((p) => p.zoneId.startsWith('tmp-'));
    const drawn = new Set(layout.zonePolygons.map((p) => p.zoneId.toLowerCase()));
    const undrawn = zones.filter((z) => !drawn.has(z.key.toLowerCase()));
    return { missing, temporary, undrawn };
}

/** 사각형 하나를 바꾼다(편집기) */
export function moveZone(layout: DraftLayout, zoneId: string, rect: Rect): DraftLayout {
    return {
        ...layout,
        zonePolygons: layout.zonePolygons.map((p) =>
            p.zoneId === zoneId ? { ...p, points: pointsOf(rect) } : p,
        ),
    };
}

/** 빠진 구역을 오른쪽 아래 빈 자리에 작은 사각형으로 더한다 */
export function addZone(layout: DraftLayout, zoneId: string): DraftLayout {
    const n = layout.zonePolygons.length;
    return {
        ...layout,
        zonePolygons: [
            ...layout.zonePolygons,
            {
                zoneId,
                points: pointsOf({ x: 0.05 + (n % 5) * 0.18, y: 0.05, width: 0.15, height: 0.15 }),
            },
        ],
    };
}

export function removeZone(layout: DraftLayout, zoneId: string): DraftLayout {
    return { ...layout, zonePolygons: layout.zonePolygons.filter((p) => p.zoneId !== zoneId) };
}

/** 글자 폭 단위. 한글은 글자 크기와 같고, 영문·숫자·띄어쓰기는 그 절반 정도로 본다. */
function textUnits(name: string) {
    return Math.max(
        1,
        [...name].reduce((n, c) => n + (/[ -~]/.test(c) ? 0.55 : 1), 0),
    );
}

/** 구역 이름 글자 크기(viewBox 단위). 도형 너비의 90% 안에 한 줄로 들어가게 줄인다.
 *  작은 도형에서 이름이 도형 밖으로 넘쳐 옆 구역과 겹쳐 보이던 문제(운영 2026-11-12, "A구역 무대 앞")를 막는다. */
export function labelSize(name: string, width: number, height: number, max = 0.035, min = 0.012) {
    return Math.max(min, Math.min(max, (width * 0.9) / textUnits(name), height * 0.6));
}

/** 배경 그림 위의 구역 이름표(V9-D62). PDF 평면도에 이미 적힌 구역 이름과 겹치지 않게 도형 위쪽 바깥에 작은 흰 이름표로 둔다.
 *  도형이 그림 맨 위에 붙어 위쪽에 자리가 없으면 도형 안 위쪽에 둔다. 좌표는 viewBox 단위(가로 0~1, 세로 0~ratio)다. */
export function labelChip(name: string, rect: Rect, ratio: number, fontSize = 0.018) {
    const pad = fontSize * 0.4;
    const width = Math.min(1, textUnits(name) * fontSize + pad * 2);
    const height = fontSize * 1.5;
    const top = rect.y * ratio;
    const gap = fontSize * 0.25;
    const y = top - height - gap >= 0 ? top - height - gap : top + gap;
    const x = Math.min(Math.max(rect.x, 0), 1 - width);
    return { x, y, width, height, fontSize, textX: x + pad, textY: y + height / 2 };
}

/** 평면도 근거가 입력 PDF의 한 쪽(`document:<id>#page=<n>`)이면 그 문서·쪽. V9-D62 배경 그림을 받을 때 쓴다. */
export function documentPage(
    layout: DraftLayout | null,
): { documentId: string; page: number } | null {
    const m =
        /^document:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})#page=([1-9][0-9]{0,3})$/i.exec(
            layout?.source?.sourceRef ?? '',
        );
    return m ? { documentId: m[1].toLowerCase(), page: Number(m[2]) } : null;
}

/** 평면도 배경 그림 안에서 실제 평면도가 놓인 영역(0~1, 왼쪽 위 기준). 화면은 이 영역만 크게 보인다(V9-D62). */
export type Crop = Rect;

/** 응답 헤더 X-Floor-Plan-Crop("x,y,width,height")을 읽는다. 없거나 범위를 벗어나면 null(쪽 전체를 보인다). */
export function parseCrop(header: string | null): Crop | null {
    const v = (header ?? '').split(',').map(Number);
    if (v.length !== 4 || v.some((n) => !Number.isFinite(n))) return null;
    const [x, y, width, height] = v;
    if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > 1.0001 || y + height > 1.0001)
        return null;
    return { x, y, width, height };
}
