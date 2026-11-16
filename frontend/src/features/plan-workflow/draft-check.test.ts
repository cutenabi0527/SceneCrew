import { describe, expect, it } from 'vitest';
import { validateDraft, type Draft } from './contract';
import { draftProblem, explainDraftRejection, tidyDraft } from './draft-check';

// #387: 초안 저장 전 검사. 고칠 곳을 항목 이름으로 알리고, 통과한 초안은 계약 검사도 통과해야 한다.
const G = 'tmp-group1',
    Z = 'tmp-zone1',
    P = 'tmp-prog1',
    T = 'tmp-task1';
const at = (h: number) => `2026-11-18T${String(h).padStart(2, '0')}:00:00+09:00`;
function draft(task: Partial<Draft['tasks'][number]> = {}, extra: Partial<Draft> = {}): Draft {
    return {
        workGroups: [{ tempId: G, name: '음향', instructions: '' }],
        zones: [{ tempId: Z, name: '무대', description: null }],
        programs: [{ tempId: P, name: '1부', zoneId: Z, startAt: at(17), endAt: at(19) }],
        instructions: [],
        tasks: [
            {
                tempId: T,
                workGroupId: G,
                zoneId: Z,
                programId: P,
                bundleKey: '음향 준비',
                title: '마이크 점검',
                startAt: at(17),
                endAt: at(18),
                steps: ['전원 확인'],
                checklist: [],
                ...task,
            },
        ],
        ...extra,
    } as Draft;
}

describe('초안 저장 전 검사(#387)', () => {
    it('올바른 초안은 통과하고 계약 검사도 통과한다', () => {
        expect(draftProblem(draft())).toBeNull();
        expect(() => validateDraft(draft(), '1')).not.toThrow();
    });

    it('빈 수행 단계 줄은 저장할 때 뺀다', () => {
        const tidy = tidyDraft(draft({ steps: ['', ' 전원 확인 ', '  '] }));
        expect(tidy.tasks[0].steps).toEqual(['전원 확인']);
        expect(draftProblem(tidy)).toBeNull();
        expect(() => validateDraft(tidy, '1')).not.toThrow();
    });

    it.each([
        ['빈 시작 시각', { startAt: '' }, '업무 1(마이크 점검)의 시작 시각을 선택해주세요.'],
        [
            '시간대 없는 시각',
            { endAt: '2026-11-18T18:00' },
            '업무 1(마이크 점검)의 종료 시각을 선택해주세요.',
        ],
        [
            '거꾸로 된 시각',
            { startAt: at(19), endAt: at(18) },
            '업무 1(마이크 점검)의 종료 시각을 시작 시각보다 뒤로 정해주세요.',
        ],
        ['빈 제목', { title: '' }, '업무 1(제목 없음)의 제목을 입력해주세요.'],
        ['빈 업무 묶음', { bundleKey: '' }, '업무 1(마이크 점검)의 업무 묶음 이름을 입력해주세요.'],
        [
            '수행 단계 없음',
            { steps: [] },
            '업무 1(마이크 점검)의 수행 단계를 한 줄 이상 적어주세요.',
        ],
        [
            '수행 단계 7개',
            { steps: ['a', 'b', 'c', 'd', 'e', 'f', 'g'] },
            '업무 1(마이크 점검)의 수행 단계는 6개 이하로 줄여주세요.',
        ],
        [
            '직무 그룹 미선택',
            { workGroupId: '' },
            '업무 1(마이크 점검)의 직무 그룹을 선택해주세요.',
        ],
        ['구역 미선택', { zoneId: '' }, '업무 1(마이크 점검)의 구역을 선택해주세요.'],
        [
            '빈 점검 항목',
            { checklist: [{ key: 'k1', text: '', required: false }] },
            '업무 1(마이크 점검)의 점검 항목 1의 내용을 입력해주세요.',
        ],
    ])('업무 칸 검사: %s', (_, task, message) => {
        const value = tidyDraft(draft(task as Partial<Draft['tasks'][number]>));
        expect(draftProblem(value)).toBe(message);
        // 사람이 고칠 곳이 있는 초안은 계약 검사에서도 거절된다(보내지 않는 것이 맞다)
        expect(() => validateDraft(value, '1')).toThrow();
    });

    it('그룹/구역/공연 순서/공통 안내의 빈 값과 상한을 알린다', () => {
        const base = draft();
        expect(draftProblem({ ...base, workGroups: [{ ...base.workGroups[0], name: '' }] })).toBe(
            '직무 그룹 1의 이름을 입력해주세요.',
        );
        expect(
            draftProblem({
                ...base,
                workGroups: [{ ...base.workGroups[0], name: '가'.repeat(21) }],
            }),
        ).toBe('직무 그룹 1의 이름은 20자 이하로 줄여주세요.');
        expect(draftProblem({ ...base, zones: [{ ...base.zones[0], name: ' ' }] })).toBe(
            '구역 1(이름 없음)의 이름을 입력해주세요.',
        );
        expect(draftProblem({ ...base, programs: [{ ...base.programs[0], endAt: '' }] })).toBe(
            '공연 순서 1(1부)의 종료 시각을 선택해주세요.',
        );
        expect(
            draftProblem({
                ...base,
                instructions: [{ scope: 'EVENT', workGroupId: null, body: '' }],
            }),
        ).toBe('공통 안내 1의 내용을 입력해주세요.');
    });

    it('새로 추가한 업무 그대로는 저장하지 않는다', () => {
        // 업무 추가 버튼의 기본값(빈 제목·묶음·시각, 빈 단계 한 줄)
        const added = tidyDraft(
            draft({
                title: '',
                bundleKey: '',
                startAt: '',
                endAt: '',
                steps: [''],
                programId: null,
            }),
        );
        expect(draftProblem(added)).toBe('업무 1(제목 없음)의 제목을 입력해주세요.');
    });
});

