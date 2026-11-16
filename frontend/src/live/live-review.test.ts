import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Action } from '../domain/reducer';
import { canTakeBundle, changeStaleReasons, unassignedBundles } from '../domain/selectors';
import type { DemoState, EventData, EventInfo, Member, Task } from '../domain/types';
import { canEdit } from '../features/change/change-doc';
import type { Workflow } from '../features/plan-workflow/client';
import type { Plan, Task as ApiTask } from '../features/plan-workflow/contract';
import type { ChangeSet } from '../features/response/contract';
import { ApiError } from '../shared/api/client';
import { emptyData, screenDate, toScreen, type LiveEventDetail } from './convert';
import { expandReleases, lookup, toChange } from './convert-ops';
import { OPS_HANDLERS, editedOperations, kstRange } from './handlers-ops';
import { fillOps, type OpsRaw } from './ops';
import { UNKNOWN, type LiveRuntime } from './runtime';
import { assignBundle, emptyIfDenied, runWrite } from './write';

// 실서버 모드 리뷰(#362·#371) 결함 수정의 단위 시험.

type Fake = Record<string, ReturnType<typeof vi.fn>>;
const fakes = vi.hoisted(() => ({
    desk: {} as Record<string, unknown>,
    equip: {} as Record<string, unknown>,
}));
vi.mock('../features/response/client', () => ({ responder: () => fakes.desk }));
vi.mock('../features/equipment/commands', () => ({ equipmentCommands: () => fakes.equip }));

const E = '10000000-0000-4000-8000-000000000002';
const G = '10000000-0000-4000-8000-000000000004';
const Z = '10000000-0000-4000-8000-000000000005';
const P = '10000000-0000-4000-8000-000000000006';
const T = '10000000-0000-4000-8000-000000000061';
const M1 = '10000000-0000-4000-8000-000000000041';
const M2 = '10000000-0000-4000-8000-000000000042';
const A1 = '10000000-0000-4000-8000-0000000000a1';
const A2 = '10000000-0000-4000-8000-0000000000a2';

const plan = {
    eventId: E,
    eventRevision: '9',
    viewerRole: 'CHIEF',
    workGroups: [{ id: G, name: '음향', instructions: null }],
    zones: [{ id: Z, name: '입구' }],
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
} as Plan;
const apiTask = {
    id: T,
    workGroupId: G,
    assigneeId: M1,
    title: '메인 마이크 교체',
    status: 'WAITING',
    startAt: '2026-11-24T09:00:00Z',
    endAt: '2026-11-24T09:20:00Z',
    firstAssignedAt: '2026-11-24T08:00:00Z',
    zoneId: Z,
    programId: null,
    sourceIssueId: null,
    steps: ['예비 마이크를 가져온다'],
    checklist: [],
    bundleKey: '음향',
} as unknown as ApiTask;
const look = lookup(plan, [apiTask]);

const changeSet = (operations: ChangeSet['operations']): ChangeSet =>
    ({
        id: 'c-1',
        eventId: E,
        status: 'READY',
        revision: '2',
        baseRevision: '9',
        mode: 'MANUAL',
        operations,
        rationale: '변경',
        sourceJobId: null,
        issueId: null,
        previewHash: 'h',
        diff: [],
        eventRevision: '9',
        simulationRefs: [],
        evidenceSnapshot: null,
    }) as ChangeSet;

const runtime = (): LiveRuntime =>
    ({
        session: {},
        client: { scope: {} },
        eventRevision: '9',
        chief: false,
        plan,
        tasks: [apiTask],
        ops: {
            issues: [],
            changeSets: [],
            assets: [],
            reservations: [],
            connections: [],
            simulations: new Map(),
            analysisJobs: new Map(),
        },
        opIndex: new Map(),
        recoveryIssues: new Set(),
        analysisJobs: new Map(),
    }) as unknown as LiveRuntime;
