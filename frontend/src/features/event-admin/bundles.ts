import type { Task } from '../plan-workflow/contract';

/** 수동 최초 배정(045)으로 보낼 수 있는 업무 묶음 */
export interface UnassignedBundle {
    bundleKey: string;
    workGroupId: string;
    taskCount: number;
    startAt: string;
    endAt: string;
}

/**
 * 업무 목록에서 아직 아무에게도 처음 배정되지 않은 묶음을 고른다(V9-D47).
 * 서버 045와 같은 조건이다: 묶음의 모든 업무가 같은 그룹·대기·담당 없음·최초 배정 기록 없음이어야 한다.
 * 취소된 업무가 하나라도 섞인 묶음은 서버가 거절하므로 고를 수 없게 뺀다.
 */
export function unassignedBundles(tasks: readonly Task[]): UnassignedBundle[] {
    const groups = new Map<string, Task[]>();
    for (const task of tasks) {
        const list = groups.get(task.bundleKey);
        if (list) list.push(task);
        else groups.set(task.bundleKey, [task]);
    }
    const bundles: UnassignedBundle[] = [];
    for (const [bundleKey, list] of groups) {
        const first = list[0];
        const eligible = list.every(
            (t) =>
                t.workGroupId === first.workGroupId &&
                t.status === 'WAITING' &&
                t.assigneeId === null &&
                (t.firstAssignedAt ?? null) === null,
        );
        if (!eligible) continue;
        const starts = list.map((t) => Date.parse(t.startAt));
        const ends = list.map((t) => Date.parse(t.endAt));
        bundles.push({
            bundleKey,
            workGroupId: first.workGroupId,
            taskCount: list.length,
            startAt: new Date(Math.min(...starts)).toISOString(),
            endAt: new Date(Math.max(...ends)).toISOString(),
        });
    }
    return bundles.sort(
        (a, b) =>
            Date.parse(a.startAt) - Date.parse(b.startAt) || a.bundleKey.localeCompare(b.bundleKey),
    );
}
