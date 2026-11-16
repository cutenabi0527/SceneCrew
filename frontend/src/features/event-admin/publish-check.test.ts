import { describe, expect, it } from 'vitest';
import type { BasicKey } from './labels';
import { publishChecklist } from './publish-check';

const filled: Record<BasicKey, string> = {
    name: '여름 공연',
    description: '야외 공연',
    date: '2026-12-01',
    prepStartAt: '2026-12-01T09:00',
    openAt: '2026-12-01T18:00',
    closeAt: '2026-12-01T21:00',
    teardownEndAt: '2026-12-01T23:00',
    locationName: '한강공원',
    address: '서울시',
    totalCapacity: '20',
    expectedVisitors: '',
    peakVisitors: '',
    desiredReserveCount: '2',
};
const ok = { unsaved: false, planEditing: false, plan: { workGroups: 1, tasks: 3 } };

describe('공개 조건 안내(publishChecklist)', () => {
    it('모두 채우면 비어 있다', () => {
        expect(publishChecklist({ values: filled, ...ok })).toEqual([]);
    });

    it('빈 필수 칸을 한 줄로 모은다', () => {
        const lines = publishChecklist({
            values: { ...filled, date: '', address: ' ' },
            ...ok,
        });
        expect(lines[0]).toBe('입력할 항목: 공연 날짜, 주소');
    });

    it('시각 순서·같은 날·정원·예비 인원을 검사한다', () => {
        const lines = publishChecklist({
            values: {
                ...filled,
                openAt: '2026-12-01T22:00',
                teardownEndAt: '2026-12-02T01:00',
                totalCapacity: '40',
                desiredReserveCount: '40',
            },
            ...ok,
        });
        expect(lines).toContain('시각 순서: 준비 시작 ≤ 공연 시작 < 공연 종료 ≤ 철수 종료');
        expect(lines).toContain('준비 시작과 철수 종료는 공연 날짜와 같은 날이어야 합니다');
        expect(lines).toContain('운영 인원 정원은 1~31명입니다');
        expect(lines).toContain('희망 예비 인원은 0명 이상, 운영 인원 정원보다 적어야 합니다');
    });

    it('빈 계획과 저장하지 않은 편집을 알리고, 계획을 못 읽었으면 계획 조건은 띄우지 않는다', () => {
        expect(
            publishChecklist({
                values: filled,
                unsaved: true,
                planEditing: true,
                plan: { workGroups: 0, tasks: 0 },
            }),
        ).toEqual([
            '상세 정보 업로드에서 직무 그룹을 1개 이상 만들어 저장해주세요',
            '상세 정보 업로드에서 업무를 1개 이상 만들어 저장해주세요',
            '입력한 기본정보를 임시 저장해주세요',
            '상세 정보의 계획 편집을 저장해주세요',
        ]);
        expect(publishChecklist({ values: filled, ...ok, plan: null })).toEqual([]);
    });
});