const state = { data: emptyData() } as DemoState;
const run = (r: LiveRuntime, action: Action) => OPS_HANDLERS[action.type]!(r, action, state);
const report = (text: string): Action => ({
    type: 'report',
    input: {
        id: 'x',
        taskId: null,
        issueId: null,
        zone: '입구',
        text,
        via: '텍스트',
        kind: 'INITIAL',
    },
});

describe('같은 키 의도: 새 입력이 남은 의도와 다르면 버리고 보낸다(리뷰 1)', () => {
    let pending: Set<string>;
    let desk: Fake;
    let equip: Fake;
    beforeEach(() => {
        pending = new Set();
        desk = {
            pending: vi.fn((op: string, target: string) => pending.has(`${op}:${target}`)),
            forget: vi.fn((op: string, target: string) => pending.delete(`${op}:${target}`)),
            // 응답을 받지 못해 결과를 모른다(의도 유지)
            createIssue: vi.fn(async () => {
                pending.add('createIssue:new');
                return { kind: 'check' };
            }),
        };
        equip = {
            pending: vi.fn((key: string) => pending.has(key)),
            forget: vi.fn((key: string) => pending.delete(key)),
        };
        fakes.desk = desk;
        fakes.equip = equip;
    });

    it('결과를 모르는 보고 A 뒤에 보고 B를 보내면 A의 의도를 버리고 B를 보낸다. 같은 B는 같은 키로 다시 보낸다', async () => {
        const r = runtime();
        await run(r, report('보고 A'));
        expect(desk.forget).not.toHaveBeenCalled();
        await run(r, report('보고 B'));
        expect(desk.forget).toHaveBeenCalledWith('createIssue', 'new');
        expect(desk.createIssue).toHaveBeenLastCalledWith(
            expect.objectContaining({ text: '보고 B' }),
        );
        desk.forget.mockClear();
        await run(r, report('보고 B'));
        expect(desk.forget).not.toHaveBeenCalled();
    });

    it('장비 가져오기는 결과를 모르고 멈춘 줄부터 이어 가고, 다른 장비 등록은 남은 의도를 버린다', async () => {
        const sent: string[] = [];
        equip.register = vi.fn(async (input: { name: string }) => {
            sent.push(input.name);
            if (input.name === '나' && !sent.slice(0, -1).includes('나')) {
                pending.add('register');
                return { kind: 'retry' };
            }
            pending.delete('register');
            return { kind: 'done', data: { eventRevision: String(sent.length + 9) } };
        });
        const items = ['가', '나', '다'].map((name) => ({ name, kind: '마이크', place: '입구' }));
        const r = runtime();
        const first = await run(r, { type: 'importEquipment', items } as Action);
        expect(first).toContain('1건을 등록하고 멈췄습니다');
        const second = await run(r, { type: 'importEquipment', items } as Action);
        // '가'를 다시 등록하지 않는다
        expect(sent).toEqual(['가', '나', '나', '다']);
        expect(second).toBe('앞서 등록한 1건을 건너뛰고 장비 2건을 이어서 등록했습니다.');

        pending.add('register');
        await run(r, { type: 'addEquipment', name: '라', kind: '스피커', place: '입구' } as Action);
        expect(equip.forget).toHaveBeenCalledWith('register');
        expect(sent.at(-1)).toBe('라');
    });

    it('복구 계산 이슈를 기억해 다시 읽을 때 결과를 함께 읽게 한다(리뷰 16)', async () => {
        desk.recover = vi.fn(async () => ({ kind: 'rejected' }));
        const r = runtime();
        await run(r, { type: 'recoveryRun', issueId: 'i-9', assetIds: [A1] } as Action);
        expect(r.recoveryIssues.has('i-9')).toBe(true);
    });
});

