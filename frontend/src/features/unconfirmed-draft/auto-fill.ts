import { pointsOf, type DraftLayout } from './layout';

// 상세 정보 업로드에서 올린 자료를 미확정 초안으로 옮기는 변환(#372 미확정 초안 탭).
// - 평면도: 이 브라우저에 둔 평면도의 구역 상자(%) → 미확정 초안 배치도(0~1 정규화 좌표)
// - 장비 목록: 엑셀/CSV 첫 시트의 행 → 장비 후보(이름·모델)

/** 평면도 구역 상자 하나. key는 계획 초안 구역(id 또는 tempId), rect는 그림 기준 % [x, y, w, h] */
export type FloorBox = { key: string; rect: [number, number, number, number] };

/** 평면도 상자로 배치도를 만든다. 상자가 없으면 null */
export function layoutFromFloorBoxes(
    boxes: FloorBox[],
    size: { width: number; height: number },
): DraftLayout | null {
    if (!boxes.length) return null;
    return {
        coordinateSystem: 'normalized-image',
        width: size.width,
        height: size.height,
        zonePolygons: boxes.map((b) => ({
            zoneId: b.key,
            points: pointsOf({
                x: b.rect[0] / 100,
                y: b.rect[1] / 100,
                width: b.rect[2] / 100,
                height: b.rect[3] / 100,
            }),
        })),
        source: {
            sourceKind: 'MANUAL_CONFIRMATION',
            sourceRef: 'plan-draft:floor-plan-upload',
            observedAt: null,
        },
    };
}

/** 장비 후보(이름·모델) */
export type SheetAsset = { name: string; model: string | null };

const NAME_HEADER = /장비\s*명|장비\s*이름|품명|이름|name|장비/i;
const MODEL_HEADER = /모델|model|규격|기종/i;

/**
 * 장비 목록 시트의 행을 장비 후보로 바꾼다. 첫 행이 머리글(장비명·모델 등)이면 그 열을 쓰고,
 * 아니면 첫 열을 이름으로 본다. 이름이 빈 행과 같은 이름·모델의 중복은 뺀다.
 */
export function assetsFromSheet(rows: string[][]): SheetAsset[] {
    if (!rows.length) return [];
    const head = rows[0].map((c) => c.trim());
    const nameCol = head.findIndex((c) => NAME_HEADER.test(c) && !MODEL_HEADER.test(c));
    const modelCol = head.findIndex((c) => MODEL_HEADER.test(c));
    const hasHeader = nameCol >= 0 || modelCol >= 0;
    const body = hasHeader ? rows.slice(1) : rows;
    const n = nameCol >= 0 ? nameCol : 0;
    const seen = new Set<string>();
    const out: SheetAsset[] = [];
    for (const row of body) {
        const name = (row[n] ?? '').trim();
        if (!name) continue;
        const model = modelCol >= 0 && modelCol !== n ? (row[modelCol] ?? '').trim() || null : null;
        const key = `${name}\u0000${model ?? ''}`;
        if (seen.has(key)) continue;
        seen.add(key);
        out.push({ name, model });
    }
    return out;
}
