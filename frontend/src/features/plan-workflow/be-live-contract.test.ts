import { describe, expect, it } from 'vitest';
import samples from './be-live-samples.json';
import { currentDraft, parse, validateDraft, type Plan, type Task } from './contract';

// 로컬 BE의 실제 027·028·050 응답을 FE의 엄격한 검사기로 읽는다(합성 데이터, 2026-11-07 수집).
// BE 응답 모양이 계약과 어긋나면 이 시험이 먼저 실패한다.
describe('BE 계획 조회/초안 저장 실제 응답', () => {
    it('총괄의 계획 조회(027)를 그대로 읽는다', () => {
        const plan = parse<Plan>('PlanView', samples.planChief);
        expect(plan.viewerRole).toBe('CHIEF');
        expect(plan.workGroups.every((g) => g.instructions !== null)).toBe(true);
    });

    it('팀원의 계획 조회(027)는 본인 그룹 안내만 담고도 같은 계약을 지킨다', () => {
        const plan = parse<Plan>('PlanView', samples.planWorker);
        expect(plan.viewerRole).toBe('WORKER');
        expect(plan.tasks.length).toBeGreaterThan(0);
    });

    it('초안 저장(028) 응답의 tempId 매핑이 저장된 계획의 실제 id를 가리킨다', () => {
        const saved = parse<{ plan: Plan; idMappings: { tempId: string; id: string }[] }>(
            'DraftPlanSaved',
            samples.draftSaved,
        );
        const ids = new Set(
            [
                saved.plan.workGroups,
                saved.plan.zones,
                saved.plan.programs,
                saved.plan.tasks,
            ].flatMap((items) => items.map((item) => item.id)),
        );
        expect(saved.idMappings.length).toBe(ids.size);
        for (const mapping of saved.idMappings) expect(ids.has(mapping.id)).toBe(true);
        expect(saved.plan.instructions).toEqual([
            { scope: 'EVENT', workGroupId: null, body: '입장 전 무전기를 켭니다.' },
        ]);
    });

    it('저장한 계획과 업무 목록(050)으로 편집 화면의 현재 초안을 다시 만들 수 있다', () => {
        const saved = parse<{ plan: Plan }>('DraftPlanSaved', samples.draftSaved);
        const tasks = parse<{ items: Task[]; nextCursor: string | null }>(
            'TaskPage',
            samples.tasksChief,
        );
        const draft = currentDraft(saved.plan, tasks.items);
        expect(draft.tasks.map((t) => t.bundleKey).sort()).toEqual(['light-a', 'sound-a']);
        // 다시 저장할 요청도 계약의 DraftPlanWrite를 통과한다.
        expect(() => validateDraft(draft, saved.plan.eventRevision)).not.toThrow();
    });
});
