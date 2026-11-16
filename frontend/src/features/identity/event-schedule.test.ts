import { describe, expect, it } from 'vitest';
import { scheduleLine } from './event-schedule';

describe('scheduleLine', () => {
    it('날짜·시간·장소를 한 줄로 쓴다', () => {
        expect(
            scheduleLine({
                date: '2026-11-14',
                openAt: '2026-11-14T08:00:00Z',
                closeAt: '2026-11-14T12:30:00Z',
                locationName: '서울 청년센터 공연장',
            }),
        ).toBe('11월 14일 / 17:00–21:30 / 서울 청년센터 공연장');
    });
    it('정해지지 않은 값은 미정으로 쓴다', () => {
        expect(scheduleLine({ date: null, openAt: null, closeAt: null, locationName: null })).toBe(
            '미정 / 미정 / 미정',
        );
    });
});
