import { describe, expect, it } from 'vitest';
import type { Plan, Task } from '../features/plan-workflow/contract';
import type { ChangeSet, Issue, RecoverySimulation } from '../features/response/contract';
import type { LiveAsset, LiveReservation } from '../features/equipment/live-contract';
import {
    lookup,
    signalPath,
    toChange,
    toEquipment,
    toIssues,
    toRecovery,
    toReservations,
} from './convert-ops';
import { editedOperations, kstToUtc } from './handlers-ops';

const E = '10000000-0000-4000-8000-000000000002';
const G = '10000000-0000-4000-8000-000000000004';
const Z = '10000000-0000-4000-8000-000000000005';
const P = '10000000-0000-4000-8000-000000000006';
const T = '10000000-0000-4000-8000-000000000061';
const M1 = '10000000-0000-4000-8000-000000000041';
const M2 = '10000000-0000-4000-8000-000000000042';
const A1 = '10000000-0000-4000-8000-0000000000a1';
const A2 = '10000000-0000-4000-8000-0000000000a2';
const A3 = '10000000-0000-4000-8000-0000000000a3';

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
const task: Task = {
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
    sourceIssueId: 'i-1',
    steps: ['예비 마이크를 가져온다'],
    checklist: [],
    bundleKey: '음향',
} as Task;
const look = lookup(plan, [task]);

const issue = (patch: Partial<Issue> = {}): Issue => ({
    id: 'i-1',
    eventId: E,
    title: '메인 마이크 무음',
    status: 'RECEIVED',
    zoneId: Z,
    workGroupId: G,
    taskId: null,
    relatedIssueId: null,
    reports: [
        {
            id: 'r-1',
            issueId: 'i-1',
            authorMemberId: M2,
            text: '메인 마이크 소리가 안 나요',
            audioId: null,
            kind: 'INITIAL',
            participationRevision: '1',
            createdAt: '2026-11-24T09:05:00Z',
        },
        {
            id: 'r-2',
            issueId: 'i-1',
            authorMemberId: M2,
            text: '예비 마이크로 바꿨어요',
            audioId: 'audio-1',
            kind: 'RESOLUTION',
            participationRevision: '1',
            createdAt: '2026-11-24T09:15:00Z',
        },
    ],
    analysis: {
        analysisValidity: 'CURRENT',
        category: null,
        reportedState: 'FAULT',
        zoneCandidates: [],
    },
    situation: {
        primaryType: 'EQUIPMENT_ANOMALY',
        relatedTypes: ['SCHEDULE_DELAY'],
        confirmed: true,
        confirmedByMemberId: M1,
        confirmedAt: '2026-11-24T09:06:00Z',
        reason: '현장 확인',
        suggestion: null,
    },
    eventRevision: '9',
    createdAt: '2026-11-24T09:05:00Z',
    ...patch,
});

const changeSet = (
    operations: ChangeSet['operations'],
    patch: Partial<ChangeSet> = {},
): ChangeSet => ({
    id: 'c-1',
    eventId: E,
    status: 'READY',
    revision: '2',
    baseRevision: '9',
    mode: 'MANUAL',
    operations,
    rationale: '복구 대안 1: 예비 마이크\n두 번째 줄',
    sourceJobId: null,
    issueId: 'i-1',
    previewHash: 'h',
    diff: [],
    eventRevision: '9',
    simulationRefs: [{ simulationId: 's-1', candidateId: 'c1', inputHash: 'x' }],
    evidenceSnapshot: null,
    ...patch,
});

const asset = (id: string, name: string, state: LiveAsset['technicalState']): LiveAsset => ({
    id,
    eventId: E,
    name,
    model: '무선 마이크',
    technicalState: state,
    stateEvidence: {
        sourceKind: 'MANUAL_CONFIRMATION',
        sourceRef: 'chief',
        observedAt: '2026-11-24T09:04:00Z',
        recordedAt: '2026-11-24T09:04:00Z',
        confirmedByMemberId: M1,
        confirmed: true,
    },
    componentAssetIds: [],
    ports: [],
    verifiedCompatibility: [],
    storageZoneId: Z,
    holderMemberId: null,
    revision: '1',
});

