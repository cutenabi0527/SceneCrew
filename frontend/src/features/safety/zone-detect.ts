/**
 * 평면도 이미지에서 구역 후보 상자를 찾는다(브라우저 안에서만, 서버·외부 AI 없음).
 * 벽·글자 같은 어두운 선을 경계로 보고, 같은 색으로 이어진 영역(벽으로 둘러싸인 방, 색칠된 구역)을 묶어
 * 너무 작거나 가는 것·바깥 배경·여러 구역을 감싸는 큰 테두리를 뺀 나머지를 상자로 돌려준다.
 * 결과는 후보일 뿐이며 사용자가 이름과 영역을 확인·수정한다.
 */
export type Rect = [x: number, y: number, w: number, h: number];
export type ImageLike = { data: Uint8ClampedArray; width: number; height: number };

const MAX_BOXES = 12;
const DARK = 90;

type Comp = { area: number; x0: number; y0: number; x1: number; y1: number; border: boolean };

function keyOf(d: Uint8ClampedArray, i: number) {
    const a = d[i + 3];
    // 투명한 곳은 흰 바탕으로 본다
    const r = a < 128 ? 255 : d[i];
    const g = a < 128 ? 255 : d[i + 1];
    const b = a < 128 ? 255 : d[i + 2];
    const lum = 0.299 * r + 0.587 * g + 0.114 * b;
    if (lum < DARK) return -1;
    return ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
}

const areaOf = (r: Rect) => r[2] * r[3];
function intersect(a: Rect, b: Rect) {
    const w = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
    const h = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
    return w > 0 && h > 0 ? w * h : 0;
}
/** 두 상자의 겹침 비율(IoU) */
export function iou(a: Rect, b: Rect) {
    const i = intersect(a, b);
    return i ? i / (areaOf(a) + areaOf(b) - i) : 0;
}

export function detectZones(img: ImageLike): Rect[] {
    const { data, width: W, height: H } = img;
    const keys = new Int16Array(W * H);
    for (let p = 0; p < W * H; p++) keys[p] = keyOf(data, p * 4);
    const seen = new Uint8Array(W * H);
    const comps: Comp[] = [];
    const stack: number[] = [];
    for (let start = 0; start < W * H; start++) {
        if (seen[start] || keys[start] < 0) continue;
        const k = keys[start];
        const c: Comp = { area: 0, x0: W, y0: H, x1: 0, y1: 0, border: false };
        stack.push(start);
        seen[start] = 1;
        while (stack.length) {
            const p = stack.pop()!;
            const x = p % W;
            const y = (p - x) / W;
            c.area++;
            if (x < c.x0) c.x0 = x;
            if (x > c.x1) c.x1 = x;
            if (y < c.y0) c.y0 = y;
            if (y > c.y1) c.y1 = y;
            if (x === 0 || y === 0 || x === W - 1 || y === H - 1) c.border = true;
            const next = [
                x > 0 ? p - 1 : -1,
                x < W - 1 ? p + 1 : -1,
                y > 0 ? p - W : -1,
                y < H - 1 ? p + W : -1,
            ];
            for (const q of next) {
                if (q >= 0 && !seen[q] && keys[q] === k) {
                    seen[q] = 1;
                    stack.push(q);
                }
            }
        }
        comps.push(c);
    }
    const total = W * H;
    // 바깥 배경: 가장자리에 닿는 가장 큰 영역
    const background = comps.filter((c) => c.border).sort((a, b) => b.area - a.area)[0];
    const pct = (c: Comp): Rect => [
        (c.x0 / W) * 100,
        (c.y0 / H) * 100,
        ((c.x1 - c.x0 + 1) / W) * 100,
        ((c.y1 - c.y0 + 1) / H) * 100,
    ];
    let boxes = comps
        .filter((c) => c !== background)
        .filter((c) => c.area >= total * 0.008)
        .filter((c) => {
            const bw = c.x1 - c.x0 + 1;
            const bh = c.y1 - c.y0 + 1;
            // 너무 가늘거나(선) 듬성한(글자 사이 틈) 영역은 뺀다
            return bw >= W * 0.04 && bh >= H * 0.04 && c.area / (bw * bh) >= 0.35;
        })
        .sort((a, b) => b.area - a.area)
        .map(pct);
    // 다른 후보를 둘 이상 감싸는 큰 테두리(전체 공연장 바깥선 안쪽 등)는 뺀다
    boxes = boxes.filter(
        (r) => boxes.filter((o) => o !== r && intersect(r, o) >= areaOf(o) * 0.9).length < 2,
    );
    // 거의 같은 상자는 하나만
    const kept: Rect[] = [];
    for (const r of boxes) if (!kept.some((k) => iou(k, r) > 0.6)) kept.push(r);
    const round = (v: number) => Math.round(v * 10) / 10;
    return kept
        .slice(0, MAX_BOXES)
        .map((r) => r.map(round) as Rect)
        .sort((a, b) => a[1] - b[1] || a[0] - b[0]);
}

export type ZoneDraft = { key: string; id: string | null; name: string; rect: Rect };

/**
 * 찾은 상자에 이름을 붙인다: 기존 구역과 많이 겹치면 그 구역(이름·id)을 이어 쓰고, 아니면 "구역 N"으로 둔다.
 */
export function nameBoxes(
    boxes: Rect[],
    existing: { id: string; name: string; rect: Rect }[],
): ZoneDraft[] {
    const used = new Set<string>();
    let n = 1;
    const taken = new Set(existing.map((z) => z.name));
    const fresh = () => {
        while (taken.has(`구역 ${n}`)) n++;
        const name = `구역 ${n}`;
        taken.add(name);
        return name;
    };
    return boxes.map((rect, k) => {
        const match = existing
            .filter((z) => !used.has(z.id))
            .map((z) => ({ z, score: iou(z.rect, rect) }))
            .sort((a, b) => b.score - a.score)[0];
        if (match && match.score > 0.3) {
            used.add(match.z.id);
            return { key: `d${k}`, id: match.z.id, name: match.z.name, rect };
        }
        return { key: `d${k}`, id: null, name: fresh(), rect };
    });
}
