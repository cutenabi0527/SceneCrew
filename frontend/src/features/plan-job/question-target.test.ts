import { describe, expect, it } from 'vitest';
import { questionHint, questionTarget } from './question-target';

const q = (field: string, key = 'k') => ({ key, field, question: '질문' });

describe('계획 생성 추가 질문의 보완 위치(#268)', () => {
    it('AI가 내는 기본정보 위치는 기본정보로 안내한다', () => {
        expect(questionTarget(q('payload.event.prepStartAt'))).toEqual({
            kind: 'basic',
            label: '준비 시작 시각',
        });
        expect(questionTarget(q('payload.event.totalCapacity'))).toEqual({
            kind: 'basic',
            label: '운영 인원 정원(1~31)',
        });
        expect(questionTarget(q('payload.event'))).toEqual({
            kind: 'basic',
            label: '공연 기본정보',
        });
    });

    it('구역/직무 그룹/공연 순서는 계획 초안 편집으로 안내한다', () => {
        expect(questionTarget(q('payload.zones'))).toEqual({ kind: 'plan', label: '구역' });
        expect(questionTarget(q('payload.workGroups[0].name'))).toEqual({
            kind: 'plan',
            label: '직무 그룹',
        });
        expect(questionTarget(q('payload.unknown'))).toEqual({ kind: 'plan', label: '계획 입력' });
    });

    it('계획 생성 조건과 위치가 아닌 이름은 조건 칸으로 답한다', () => {
        expect(questionTarget(q('payload.event.confirmedInput.audience'))).toEqual({
            kind: 'condition',
            key: 'audience',
        });
        expect(questionTarget(q('payload.event.confirmedInput', 'parking'))).toEqual({
            kind: 'condition',
            key: 'parking',
        });
        expect(questionTarget(q('관객 수'))).toEqual({ kind: 'condition', key: '관객 수' });
    });

    it('안내 문구는 조건 질문에는 없고, 셸 안에서는 공연 관리 위치를 붙인다', () => {
        expect(questionHint(q('관객 수'), true)).toBeNull();
        expect(questionHint(q('payload.event.prepStartAt'), true)).toContain('공연 관리의');
        expect(questionHint(q('payload.event.prepStartAt'), false)).not.toContain('아래');
        expect(questionHint(q('payload.zones'), false)).toContain('계획 초안 편집');
    });
});
