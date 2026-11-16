import { describe, expect, it } from 'vitest';
import { example, isDate, localTime, shownTime } from './basic-form';

describe('기본정보 날짜·시각 글자 칸(basic-form)', () => {
    it('저장 값은 보기 형식으로, 친 글자는 그대로 보인다', () => {
        expect(shownTime('openAt', '2026-12-24T18:00')).toBe('18:00');
        expect(shownTime('prepStartAt', '2026-12-24T09:00')).toBe('2026-12-24 09:00');
        expect(shownTime('openAt', '18:')).toBe('18:');
    });

    it('시각만 칸은 공연 날짜와 합치고, 날짜가 없거나 틀리면 null', () => {
        expect(localTime('openAt', '9:05', '2026-12-24')).toBe('2026-12-24T09:05');
        // 저장 값이 옛 날짜여도 지금 공연 날짜로 맞춘다
        expect(localTime('closeAt', '2026-12-01T21:00', '2026-12-24')).toBe('2026-12-24T21:00');
        expect(localTime('openAt', '18:00', '')).toBeNull();
        expect(localTime('openAt', '25:00', '2026-12-24')).toBeNull();
        expect(localTime('openAt', ' ', '2026-12-24')).toBe('');
    });

    it('준비·철수 칸은 날짜와 시각을 함께 받는다', () => {
        expect(localTime('prepStartAt', '2026-12-24 9:00', '')).toBe('2026-12-24T09:00');
        expect(localTime('teardownEndAt', '2026-02-30 23:00', '')).toBeNull();
        expect(localTime('teardownEndAt', '23:00', '2026-12-24')).toBeNull();
    });

    it('날짜 검사와 예시', () => {
        expect(isDate('2026-12-24')).toBe(true);
        expect(isDate('2026-13-01')).toBe(false);
        expect(example('prepStartAt', '2027-01-05')).toBe('예: 2027-01-05 09:00');
        expect(example('openAt', '')).toBe('예: 18:00');
    });
});