describe('서버만 알던 관계 규칙(#387, be/04)', () => {
    const Z2 = 'tmp-zone2';
    const withZone2 = (d: Draft): Draft => ({
        ...d,
        zones: [...d.zones, { tempId: Z2, name: '로비', description: null }],
    });
    const second = (task: Partial<Draft['tasks'][number]>) => ({
        ...draft().tasks[0],
        tempId: 'tmp-task2',
        title: '조명 점검',
        ...task,
    });

    it.each([
        [
            '공연 순서와 다른 구역',
            withZone2(draft({ zoneId: Z2 })),
            '업무 1(마이크 점검)의 구역이 연결한 공연 순서 1(1부)의 구역과 다릅니다. 같은 구역을 고르거나 공연 순서 연결을 빼주세요.',
        ],
        [
            '공연 순서와 겹치지 않는 시간',
            draft({ startAt: at(15), endAt: at(16) }),
            '업무 1(마이크 점검)의 시간이 연결한 공연 순서 1(1부)의 시간과 겹치지 않습니다. 시간을 맞추거나 공연 순서 연결을 빼주세요.',
        ],
        [
            '같은 묶음의 시간 겹침',
            draft(
                { programId: null },
                {
                    tasks: [
                        { ...draft().tasks[0], programId: null },
                        second({ programId: null, startAt: at(17), endAt: at(19) }),
                    ],
                },
            ),
            "업무 묶음 '음향 준비' 안에서 업무 1(마이크 점검), 업무 2(조명 점검)의 시간이 겹칩니다. 한 사람이 맡으므로 시간을 나누거나 묶음 이름을 다르게 정해주세요.",
        ],
        [
            '같은 묶음의 다른 그룹',
            {
                ...draft(),
                workGroups: [
                    ...draft().workGroups,
                    { tempId: 'tmp-group2', name: '조명', instructions: '' },
                ],
                tasks: [
                    draft().tasks[0],
                    second({ workGroupId: 'tmp-group2', startAt: at(18), endAt: at(19) }),
                ],
            } as Draft,
            "업무 묶음 '음향 준비'에 다른 직무 그룹의 업무가 섞였습니다(업무 1(마이크 점검), 업무 2(조명 점검)). 한 묶음은 한 사람이 맡으므로 같은 그룹이어야 합니다.",
        ],
        [
            '공백·대소문자만 다른 그룹 이름',
            {
                ...draft(),
                workGroups: [
                    ...draft().workGroups,
                    { tempId: 'tmp-group2', name: ' 음 향 ', instructions: '' },
                ],
            } as Draft,
            '직무 그룹 2의 이름이 직무 그룹 1의 이름과 같습니다. 이름을 다르게 정해주세요.',
        ],
    ])('%s를 알린다(계약 형식은 통과한다)', (_, value, message) => {
        expect(() => validateDraft(value, '1')).not.toThrow();
        expect(draftProblem(value)).toBe(message);
    });

    it('묶음이 같아도 시간이 이어지면 통과한다', () => {
        const value = {
            ...draft(),
            tasks: [draft().tasks[0], second({ startAt: at(18), endAt: at(19) })],
        } as Draft;
        expect(draftProblem(value)).toBeNull();
    });

    it('서버 거절 사유를 항목 이름으로 바꾼다', () => {
        expect(
            explainDraftRejection(
                [{ field: 'tasks[0].programId', reason: 'PROGRAM_CONTEXT' }],
                draft(),
            ),
        ).toBe(
            '서버가 초안을 받지 않았습니다. 업무 1(마이크 점검): 연결한 공연 순서와 구역이 같고 시간이 겹쳐야 합니다.',
        );
        expect(explainDraftRejection([{ field: 'x', reason: 'NEW_RULE' }], draft())).toBe(
            '서버가 초안을 받지 않았습니다. 초안: 값을 확인해주세요(NEW_RULE).',
        );
        expect(explainDraftRejection(undefined, draft())).toBeUndefined();
    });
});
