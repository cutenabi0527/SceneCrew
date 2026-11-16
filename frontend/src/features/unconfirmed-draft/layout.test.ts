import { describe, expect, it } from 'vitest';
import { parseV9 } from '../../shared/api/v9';
import {
    addZone,
    clampRect,
    defaultLayout,
    documentPage,
    labelChip,
    labelSize,
    layoutGaps,
    moveZone,
    parseCrop,
    pointsOf,
    rectOf,
    remapLayout,
    removeZone,
} from './layout';

// #372: 미확정 평면도(venueDraft.layout)의 기본 배치·편집·임시 구역 id 맞추기.
const zones = [
    { key: 'tmp-a', name: 'A 구역' },
    { key: 'tmp-b', name: 'B 구역' },
    { key: 'tmp-c', name: 'C 구역' },
];

describe('평면도 초안(#372)', () => {
    it('구역 이름으로 무대 앞에 나란히 놓은 기본 배치는 계약 형식을 지킨다', () => {
        const layout = defaultLayout(zones)!;
        expect(layout.zonePolygons.map((p) => p.zoneId)).toEqual(['tmp-a', 'tmp-b', 'tmp-c']);
        const rects = layout.zonePolygons.map((p) => rectOf(p.points));
        // 위쪽은 무대 자리로 비우고, 서로 겹치지 않게 왼쪽부터 놓는다
        expect(rects.every((r) => r.y === 0.3 && r.height === 0.6)).toBe(true);
        for (let i = 1; i < rects.length; i++)
            expect(rects[i].x).toBeGreaterThan(rects[i - 1].x + rects[i - 1].width);
        expect(() => parseV9('V9DraftLayout', layout)).not.toThrow();
        expect(defaultLayout([])).toBeNull();
    });

    it('사각형은 0~1 안으로 자르고 너무 작게 만들지 않는다', () => {
        expect(clampRect({ x: 0.95, y: -0.2, width: 0.2, height: 0.001 })).toEqual({
            x: 0.8,
            y: 0,
            width: 0.2,
            height: 0.03,
        });
        expect(rectOf(pointsOf({ x: 0.1, y: 0.2, width: 0.3, height: 0.4 }))).toEqual({
            x: 0.1,
            y: 0.2,
            width: 0.3,
            height: 0.4,
        });
    });

    it('계획 초안 저장의 id 대응으로 임시 구역을 새 id로 바꾼다', () => {
        const layout = defaultLayout(zones)!;
        const id = '10000000-0000-4000-8000-0000000000aa';
        const next = remapLayout(layout, [{ tempId: 'tmp-b', id }]);
        expect(next.zonePolygons.map((p) => p.zoneId)).toEqual(['tmp-a', id, 'tmp-c']);
        // 바꿀 것이 없으면 같은 값을 돌려준다(저장할 변경으로 보지 않는다)
        expect(remapLayout(layout, [])).toBe(layout);
        expect(remapLayout(layout, [{ tempId: 'tmp-x', id }])).toBe(layout);
    });

    it('계획에 없는 구역·아직 임시인 구역·도형 없는 구역을 나눠 알린다', () => {
        const layout = removeZone(defaultLayout(zones)!, 'tmp-c');
        const gaps = layoutGaps(
            {
                ...layout,
                zonePolygons: [...layout.zonePolygons, { zoneId: 'tmp-gone', points: [] }],
            },
            zones,
        );
        expect(gaps.missing.map((p) => p.zoneId)).toEqual(['tmp-gone']);
        expect(gaps.temporary).toHaveLength(3);
        expect(gaps.undrawn.map((z) => z.key)).toEqual(['tmp-c']);
    });

    it('도형을 옮기고 더한다', () => {
        const layout = defaultLayout(zones.slice(0, 1))!;
        const moved = moveZone(layout, 'tmp-a', { x: 0.5, y: 0.5, width: 0.2, height: 0.2 });
        expect(rectOf(moved.zonePolygons[0].points)).toEqual({
            x: 0.5,
            y: 0.5,
            width: 0.2,
            height: 0.2,
        });
        const added = addZone(moved, 'tmp-b');
        expect(added.zonePolygons.map((p) => p.zoneId)).toEqual(['tmp-a', 'tmp-b']);
        expect(() => parseV9('V9DraftLayout', added)).not.toThrow();
    });

    it('구역 이름은 작은 도형의 너비 안에 들어가게 글자를 줄인다', () => {
        // 운영 2026-11-12: AI가 읽은 A구역 도형(너비 0.1375)에 "A구역 무대 앞"이 넘쳤다.
        const size = labelSize('A구역 무대 앞', 0.1375, 0.065);
        expect(size).toBeLessThan(0.035);
        expect(size * 6.65).toBeLessThanOrEqual(0.1375 * 0.9 + 1e-9); // A(0.55)+한글 5+띄어쓰기 2(0.55씩)
        expect(labelSize('A', 0.5, 0.5)).toBe(0.035);
        expect(labelSize('아주 긴 구역 이름이 들어간 경우입니다', 0.05, 0.05)).toBe(0.012);
    });

    it('평면도 근거가 입력 PDF 한 쪽이면 배경 그림을 받을 문서·쪽을 알려준다(V9-D62)', () => {
        const base = defaultLayout(zones)!;
        const at = (sourceRef: string) =>
            documentPage({ ...base, source: { ...base.source, sourceRef } });
        expect(at('document:17577B79-CA05-4DD6-B495-736E0FD04D6E#page=5')).toEqual({
            documentId: '17577b79-ca05-4dd6-b495-736e0fd04d6e',
            page: 5,
        });
        expect(at('plan-draft:default-arrangement')).toBeNull();
        expect(at('document:not-a-uuid#page=1')).toBeNull();
        expect(at('document:17577b79-ca05-4dd6-b495-736e0fd04d6e#page=0')).toBeNull();
        expect(documentPage(null)).toBeNull();
    });

    it('배경 그림 위 구역 이름표는 도형 위쪽 바깥에 두고, 위에 자리가 없으면 도형 안 위쪽에 둔다(V9-D62)', () => {
        const ratio = 1131 / 1600;
        const a = labelChip(
            'A구역 무대 앞',
            { x: 0.62, y: 0.3392, width: 0.1375, height: 0.0919 },
            ratio,
        );
        expect(a.y + a.height).toBeLessThanOrEqual(0.3392 * ratio); // 도형 위쪽 바깥
        expect(a.x).toBeCloseTo(0.62, 5);
        const top = labelChip('맨 위 구역', { x: 0.95, y: 0, width: 0.05, height: 0.1 }, ratio);
        expect(top.y).toBeGreaterThan(0); // 그림 밖으로 나가지 않고 도형 안 위쪽
        expect(top.x + top.width).toBeLessThanOrEqual(1 + 1e-9); // 오른쪽 끝을 넘지 않는다
    });

    it('평면도 영역 헤더를 읽고, 없거나 범위를 벗어나면 쪽 전체를 보인다(V9-D62)', () => {
        expect(parseCrop('0.1092,0.1720,0.6911,0.6000')).toEqual({
            x: 0.1092,
            y: 0.172,
            width: 0.6911,
            height: 0.6,
        });
        expect(parseCrop(null)).toBeNull();
        expect(parseCrop('0.5,0.5,0.6,0.2')).toBeNull(); // 오른쪽으로 넘침
        expect(parseCrop('a,b,c,d')).toBeNull();
        expect(parseCrop('0,0,0,1')).toBeNull();
    });
});