describe('변경안 편집을 연산별 계약 칸으로 되돌려 쓴다(리뷰 2, 9/15)', () => {
    const set = changeSet([
        {
            type: 'ADD_TASK',
            payload: {
                workGroupId: G,
                zoneId: Z,
                title: '예비 마이크 설치',
                startAt: '2026-11-24T09:10:00Z',
                endAt: '2026-11-24T09:25:00Z',
                steps: ['설치'],
                assigneeId: null,
            },
        },
        { type: 'UPDATE_TASK', payload: { taskId: T, title: '메인 마이크 교체' } },
        { type: 'ASSIGN_TASK', payload: { taskId: T, fromMemberId: M1, toMemberId: M2 } },
        { type: 'UPDATE_PROGRAM', payload: { programId: P, name: '첫 곡 변경' } },
    ]);
    const { change, opIndex } = toChange(set, look);
    const base = ({ taskId, programId }: { taskId?: string; programId?: string }) =>
        taskId === T ? apiTask.startAt : programId === P ? plan.programs[0].startAt : undefined;

    it('연산마다 편집기에서 고칠 수 있는 칸을 계약에 맞춰 두고, 확인 필요 사항은 고칠 수 없다', () => {
        expect(change.ops.map((o) => (o.kind === 'cancel' ? [] : o.editable))).toEqual([
            ['시간', '담당', '절차'],
            ['시간', '절차'],
            ['담당'],
            ['시간'],
        ]);
        expect(change.fixedChecks).toBe(true);
        expect(canEdit(change.ops[2], '시간')).toBe(false);
        expect(canEdit(change.ops[0], '추가 담당')).toBe(false);
        // 데모 연산(editable 없음)은 모든 칸을 고친다
        expect(canEdit({ ...change.ops[2], editable: undefined } as never, '추가 담당')).toBe(true);
    });

    it('시각 없는 프로그램 변경은 현재 계획 시각을 이전/이후 값으로 둔다(영구 낡음 없음)', () => {
        expect(change.ops[3]).toMatchObject({
            program: P,
            before: { start: '18:30', end: '18:32' },
            after: { start: '18:30', end: '18:32' },
        });
        const ev = { revision: 9, programs: [] } as unknown as EventInfo;
        expect(changeStaleReasons(emptyData(), ev, { ...change, ops: [change.ops[3]] })).toEqual(
            [],
        );
    });

    it('업무 추가의 담당/절차, 업무 수정의 절차, 담당 변경의 새 담당, 프로그램 시각을 보낸다', () => {
        const edited = change.ops.map((o, j) => {
            if (o.kind === 'add')
                return { ...o, steps: ['가져오기', '설치'], after: { ...o.after, assignee: M2 } };
            if (o.kind === 'update' && j === 1)
                return { ...o, after: { ...o.after, steps: ['새 절차'] } };
            if (o.kind === 'update') return { ...o, after: { ...o.after, assignee: M1 } };
            if (o.kind === 'program') return { ...o, after: { start: '18:40', end: '18:42' } };
            return o;
        });
        const ops = editedOperations(set, opIndex, edited, base, change.ops);
        expect(ops[0].payload).toMatchObject({ assigneeId: M2, steps: ['가져오기', '설치'] });
        // 시각을 고치지 않은 업무 수정에는 시각을 새로 넣지 않는다
        expect(ops[1].payload).toEqual({
            taskId: T,
            title: '메인 마이크 교체',
            steps: ['새 절차'],
        });
        expect(ops[2].payload).toEqual({ taskId: T, fromMemberId: M1, toMemberId: M1 });
        expect(ops[3].payload).toMatchObject({
            startAt: '2026-11-24T09:40:00.000Z',
            endAt: '2026-11-24T09:42:00.000Z',
        });
    });

    it('종료가 시작보다 이르면 다음 날 종료로 본다', () => {
        expect(kstRange('2026-11-24', '23:30', '00:30')).toEqual({
            startAt: '2026-11-24T14:30:00.000Z',
            endAt: '2026-11-24T15:30:00.000Z',
        });
        expect(kstRange('2026-11-24', '18:00', '18:30').endAt).toBe('2026-11-24T09:30:00.000Z');
    });
});

