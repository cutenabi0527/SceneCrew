import { describe, expect, it } from 'vitest';
import { axisSlots } from './useTimeZoom';

describe('axisSlots', () => {
    it('간격이 좁으면 30분마다 눈금을 적는다', () => {
        expect(axisSlots(600, 660, 4)).toEqual([600, 630, 660]);
    });

    it('간격이 넓으면 10분마다 눈금을 적는다', () => {
        expect(axisSlots(600, 630, 6)).toEqual([600, 610, 620, 630]);
    });
});
