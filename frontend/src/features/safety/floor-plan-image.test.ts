import { describe, expect, it } from 'vitest';
import { paddedSize } from './floor-plan-image';

describe('paddedSize', () => {
    it('틀보다 가로로 길면 가로에 맞춰 위아래 여백을 늘린다', () => {
        expect(paddedSize(1000, 400, 2)).toEqual({ w: 1000, h: 500 });
    });

    it('틀보다 세로로 길면 세로에 맞춰 좌우 여백을 늘린다', () => {
        expect(paddedSize(400, 500, 2)).toEqual({ w: 1000, h: 500 });
    });

    it('틀과 비율이 같으면 그대로 둔다', () => {
        expect(paddedSize(1000, 500, 2)).toEqual({ w: 1000, h: 500 });
    });
});
