import { describe, expect, it } from 'vitest';
import type { Plan, Task } from '../features/plan-workflow/contract';
import type { Member } from '../features/members/contract';
import { kstDate, kstTime, toScreen, type LiveEventDetail } from './convert';

const E = '10000000-0000-4000-8000-000000000002';
const G = '10000000-0000-4000-8000-000000000004';
const Z = '10000000-0000-4000-8000-000000000005';
const P = '10000000-0000-4000-8000-000000000006';
const M = '10000000-0000-4000-8000-000000000042';

const event: LiveEventDetail = {
    id: E,
    name: '씬크루 쇼케이스 라이브',
    state: 'RECRUITING',
    revision: '7',
    instructions: '공통 안내',
    date: '2026-11-24',
    locationName: '소공연장',
    totalCapacity: '5',
    prepStartAt: '2026-11-24T08:30:00Z',
    openAt: '2026-11-24T09:00:00Z',
    closeAt: '2026-11-24T09:35:00Z',
    teardownEndAt: '2026-11-24T09:50:00Z',
    expectedVisitors: '60',
    peakVisitors: null,
};
const plan: Plan = {
    eventId: E,
    eventRevision: '7',
    viewerRole: 'CHIEF',
    workGroups: [{ id: G, name: '음향 장비 운영', instructions: null }],
    zones: [{ id: Z, name: 'A구역 무대 앞' }],
    programs: [
        {
            id: P,
            name: '첫 곡',
            zoneId: Z,
            startAt: '2026-11-24T09:30:00Z',
            endAt: '2026-11-24T09:32:00Z',
        },
    ],
    tasks: [],
    instructions: [],
};
const task = (patch: Partial<Task>): Task => ({
    id: '10000000-0000-4000-8000-000000000061',
    workGroupId: G,
    assigneeId: null,
    title: '수신기 연결',
    status: 'WAITING',
    startAt: '2026-11-24T08:30:00Z',
    endAt: '2026-11-24T08:50:00Z',
    firstAssignedAt: null,
    zoneId: Z,
    programId: null,
    sourceIssueId: null,
    steps: ['전원 확인'],
    checklist: [{ key: 'mic', text: '메인 마이크 소리', required: true, checked: true }],
    bundleKey: '음향',
    ...patch,
});
const member = (patch: Partial<Member>): Member => ({
    memberId: M,
    displayName: '김현장',
    workGroupId: G,
    eventRole: 'WORKER',
    active: true,
    available: true,
    isReserve: false,
    participationRevision: '1',
    ...patch,
});

describe('live convert', () => {
    it('formats UTC instants in Korea time', () => {
        expect(kstTime('2026-11-24T09:30:00Z')).toBe('18:30');
        expect(kstDate('2026-11-24T15:30:00Z')).toBe('2026-11-25');
    });

    it('builds the event header from the event detail and plan', () => {
        const { info } = toScreen({ event, plan, tasks: [], members: [] });
        expect(info).toMatchObject({
            id: E,
            name: '씬크루 쇼케이스 라이브',
            date: '11월 24일',
            time: '18:00–18:35',
            place: '소공연장',
            state: 'RECRUITING',
            revision: 7,
            zones: ['A구역 무대 앞'],
            programs: [{ id: P, name: '첫 곡', start: '18:30', end: '18:32' }],
            prep: '17:30',
            teardown: '18:50',
            capacity: 5,
            visitors: '60',
            notice: '공통 안내',
        });
    });

    it('maps tasks to screen tasks and keeps event-day tasks dateless', () => {
        const { data } = toScreen({
            event,
            plan,
            tasks: [
                task({
                    status: 'IN_PROGRESS',
                    assigneeId: M,
                    firstAssignedAt: '2026-11-23T10:00:00Z',
                }),
                task({
                    id: '10000000-0000-4000-8000-000000000062',
                    startAt: '2026-11-25T01:00:00Z',
                    endAt: '2026-11-25T02:00:00Z',
                    zoneId: '10000000-0000-4000-8000-000000000099',
                }),
            ],
            members: [],
        });
        expect(data.tasks[0]).toMatchObject({
            title: '수신기 연결',
            groupId: G,
            zone: 'A구역 무대 앞',
            date: undefined,
            start: '17:30',
            end: '17:50',
            assignee: M,
            bundle: '음향',
            firstAssignedAt: '2026-11-23T10:00:00Z',
            status: 'doing',
            checklist: [{ text: '메인 마이크 소리', done: true }],
        });
        expect(data.tasks[1]).toMatchObject({
            date: '2026-11-25',
            zone: '구역 미확인',
            status: 'todo',
        });
    });

    it('keeps only active members and leaves unconnected areas empty', () => {
        const { data } = toScreen({
            event,
            plan,
            tasks: [],
            members: [
                member({}),
                member({ memberId: '10000000-0000-4000-8000-000000000043', active: false }),
            ],
        });
        expect(data.groups).toEqual([{ id: G, name: '음향 장비 운영', guide: '' }]);
        expect(data.members).toEqual([
            {
                id: M,
                name: '김현장',
                role: 'WORKER',
                groupId: G,
                isReserve: false,
                available: true,
                avail: '',
            },
        ]);
        expect(data.issues).toEqual([]);
        expect(data.equipment).toEqual([]);
        expect(data.cameras).toEqual([]);
    });

    it('shows placeholders when the event has no plan or schedule yet', () => {
        const { info, data } = toScreen({
            event: { id: E, name: '새 공연', state: 'DRAFT', revision: '1' },
            plan: null,
            tasks: [],
            members: [],
        });
        expect(info).toMatchObject({
            date: '날짜 미정',
            time: '시간 미정',
            place: '장소 미정',
            zones: [],
            programs: [],
        });
        expect(data.groups).toEqual([]);
    });
});