describe('여러 장비 예약의 해제 목록을 화면 예약 줄로 펼친다(리뷰 3)', () => {
    it('예약id#n 줄을 모두 해제 대상으로 보고, 화면에 없는 예약은 그대로 둔다', () => {
        const rows = [
            { id: 'res-2#0', assetId: A1 },
            { id: 'res-2#1', assetId: A2 },
            { id: 'res-1', assetId: A1 },
        ] as never;
        expect(expandReleases(['res-2', 'res-1', 'gone'], rows)).toEqual([
            'res-2#0',
            'res-2#1',
            'res-1',
            'gone',
        ]);
        expect(expandReleases(undefined, rows)).toBeUndefined();
    });

    it('불러온 변경안이 다중 장비 예약을 해제해도 낡았다고 보지 않는다', () => {
        const raw: OpsRaw = {
            issues: [],
            changeSets: [
                changeSet([
                    {
                        type: 'CANCEL_EQUIPMENT_RESERVATION',
                        payload: { reservationId: 'res-2', expectedRevision: '1', reason: '대체' },
                    },
                ]),
            ],
            assets: [],
            reservations: [
                {
                    id: 'res-2',
                    eventId: E,
                    assetIds: [A1, A2],
                    programId: null,
                    taskId: T,
                    startAt: '2026-11-24T09:00:00Z',
                    endAt: '2026-11-24T09:20:00Z',
                    status: 'PLANNED',
                    revision: '1',
                },
            ],
            connections: [],
            simulations: new Map(),
            analysisJobs: new Map(),
        };
        const data = emptyData();
        fillOps(data, raw, plan, [apiTask]);
        expect(data.changes[0].releases).toEqual(['res-2#0', 'res-2#1']);
        const ev = { revision: 9, programs: [] } as unknown as EventInfo;
        expect(changeStaleReasons(data, ev, data.changes[0])).toEqual([]);
    });
});

describe('화면 기준 날짜는 행사 날짜다(리뷰 4)', () => {
    const event: LiveEventDetail = {
        id: E,
        name: '공연',
        state: 'READY' as LiveEventDetail['state'],
        revision: '9',
        date: '2026-11-24',
    };
    it('행사 전날에 열어도 행사 날짜를 기준으로 당일 업무에 날짜를 붙이지 않는다', () => {
        const day = screenDate(event, '2026-11-20T03:00:00Z');
        expect(day).toBe('2026-11-24');
        const { data } = toScreen({ event, plan, tasks: [apiTask], members: [] }, day);
        expect(data.tasks[0].date).toBeUndefined();
        expect(data.bundleRule).toBe('server');
    });
    it('행사 날짜를 모르면 한국 오늘 날짜를 쓴다', () => {
        expect(screenDate({ ...event, date: null }, '2026-11-20T16:00:00Z')).toBe('2026-11-21');
    });
});

