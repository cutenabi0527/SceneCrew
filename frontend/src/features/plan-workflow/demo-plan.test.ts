import { describe, expect, it } from 'vitest';
import { reducer } from '../../domain/reducer';
import { EVENT_DATE, initialState } from '../../domain/seed';
import { demoDraft, planFromDraft } from './demo-plan';
import { draftProblem, tidyDraft } from './draft-check';

const chief = () =>
    reducer(reducer(initialState(), { type: 'login', me: 'm1' }), {
        type: 'enterEvent',
        eventId: 'e1',
    });
const eventOf = (s: ReturnType<typeof chief>) => s.events.find((e) => e.id === 'e1')!;

describe('데모 계획 수정', () => {
    it('지금 데모 계획은 고치지 않아도 편집기 검사를 통과한다', () => {
        const s = chief();
        const draft = demoDraft(eventOf(s), s.data, EVENT_DATE);
        expect(draftProblem(tidyDraft(draft), { relations: false })).toBeNull();
    });

    it('고치지 않고 되돌리면 구역·순서·업무 시각이 그대로다', () => {
        const s = chief();
        const ev = eventOf(s);
        const plan = planFromDraft(demoDraft(ev, s.data, EVENT_DATE), EVENT_DATE);
        expect(plan.zones).toEqual(ev.zones);
        expect(plan.programs.map((p) => [p.id, p.start, p.end])).toEqual(
            ev.programs.map((p) => [p.id, p.start, p.end]),
        );
        const t1 = s.data.tasks.find((t) => !t.copyOf)!;
        const back = plan.tasks.find((t) => t.id === t1.id)!;
        expect([back.zone, back.start, back.end]).toEqual([t1.zone, t1.start, t1.end]);
    });

    it('저장하면 고친 업무는 배정·진행 상태를 이어 받고, 뺀 업무는 지운다', () => {
        const s = chief();
        const draft = demoDraft(eventOf(s), s.data, EVENT_DATE);
        const [first, second] = draft.tasks;
        first.title = '이름 바꾼 업무';
        draft.tasks = draft.tasks.filter((t) => t !== second);
        const next = reducer(s, { type: 'replacePlan', plan: planFromDraft(draft, EVENT_DATE) });
        const before = s.data.tasks.find((t) => t.id === first.id)!;
        const after = next.data.tasks.find((t) => t.id === first.id)!;
        expect(after.title).toBe('이름 바꾼 업무');
        expect([after.assignee, after.status]).toEqual([before.assignee, before.status]);
        expect(next.data.tasks.some((t) => (t.copyOf ?? t.id) === second.id)).toBe(false);
        expect(eventOf(next).revision).toBe(eventOf(s).revision + 1);
    });
});
