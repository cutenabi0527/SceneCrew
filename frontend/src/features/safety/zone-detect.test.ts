import { describe, expect, it } from 'vitest';
import { detectZones, iou, nameBoxes, type Rect } from './zone-detect';

/** 흰 바탕(W×H)에 사각형을 칠한 가짜 이미지 */
function image(
    W: number,
    H: number,
    shapes: {
        rect: [number, number, number, number];
        rgb: [number, number, number];
        fill?: boolean;
    }[],
) {
    const data = new Uint8ClampedArray(W * H * 4).fill(255);
    const put = (x: number, y: number, [r, g, b]: [number, number, number]) => {
        const i = (y * W + x) * 4;
        data[i] = r;
        data[i + 1] = g;
        data[i + 2] = b;
    };
    for (const s of shapes) {
        const [x, y, w, h] = s.rect;
        for (let yy = y; yy < y + h; yy++)
            for (let xx = x; xx < x + w; xx++) {
                const edge = xx === x || yy === y || xx === x + w - 1 || yy === y + h - 1;
                if (s.fill || edge) put(xx, yy, s.rgb);
            }
    }
    return { data, width: W, height: H };
}

describe('평면도 구역 자동 인식', () => {
    it('벽(어두운 선)으로 둘러싼 방과 색칠한 구역을 각각 상자로 찾는다', () => {
        const img = image(100, 80, [
            { rect: [10, 5, 80, 15], rgb: [0, 0, 0] }, // 선으로 그린 방(스테이지)
            { rect: [10, 30, 35, 40], rgb: [200, 230, 255], fill: true }, // 색칠한 객석
            { rect: [55, 30, 35, 40], rgb: [255, 220, 200], fill: true },
        ]);
        const boxes = detectZones(img);
        expect(boxes).toHaveLength(3);
        // 위에서 아래, 왼쪽에서 오른쪽 순
        expect(boxes[0][1]).toBeLessThan(boxes[1][1]);
        expect(boxes[1][0]).toBeLessThan(boxes[2][0]);
        expect(iou(boxes[1], [10, 37.5, 35, 50])).toBeGreaterThan(0.8);
    });

    it('아무 구역도 없는 흰 이미지에서는 상자를 만들지 않는다', () => {
        expect(detectZones(image(60, 40, []))).toEqual([]);
    });

    it('기존 구역과 겹치는 상자는 그 이름을 쓰고, 나머지는 "구역 N"이 된다', () => {
        const boxes: Rect[] = [
            [20, 4, 60, 14],
            [5, 70, 20, 20],
        ];
        const named = nameBoxes(boxes, [
            { id: 'z-stage', name: '스테이지', rect: [20, 5, 60, 13] },
            { id: 'z-a', name: '구역 1', rect: [20, 22, 60, 22] },
        ]);
        expect(named[0]).toMatchObject({ id: 'z-stage', name: '스테이지' });
        expect(named[1]).toMatchObject({ id: null, name: '구역 2' });
    });
});