describe('보고 변환(#371)', () => {
    it('접수 시각/보고자/음성 여부/상황/관련 업무를 화면 값으로 바꾼다', () => {
        const [i] = toIssues([issue()], look);
        expect(i).toMatchObject({
            title: '메인 마이크 무음',
            summary: '메인 마이크 소리가 안 나요',
            zone: 'A구역 무대 앞',
            at: '18:05',
            reporter: M2,
            tasks: [T],
            analysis: { category: '분류 보류', state: 'FAULT', items: [] },
            situation: {
                primary: 'EQUIPMENT_ANOMALY',
                related: ['SCHEDULE_DELAY'],
                source: '총괄 확인',
                by: M1,
                at: '18:06',
            },
        });
        expect(i.reports.map((r) => [r.kind, r.at, r.via])).toEqual([
            ['INITIAL', '18:05', '텍스트'],
            ['RESOLUTION', '18:15', '음성'],
        ]);
    });

    it('추천만 있으면 모델 추천, 상황이 없으면 비운다(팀원 응답)', () => {
        const [suggested] = toIssues(
            [
                issue({
                    situation: {
                        ...issue().situation!,
                        confirmed: false,
                        confirmedByMemberId: null,
                        confirmedAt: null,
                    },
                }),
            ],
            look,
        );
        expect(suggested.situation?.source).toBe('모델 추천');
        const [worker] = toIssues([issue({ situation: null, analysis: null })], look);
        expect(worker.situation).toBeUndefined();
        expect(worker.analysis).toBeUndefined();
    });
});

describe('변경안 변환(#371)', () => {
    it('담당 변경은 update, 예약 연산은 reservations/releases로 나누고 연산 번호를 남긴다', () => {
        const { change, opIndex } = toChange(
            changeSet([
                {
                    type: 'RESERVE_EQUIPMENT',
                    payload: {
                        reservation: {
                            assetIds: [A2],
                            startAt: '2026-11-24T09:10:00Z',
                            endAt: '2026-11-24T09:40:00Z',
                        },
                        evidenceRefs: [],
                    },
                },
                { type: 'ASSIGN_TASK', payload: { taskId: T, fromMemberId: M1, toMemberId: M2 } },
                {
                    type: 'CANCEL_EQUIPMENT_RESERVATION',
                    payload: { reservationId: 'res-1', expectedRevision: '1', reason: '대체' },
                },
            ]),
            look,
        );
        expect(change).toMatchObject({
            title: '복구 대안 1: 예비 마이크',
            baseRevision: 9,
            status: 'READY',
            source: '복구 대안',
            reservations: [{ assetId: A2, kind: '예약', start: '18:10', end: '18:40' }],
            releases: ['res-1'],
        });
        expect(change.previewHash).toBeUndefined();
        expect(change.ops).toEqual([
            {
                kind: 'update',
                editable: ['담당'],
                taskId: T,
                before: expect.objectContaining({ assignee: M1, start: '18:00', end: '18:20' }),
                after: expect.objectContaining({ assignee: M2, start: '18:00', end: '18:20' }),
            },
        ]);
        expect(opIndex).toEqual([1]);
    });

    it('업무 추가/시간 변경/취소/프로그램 변경을 화면 연산으로 바꾼다', () => {
        const { change } = toChange(
            changeSet(
                [
                    {
                        type: 'ADD_TASK',
                        payload: {
                            workGroupId: G,
                            bundleKey: '복구',
                            zoneId: Z,
                            title: '예비 마이크 설치',
                            startAt: '2026-11-24T09:10:00Z',
                            endAt: '2026-11-24T09:25:00Z',
                            steps: ['설치'],
                        },
                    },
                    {
                        type: 'UPDATE_TASK',
                        payload: {
                            taskId: T,
                            startAt: '2026-11-24T09:05:00Z',
                            endAt: '2026-11-24T09:25:00Z',
                        },
                    },
                    { type: 'CANCEL_TASK', payload: { taskId: T, reason: '대체' } },
                    {
                        type: 'UPDATE_PROGRAM',
                        payload: {
                            programId: P,
                            startAt: '2026-11-24T09:35:00Z',
                            endAt: '2026-11-24T09:37:00Z',
                        },
                    },
                ],
                { mode: 'AI', simulationRefs: [] },
            ),
            look,
        );
        expect(change.source).toBe('보고 대응');
        expect(change.ops.map((o) => o.kind)).toEqual(['add', 'update', 'cancel', 'program']);
        expect(change.ops[0]).toMatchObject({
            title: '예비 마이크 설치',
            after: { start: '18:10', end: '18:25', zone: 'A구역 무대 앞' },
        });
        expect(change.ops[1]).toMatchObject({
            before: { start: '18:00' },
            after: { start: '18:05', end: '18:25' },
        });
        // 화면은 프로그램을 id로 찾고, 이전 값은 현재 계획 시각이다
        expect(change.ops[3]).toMatchObject({
            program: P,
            before: { start: '18:30', end: '18:32' },
            after: { start: '18:35', end: '18:37' },
        });
    });

    it('편집한 시각/담당을 원래 날짜의 UTC로 서버 연산에 되돌려 쓴다', () => {
        const set = changeSet([
            {
                type: 'RESERVE_EQUIPMENT',
                payload: { reservation: { assetIds: [A2] }, evidenceRefs: [] },
            },
            { type: 'ASSIGN_TASK', payload: { taskId: T, fromMemberId: M1, toMemberId: M2 } },
            {
                type: 'UPDATE_TASK',
                payload: {
                    taskId: T,
                    startAt: '2026-11-24T09:05:00Z',
                    endAt: '2026-11-24T09:25:00Z',
                },
            },
        ]);
        const { change, opIndex } = toChange(set, look);
        const edited = change.ops.map((o) =>
            o.kind === 'update' && o.after.assignee === M2
                ? { ...o, after: { ...o.after, assignee: M1 } }
                : o.kind === 'update'
                  ? { ...o, after: { ...o.after, start: '18:10', end: '18:30' } }
                  : o,
        );
        const ops = editedOperations(set, opIndex, edited, () => task.startAt);
        expect(ops[0]).toEqual(set.operations[0]);
        expect(ops[1].payload.toMemberId).toBe(M1);
        expect(ops[2].payload).toMatchObject({
            startAt: '2026-11-24T09:10:00.000Z',
            endAt: '2026-11-24T09:30:00.000Z',
        });
        // 원래 연산은 바꾸지 않는다
        expect(set.operations[2].payload.startAt).toBe('2026-11-24T09:05:00Z');
        expect(kstToUtc('2026-11-25', '00:30')).toBe('2026-11-24T15:30:00.000Z');
    });
});

