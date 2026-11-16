import { describe, expect, it } from 'vitest';
import { incompleteLine } from './labels';
import { fromLocalInput, toLocalInput } from './time';

describe('공연 관리 시각 변환(#286)', () => {
    it('datetime-local 값에 이 기기 시간대 오프셋을 붙이고 같은 순간으로 되돌린다', () => {
        const iso = fromLocalInput('2026-11-14T18:30');
        expect(iso).toMatch(/^2026-11-14T18:30:00[+-]\d{2}:\d{2}$/);
        expect(toLocalInput(new Date(iso!).toISOString())).toBe('2026-11-14T18:30');
    });
    it('형식이 다르거나 없는 날짜는 null/빈 값이다', () => {
        expect(fromLocalInput('2026-02-30T10:00')).toBeNull();
        expect(fromLocalInput('2026-11-14 10:00')).toBeNull();
        expect(toLocalInput(null)).toBe('');
        expect(toLocalInput('x')).toBe('');
    });
});

describe('공개 거절 문구(#286)', () => {
    it('빈 계획과 기본정보 위반을 사람이 읽는 말로 바꾼다', () => {
        expect(incompleteLine({ field: 'workGroups', reason: 'MISSING' })).toContain('직무 그룹');
        expect(incompleteLine({ field: 'tasks', reason: 'MISSING' })).toContain('업무가 1개 이상');
        expect(incompleteLine({ field: 'openAt', reason: 'ORDER' })).toBe(
            '공연 시작 시각: 시각 순서가 맞지 않습니다(준비 ≤ 공연 시작 < 공연 종료 ≤ 철수)',
        );
        expect(incompleteLine({ field: 'unknownField', reason: 'NEW_REASON' })).toBe(
            'unknownField: NEW_REASON',
        );
    });
});
