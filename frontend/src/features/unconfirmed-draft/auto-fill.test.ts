import { describe, expect, it } from 'vitest';
import { assetsFromSheet, layoutFromFloorBoxes } from './auto-fill';

describe('상세 정보 자료 → 미확정 초안(auto-fill)', () => {
    it('평면도 구역 상자(%)를 0~1 배치도로 바꾼다', () => {
        const layout = layoutFromFloorBoxes([{ key: 'z1', rect: [10, 20, 30, 40] }], {
            width: 5177,
            height: 2748,
        })!;
        expect(layout.width).toBe(5177);
        expect(layout.zonePolygons[0].zoneId).toBe('z1');
        expect(layout.zonePolygons[0].points[0]).toEqual({ x: 0.1, y: 0.2 });
        expect(layout.zonePolygons[0].points[2]).toEqual({ x: 0.4, y: 0.6 });
        expect(layoutFromFloorBoxes([], { width: 1, height: 1 })).toBeNull();
    });

    it('머리글이 있으면 장비명·모델 열을 쓰고 빈 행·중복을 뺀다', () => {
        expect(
            assetsFromSheet([
                ['번호', '장비명', '모델', '수량'],
                ['1', '무선 마이크', 'SM58', '4'],
                ['2', '', 'X', '1'],
                ['3', '무선 마이크', 'SM58', '2'],
                ['4', '조명 콘솔', '', '1'],
            ]),
        ).toEqual([
            { name: '무선 마이크', model: 'SM58' },
            { name: '조명 콘솔', model: null },
        ]);
    });

    it('머리글이 없으면 첫 열을 이름으로 본다', () => {
        expect(assetsFromSheet([['스피커'], ['앰프']])).toEqual([
            { name: '스피커', model: null },
            { name: '앰프', model: null },
        ]);
    });
});