describe('장비/복구 변환(#371)', () => {
    const connections = [
        {
            id: 'k1',
            fromAssetId: A1,
            fromPort: 'out',
            toAssetId: A2,
            toPort: 'in',
            confirmedAt: '2026-11-24T08:00:00Z',
            active: true,
        },
        {
            id: 'k2',
            fromAssetId: A2,
            fromPort: 'out',
            toAssetId: A3,
            toPort: 'in',
            confirmedAt: '2026-11-24T08:00:00Z',
            active: true,
        },
        {
            id: 'k3',
            fromAssetId: A3,
            fromPort: 'out',
            toAssetId: A1,
            toPort: 'in',
            confirmedAt: '2026-11-24T08:00:00Z',
            active: true,
        },
    ];

    it('기술 상태/출처/위치와 신호 경로(되돌아오는 연결은 끊음)를 만든다', () => {
        expect(signalPath(A1, connections)).toEqual([A1, A2, A3]);
        expect(
            signalPath(
                A2,
                connections.map((c) => (c.id === 'k2' ? { ...c, active: false } : c)),
            ),
        ).toEqual([A2]);
        const [a] = toEquipment([asset(A1, '메인 마이크', 'UNUSABLE')], connections, look);
        expect(a).toMatchObject({
            tech: 'FAULT',
            kind: '무선 마이크',
            place: 'A구역 무대 앞',
            source: '담당자 확인',
            observedAt: '18:04',
        });
    });

    it('자동 관측이 있는 장비만 auto를 붙이고, 모르는 값은 null 그대로 둔다(#398)', () => {
        const [plain, auto] = toEquipment(
            [
                asset(A1, '스피커', 'VERIFIED_USABLE'),
                {
                    ...asset(A2, '메인 수신기', 'VERIFIED_USABLE'),
                    autoObservation: {
                        kind: 'QLXD4',
                        inputKind: 'WEB',
                        state: 'PROGRAM_OFFLINE',
                        transmitterOn: null,
                        batteryBars: 4,
                        rfLevel: null,
                        audioLevel: null,
                        lastReceivedAt: '2026-11-24T09:04:05Z',
                    },
                },
            ],
            [],
            look,
        );
        expect(plain.auto).toBeUndefined();
        expect(auto.auto).toEqual({
            state: 'PROGRAM_OFFLINE',
            inputKind: 'WEB',
            transmitterOn: null,
            batteryBars: 4,
            rfLevel: null,
            audioLevel: null,
            lastReceivedAt: '18:04:05',
        });
        // 자동 값으로 기술 상태를 바꾸지 않는다
        expect(auto.tech).toBe('OK');
    });

    it('예정/사용 중 예약만, 장비마다 한 줄로 바꾼다', () => {
        const r = (patch: Partial<LiveReservation>): LiveReservation => ({
            id: 'res-1',
            eventId: E,
            assetIds: [A1],
            programId: P,
            taskId: null,
            startAt: '2026-11-24T09:30:00Z',
            endAt: '2026-11-24T09:32:00Z',
            status: 'PLANNED',
            revision: '1',
            ...patch,
        });
        const list = toReservations(
            [
                r({}),
                r({ id: 'res-2', assetIds: [A1, A2], programId: null, taskId: T }),
                r({ id: 'res-3', status: 'CANCELED' }),
            ],
            look,
        );
        expect(list.map((x) => [x.id, x.assetId, x.kind, x.label, x.start])).toEqual([
            ['res-1', A1, '공연', '첫 곡', '18:30'],
            ['res-2#0', A1, '준비', '메인 마이크 교체', '18:30'],
            ['res-2#1', A2, '준비', '메인 마이크 교체', '18:30'],
        ]);
    });

    it('복구 후보의 판정/여유/작업/가정을 대안으로 바꾼다', () => {
        const sim = {
            id: 's-1',
            eventId: E,
            issueId: 'i-1',
            targetAssetIds: [A1],
            createdByMemberId: M1,
            baseRevision: '9',
            inputHash: 'x',
            evidenceRefs: [],
            policyVersion: '1',
            assumptions: [{ key: 'walk', description: '창고까지 3분', confirmed: false }],
            calculatedAt: '2026-11-24T09:06:00Z',
            state: 'READY',
            expiresAt: '2026-12-01T09:06:00Z',
            candidates: [
                {
                    candidateId: 'c1',
                    status: 'FEASIBLE',
                    reasons: ['예비 마이크가 확인됨'],
                    evidenceRefs: [],
                    equipmentIds: [A2],
                    componentIds: [],
                    steps: [
                        {
                            taskId: T,
                            assigneeCandidateId: M2,
                            startAt: '2026-11-24T09:10:00Z',
                            endAt: '2026-11-24T09:18:00Z',
                            timeEvidence: null,
                        },
                    ],
                    occupancy: [
                        {
                            assetIds: [A2],
                            startAt: '2026-11-24T09:10:00Z',
                            endAt: '2026-11-24T09:40:00Z',
                        },
                    ],
                    readyAt: '2026-11-24T09:18:00Z',
                    requiredReadyAt: '2026-11-24T09:30:00Z',
                    slackSec: 720,
                    shortfallSec: null,
                    programImpacts: [{ programId: P, description: '첫 곡 영향 없음' }],
                    conflicts: [],
                    operations: [
                        {
                            type: 'CANCEL_EQUIPMENT_RESERVATION',
                            payload: {
                                reservationId: 'res-1',
                                expectedRevision: '1',
                                reason: '대체',
                            },
                        },
                    ],
                    preview: [],
                },
                {
                    candidateId: 'c2',
                    status: 'UNDETERMINED',
                    reasons: [],
                    evidenceRefs: [],
                    equipmentIds: [],
                    componentIds: [],
                    steps: [],
                    occupancy: [],
                    readyAt: null,
                    requiredReadyAt: '2026-11-24T09:30:00Z',
                    slackSec: null,
                    shortfallSec: null,
                    programImpacts: [],
                    conflicts: ['담당자 미정'],
                    operations: [],
                    preview: [],
                },
            ],
        } as RecoverySimulation;
        const [ok, unknown] = toRecovery(sim, new Map([[A2, '예비 마이크']]), look);
        expect(ok).toMatchObject({
            id: 's-1:c1',
            issueId: 'i-1',
            title: '대안 1: 예비 마이크',
            verdict: 'OK',
            readyAt: '18:18',
            deadline: '18:30',
            margin: '12분 여유',
            work: [{ label: '메인 마이크 교체', min: 8 }],
            staff: M2,
            effects: ['첫 곡 영향 없음'],
            unknowns: ['창고까지 3분'],
            releases: ['res-1'],
            reservations: [{ assetId: A2, start: '18:10', end: '18:40' }],
        });
        expect(unknown).toMatchObject({
            verdict: 'UNKNOWN',
            readyAt: null,
            margin: '판단 불가',
            effects: ['담당자 미정'],
            reason: '계산 근거 없음',
        });
    });
});