describe('불러오기/쓰기 처리(리뷰 7, 8/11, 12)', () => {
    it('권한 없음만 빈 목록으로 두고 일시 실패는 불러오기 실패로 던진다', () => {
        expect(emptyIfDenied(new ApiError(403, null))).toEqual([]);
        expect(() => emptyIfDenied(new ApiError(503, null))).toThrow();
        expect(() => emptyIfDenied(new TypeError('network'))).toThrow();
    });

    it('처리 뒤 다시 읽기만 실패하면 처리 결과를 유지하고 화면 갱신 실패를 따로 알린다', async () => {
        const fail = () => Promise.reject(new Error('offline'));
        expect(
            await runWrite(
                async () => '보고를 접수했습니다.',
                async () => undefined,
            ),
        ).toBe('보고를 접수했습니다.');
        const text = await runWrite(async () => '보고를 접수했습니다.', fail);
        expect(text).toMatch(/^보고를 접수했습니다\. 다만 화면을 새로 고치지 못했습니다/);
        const reload = vi.fn(async () => undefined);
        expect(await runWrite(() => Promise.reject(new ApiError(503, null)), reload)).toBe(
            '서버에 반영하지 못했습니다. 잠시 뒤 다시 시도해주세요.',
        );
        expect(reload).toHaveBeenCalled();
    });

    const client = (outcome: unknown) =>
        ({
            scope: { eventId: E },
            session: { api: { command: vi.fn(async () => outcome) } },
        }) as unknown as Workflow;

    it('최초 배정은 확인된 성공만 ok이고, 결과를 모르면 실패로 단정하지 않는다', async () => {
        expect(await assignBundle(client({ status: 'succeeded', data: {} }), 'b', M1, '9')).toEqual(
            {
                ok: true,
                text: '배정했습니다. 팀원에게 첫 배정 알림이 갑니다.',
            },
        );
        const unknown = await assignBundle(
            client({ status: 'failed', failure: { kind: 'unknown-outcome', intent: 'pending' } }),
            'b',
            M1,
            '9',
        );
        expect(unknown).toEqual({ ok: false, text: UNKNOWN });
        const rejected = await assignBundle(
            client({ status: 'failed', failure: { kind: 'rejected', intent: 'finished' } }),
            'b',
            M1,
            '9',
        );
        expect(rejected.ok).toBe(false);
        expect(rejected.text).toMatch(/^배정하지 못했습니다/);
    });
});

describe('최초 배정 묶음 판정(리뷰 10, 17)', () => {
    const task = (patch: Partial<Task>): Task => ({
        id: 't',
        title: '업무',
        groupId: 'g1',
        zone: '입구',
        start: '10:00',
        end: '11:00',
        assignee: null,
        bundle: 'b1',
        status: 'todo',
        ack: true,
        steps: [],
        checklist: [],
        ...patch,
    });
    const data = (tasks: Task[], server: boolean): EventData => ({
        ...emptyData(),
        tasks,
        ...(server && { bundleRule: 'server' as const }),
    });
    const tasks = [
        task({ id: 't1', bundle: 'b1' }),
        task({ id: 't2', bundle: 'b1', status: 'canceled' }),
        task({ id: 't3', bundle: 'b2' }),
        task({ id: 't4', bundle: 'b2', groupId: 'g2' }),
        task({ id: 't5', bundle: 'b3' }),
    ];

    it('실서버는 취소 업무가 섞이거나 그룹이 섞인 묶음을 고를 수 없다(서버 045와 같다)', () => {
        expect(unassignedBundles(data(tasks, true)).map((b) => b.key)).toEqual(['b3']);
    });
    it('데모 규칙은 그대로다(취소 업무를 빼고 묶음을 본다)', () => {
        expect(unassignedBundles(data(tasks, false)).map((b) => b.key)).toEqual(['b1', 'b2', 'b3']);
    });
    it('시간 겹침은 같은 날짜에서만 본다', () => {
        const m = {
            id: 'm',
            role: 'WORKER',
            isReserve: false,
            available: true,
            groupId: 'g1',
        } as Member;
        const mine = task({ id: 'x', bundle: 'mine', assignee: 'm', date: '2026-11-13' });
        const d = data([mine, task({ id: 'y', bundle: 'b9' })], false);
        const bundle = unassignedBundles(d).find((b) => b.key === 'b9')!;
        expect(canTakeBundle(d, m, bundle)).toBe(true);
        const same = data([{ ...mine, date: undefined }, task({ id: 'y', bundle: 'b9' })], false);
        expect(
            canTakeBundle(
                same,
                m,
                unassignedBundles(same).find((b) => b.key === 'b9')!,
            ),
        ).toBe(false);
    });
});
