import { describe, expect, it } from 'vitest';
import type { Task } from '../plan-workflow/contract';
import { unassignedBundles } from './bundles';

const G1 = '00000000-0000-4000-8000-000000000001';
const G2 = '00000000-0000-4000-8000-000000000002';
const M1 = '00000000-0000-4000-8000-0000000000a1';

function task(
    id: string,
    bundleKey: string,
    start: string,
    end: string,
    extra: Partial<Task> = {},
): Task {
    return {
        id: `00000000-0000-4000-8000-${id.padStart(12, '0')}`,
        workGroupId: G1,
        assigneeId: null,
        title: `업무 ${id}`,
        status: 'WAITING',
        startAt: start,
        endAt: end,
        firstAssignedAt: null,
        zoneId: '00000000-0000-4000-8000-0000000000f1',
        programId: null,
        sourceIssueId: null,
        steps: ['단계'],
        checklist: [],
        bundleKey,
        ...extra,
    };
}

describe('unassignedBundles', () => {
    it('groups whole bundles with task count and overall time range', () => {
        const result = unassignedBundles([
            task('2', 'sound', '2026-11-24T09:00:00Z', '2026-11-24T09:20:00Z'),
            task('1', 'sound', '2026-11-24T08:30:00Z', '2026-11-24T08:50:00Z'),
            task('3', 'safety', '2026-11-24T08:50:00Z', '2026-11-24T09:00:00Z'),
        ]);
        expect(result).toEqual([
            {
                bundleKey: 'sound',
                workGroupId: G1,
                taskCount: 2,
                startAt: '2026-11-24T08:30:00.000Z',
                endAt: '2026-11-24T09:20:00.000Z',
            },
            {
                bundleKey: 'safety',
                workGroupId: G1,
                taskCount: 1,
                startAt: '2026-11-24T08:50:00.000Z',
                endAt: '2026-11-24T09:00:00.000Z',
            },
        ]);
    });

    it('excludes a bundle when any task is assigned, started, canceled, or was assigned before', () => {
        const at = ['2026-11-24T09:00:00Z', '2026-11-24T09:10:00Z'] as const;
        const result = unassignedBundles([
            task('1', 'assigned', ...at),
            task('2', 'assigned', ...at, { assigneeId: M1 }),
            task('3', 'started', ...at, { status: 'IN_PROGRESS' }),
            task('4', 'canceled', ...at, { status: 'CANCELED' }),
            task('5', 'released', ...at, { firstAssignedAt: '2026-11-24T08:00:00Z' }),
            task('6', 'open', ...at),
        ]);
        expect(result.map((b) => b.bundleKey)).toEqual(['open']);
    });

    it('excludes a bundle whose tasks span different groups', () => {
        const at = ['2026-11-24T09:00:00Z', '2026-11-24T09:10:00Z'] as const;
        expect(
            unassignedBundles([
                task('1', 'mixed', ...at),
                task('2', 'mixed', ...at, { workGroupId: G2 }),
            ]),
        ).toEqual([]);
    });

    it('treats a missing firstAssignedAt as not yet assigned', () => {
        const at = ['2026-11-24T09:00:00Z', '2026-11-24T09:10:00Z'] as const;
        const legacy = task('1', 'legacy', ...at);
        delete legacy.firstAssignedAt;
        expect(unassignedBundles([legacy]).map((b) => b.bundleKey)).toEqual(['legacy']);
    });
});
