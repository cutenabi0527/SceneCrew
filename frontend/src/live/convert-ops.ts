import type {
    Change,
    ChangeOp,
    EditableField,
    EquipTech,
    EquipmentAsset,
    EquipmentReservation,
    FactSource,
    Issue,
    IssueReport,
    RecoveryOption,
    RecoveryVerdict,
    Situation,
    Slot,
    Task,
} from '../domain/types';
import type {
    ChangeSet,
    Issue as ApiIssue,
    RecoveryCandidate,
    RecoverySimulation,
} from '../features/response/contract';
import type {
    LiveAsset,
    LiveConnection,
    LiveReservation,
    SourceKind,
    TechnicalState,
} from '../features/equipment/live-contract';
import type { Plan, Task as ApiTask } from '../features/plan-workflow/contract';
import { kstClock, kstTime } from './convert';

// 보고·변경안·장비·복구 계산(#371) 응답을 디자인 화면 모델로 바꾸는 순수 함수.
// 서버에 없는 값(전달 기록·다음 행동 등)은 만들지 않고 비워 둔다.

const time = (utc: string | null | undefined) => (utc ? kstTime(utc) : '');
const minutes = (from: string, to: string) =>
    Math.round((Date.parse(to) - Date.parse(from)) / 60000);

type Lookup = {
    zoneName: Map<string, string>;
    programName: Map<string, string>;
    programs: Map<string, { startAt: string; endAt: string }>;
    tasks: Map<string, ApiTask>;
};

export function lookup(plan: Plan | null, tasks: readonly ApiTask[]): Lookup {
    return {
        zoneName: new Map((plan?.zones ?? []).map((z) => [z.id, z.name])),
        programName: new Map((plan?.programs ?? []).map((p) => [p.id, p.name])),
        programs: new Map((plan?.programs ?? []).map((p) => [p.id, p])),
        tasks: new Map(tasks.map((t) => [t.id, t])),
    };
}

/**
 * 연산마다 서버가 받는 칸만 편집기에서 고치게 한다(계약 OpAddTask·OpUpdateTask·OpAssignTask·OpUpdateProgram).
 * 추가 담당은 어느 연산에도 없어 고칠 수 없다.
 */
const EDITABLE: Record<string, EditableField[]> = {
    ADD_TASK: ['시간', '담당', '절차'],
    UPDATE_TASK: ['시간', '절차'],
    ASSIGN_TASK: ['담당'],
    UPDATE_PROGRAM: ['시간'],
};

// 보고 ------------------------------------------------------------------------------

export function toSituation(issue: ApiIssue): Situation | undefined {
    const s = issue.situation;
    if (!s) return undefined;
    return {
        primary: s.primaryType,
        related: s.relatedTypes,
        source: s.confirmed ? '총괄 확인' : '모델 추천',
        by: s.confirmedByMemberId ?? undefined,
        at: s.confirmedAt ? kstTime(s.confirmedAt) : undefined,
    };
}

/** 접수 뒤 이 시간 안이면 결과가 없어도 '분석 중'으로 본다(보고 분석 마감 15초 + 여유). 기기 시계가 서버보다 늦어도 이 폭 안에서만 본다. */
export const ANALYSIS_WINDOW_MS = 20_000;

/** 이 화면이 아는 분석 작업의 상태(총괄이 직접 접수했거나 접수 응답을 받은 작업) */
export type AnalysisJobState = {
    state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
    code?: string;
};

/** 가장 최근 보고 = 작성 시각이 가장 늦은 보고(BE의 이슈 분석 기준, V9-D27) */
function latestReport(i: ApiIssue) {
    return i.reports.reduce<ApiIssue['reports'][number] | undefined>(
        (a, r) => (!a || (r.createdAt ?? '') >= (a.createdAt ?? '') ? r : a),
        undefined,
    );
}

/**
 * 가장 최근 보고의 분석 진행 상태(#358). 결과가 있고 그 작업이 끝났으면 비운다.
 * 아는 작업이 없거나 막 성공했는데 아직 이슈에 결과가 없으면(이슈를 작업보다 먼저 읽음) 보고 시각으로 판단한다:
 * 마감 안이면 분석 중, 지나도 결과가 없으면 결과 없음(실패 또는 미반영).
 */
export function analysisStatus(
    i: ApiIssue,
    now: number,
    job?: AnalysisJobState,
): Issue['analysisStatus'] {
    if (job?.state === 'QUEUED' || job?.state === 'RUNNING') return { kind: 'running' };
    if (job?.state === 'FAILED') return { kind: 'failed', code: job.code };
    if (i.analysis) return undefined;
    const at = latestReport(i)?.createdAt;
    if (
        (!job || job.state === 'SUCCEEDED') &&
        at &&
        Math.abs(now - Date.parse(at)) < ANALYSIS_WINDOW_MS
    )
        return { kind: 'running' };
    return { kind: 'missing' };
}

export function toIssues(
    issues: readonly ApiIssue[],
    look: Lookup,
    now = Date.now(),
    jobs: ReadonlyMap<string, AnalysisJobState> = new Map(),
): Issue[] {
    return issues.map((i) => {
        const first = i.reports.find((r) => r.kind === 'INITIAL') ?? i.reports[0];
        const reports: IssueReport[] = i.reports.map((r) => ({
            kind: r.kind,
            by: r.authorMemberId,
            at: time(r.createdAt),
            via: r.audioId ? '음성' : '텍스트',
            text: r.text,
        }));
        const tasks = new Set<string>();
        if (i.taskId) tasks.add(i.taskId);
        for (const t of look.tasks.values()) if (t.sourceIssueId === i.id) tasks.add(t.id);
        return {
            id: i.id,
            title: i.title,
            summary: first?.text ?? '',
            zone: look.zoneName.get(i.zoneId) ?? '구역 미확인',
            groupId: i.workGroupId,
            status: i.status,
            at: time(i.createdAt ?? first?.createdAt),
            reporter: first?.authorMemberId ?? '',
            tasks: [...tasks],
            reports,
            analysis: i.analysis
                ? {
                      category: i.analysis.category ?? '분류 보류',
                      state: i.analysis.reportedState ?? '판단 보류',
                      items: [],
                      validity: i.analysis.analysisValidity,
                  }
                : undefined,
            // 분석은 총괄만 본다(팀원 응답에는 analysis가 없다). 상태도 총괄 화면에서만 쓴다.
            analysisStatus: analysisStatus(i, now, jobs.get(i.id)),
            latestReportId: latestReport(i)?.id,
            situation: toSituation(i),
        };
    });
}

// 변경안 ----------------------------------------------------------------------------

type Op = ChangeSet['operations'][number];
const str = (v: unknown) => (typeof v === 'string' ? v : undefined);

function slotOf(t: ApiTask | undefined, look: Lookup): Slot {
    if (!t) return { start: '', end: '' };
    return {
        start: kstTime(t.startAt),
        end: kstTime(t.endAt),
        assignee: t.assigneeId,
        zone: look.zoneName.get(t.zoneId),
        groupId: t.workGroupId,
        steps: t.steps,
    };
}

/** 서버 연산 하나를 화면 연산으로. 예약 연산·그룹/안내 연산은 null(따로 다룬다). */
export function toChangeOp(op: Op, index: number, look: Lookup): ChangeOp | null {
    const p = op.payload;
    const task = look.tasks.get(str(p.taskId) ?? '');
    const before = slotOf(task, look);
    switch (op.type) {
        case 'ADD_TASK':
            return {
                kind: 'add',
                editable: EDITABLE.ADD_TASK,
                tempId: `op-${index}`,
                groupId: str(p.workGroupId) ?? '',
                title: str(p.title) ?? '새 업무',
                steps: Array.isArray(p.steps) ? (p.steps as string[]) : [],
                after: {
                    start: time(str(p.startAt)),
                    end: time(str(p.endAt)),
                    assignee: str(p.assigneeId) ?? null,
                    zone: look.zoneName.get(str(p.zoneId) ?? ''),
                    groupId: str(p.workGroupId),
                },
            };
        case 'UPDATE_TASK':
            return {
                kind: 'update',
                editable: EDITABLE.UPDATE_TASK,
                taskId: str(p.taskId) ?? '',
                before,
                after: {
                    ...before,
                    start: p.startAt ? time(str(p.startAt)) : before.start,
                    end: p.endAt ? time(str(p.endAt)) : before.end,
                    zone: p.zoneId ? look.zoneName.get(str(p.zoneId) ?? '') : before.zone,
                    steps: Array.isArray(p.steps) ? (p.steps as string[]) : before.steps,
                },
            };
        case 'ASSIGN_TASK':
            return {
                kind: 'update',
                editable: EDITABLE.ASSIGN_TASK,
                taskId: str(p.taskId) ?? '',
                before: { ...before, assignee: (p.fromMemberId as string | null) ?? null },
                after: { ...before, assignee: (p.toMemberId as string | null) ?? null },
            };
        case 'CANCEL_TASK':
            return { kind: 'cancel', taskId: str(p.taskId) ?? '', before };
        case 'UPDATE_PROGRAM': {
            // 화면은 프로그램을 id로 찾는다(diffRows). 이전 값은 현재 계획의 시각이고, 시각을 바꾸지 않는
            // 연산(이름·구역만)은 현재 시각을 그대로 둔다.
            const id = str(p.programId) ?? '';
            const current = look.programs.get(id);
            const was = { start: time(current?.startAt), end: time(current?.endAt) };
            return {
                kind: 'program',
                editable: EDITABLE.UPDATE_PROGRAM,
                program: id,
                before: was,
                after: {
                    start: p.startAt ? time(str(p.startAt)) : was.start,
                    end: p.endAt ? time(str(p.endAt)) : was.end,
                },
            };
        }
        default:
            return null;
    }
}

/** 변경안의 화면 연산과, 그 연산이 서버 연산 몇 번째에서 왔는지(편집에서 되돌려 쓴다). */
/** by: 변경안 응답에 작성자가 없어 현재 총괄 참여자로 둔다(총괄만 변경안을 만든다). */
export function toChange(
    set: ChangeSet,
    look: Lookup,
    by = '',
): { change: Change; opIndex: number[] } {
    const ops: ChangeOp[] = [];
    const opIndex: number[] = [];
    const reservations: NonNullable<Change['reservations']> = [];
    const releases: string[] = [];
    set.operations.forEach((op, i) => {
        if (op.type === 'RESERVE_EQUIPMENT' || op.type === 'UPDATE_EQUIPMENT_RESERVATION') {
            const r = (op.payload.reservation ?? op.payload.after) as
                { assetIds?: string[]; startAt?: string; endAt?: string } | undefined;
            for (const assetId of r?.assetIds ?? [])
                reservations.push({
                    assetId,
                    kind: '예약',
                    start: time(r?.startAt),
                    end: time(r?.endAt),
                    label: '복구 예약',
                });
            if (op.type === 'UPDATE_EQUIPMENT_RESERVATION')
                releases.push(str(op.payload.reservationId) ?? '');
            return;
        }
        if (op.type === 'CANCEL_EQUIPMENT_RESERVATION') {
            releases.push(str(op.payload.reservationId) ?? '');
            return;
        }
        const converted = toChangeOp(op, i, look);
        if (converted) {
            ops.push(converted);
            opIndex.push(i);
        }
    });
    const title = set.rationale.split('\n')[0].slice(0, 60) || '변경안';
    return {
        change: {
            id: set.id,
            issueId: set.issueId,
            by,
            at: '',
            status: set.status,
            mode: set.mode,
            title,
            baseRevision: Number(set.baseRevision),
            ops,
            reservations: reservations.length ? reservations : undefined,
            releases: releases.length ? releases : undefined,
            // 서버 변경안에는 '확인 필요 사항' 칸이 없어 고쳐도 보낼 곳이 없다
            fixedChecks: true,
            source: set.simulationRefs?.length
                ? '복구 대안'
                : set.mode === 'AI'
                  ? '보고 대응'
                  : undefined,
            // AI 대응안의 참고 기록(V9-D55, #389). 계약 모양이 아니면 파서가 이미 뺐다.
            ...(set.alternatives || set.coordination
                ? {
                      agentRecords: {
                          alternatives: set.alternatives,
                          coordination: set.coordination,
                      },
                  }
                : {}),
        },
        opIndex,
    };
}

// 장비 ------------------------------------------------------------------------------

const TECH: Record<TechnicalState, EquipTech> = {
    VERIFIED_USABLE: 'OK',
    UNUSABLE: 'FAULT',
    CHECK_REQUIRED: 'CHECK',
    UNKNOWN: 'UNKNOWN',
};
const SOURCE: Record<SourceKind, FactSource> = {
    MANUAL_CONFIRMATION: '담당자 확인',
    REHEARSAL_MEASUREMENT: '자동 관측',
    AUTOMATIC_MEASUREMENT: '자동 관측',
    SIMULATION_ASSUMPTION: '미확인',
};

/** 이 장비에서 활성 연결을 따라 끝까지 간 신호 경로(되돌아오는 연결은 끊는다). */
export function signalPath(assetId: string, connections: readonly LiveConnection[]): string[] {
    const path = [assetId];
    const seen = new Set(path);
    let current = assetId;
    for (;;) {
        const next = connections.find((c) => c.active && c.fromAssetId === current);
        if (!next || seen.has(next.toAssetId)) return path;
        path.push(next.toAssetId);
        seen.add(next.toAssetId);
        current = next.toAssetId;
    }
}

export function toEquipment(
    assets: readonly LiveAsset[],
    connections: readonly LiveConnection[],
    look: Lookup,
): EquipmentAsset[] {
    return assets.map((a) => ({
        id: a.id,
        name: a.name,
        kind: a.model ?? '장비',
        tech: TECH[a.technicalState],
        place: (a.storageZoneId && look.zoneName.get(a.storageZoneId)) || '미확인',
        source: a.stateEvidence.confirmed ? SOURCE[a.stateEvidence.sourceKind] : '미확인',
        observedAt: a.stateEvidence.observedAt ? kstTime(a.stateEvidence.observedAt) : undefined,
        path: signalPath(a.id, connections),
        ...(a.autoObservation
            ? {
                  auto: {
                      state: a.autoObservation.state,
                      inputKind: a.autoObservation.inputKind,
                      transmitterOn: a.autoObservation.transmitterOn,
                      batteryBars: a.autoObservation.batteryBars,
                      rfLevel: a.autoObservation.rfLevel,
                      audioLevel: a.autoObservation.audioLevel,
                      lastReceivedAt: a.autoObservation.lastReceivedAt
                          ? kstClock(a.autoObservation.lastReceivedAt)
                          : null,
                  },
              }
            : {}),
    }));
}

/**
 * 서버 예약 id 목록을 화면 예약 id로 펼친다. 여러 장비를 묶은 예약은 화면에서 `예약id#n` 여러 줄이라
 * 변경안·복구 대안의 해제 목록도 그 줄들로 바꿔야 화면이 찾는다. 화면에 없는 id(이미 없어진 예약)는 그대로 둔다.
 */
export function expandReleases(
    ids: readonly string[] | undefined,
    screen: readonly EquipmentReservation[],
): string[] | undefined {
    if (!ids) return undefined;
    return ids.flatMap((id) => {
        const rows = screen.filter((r) => r.id === id || r.id.startsWith(`${id}#`));
        return rows.length ? rows.map((r) => r.id) : [id];
    });
}

/** 장비 하나당 한 줄. 여러 장비를 묶은 예약은 `예약id#n`으로 나눈다(해제는 앞부분 id로 찾는다). */
export function toReservations(
    reservations: readonly LiveReservation[],
    look: Lookup,
): EquipmentReservation[] {
    return reservations
        .filter((r) => r.status === 'PLANNED' || r.status === 'ACTIVE')
        .flatMap((r) =>
            r.assetIds.map((assetId, n) => {
                const task = r.taskId ? look.tasks.get(r.taskId) : undefined;
                const program = r.programId ? look.programName.get(r.programId) : undefined;
                return {
                    id: r.assetIds.length > 1 ? `${r.id}#${n}` : r.id,
                    assetId,
                    kind: r.programId
                        ? ('공연' as const)
                        : r.taskId
                          ? ('준비' as const)
                          : ('예약' as const),
                    start: kstTime(r.startAt),
                    end: kstTime(r.endAt),
                    label: program ?? task?.title ?? '예약',
                    program: r.programId ?? undefined,
                };
            }),
        );
}

// 복구 계산 -------------------------------------------------------------------------

const VERDICT: Record<RecoveryCandidate['status'], RecoveryVerdict> = {
    FEASIBLE: 'OK',
    CONDITIONAL: 'CONDITIONAL',
    INFEASIBLE: 'NO',
    UNDETERMINED: 'UNKNOWN',
};

function margin(c: RecoveryCandidate) {
    if (c.slackSec != null) return `${Math.round(c.slackSec / 60)}분 여유`;
    if (c.shortfallSec != null) return `${Math.round(c.shortfallSec / 60)}분 부족`;
    return '판단 불가';
}

/** 화면의 대안 id: 계산id:후보id. 변경안을 만들 때 다시 나눈다. */
export const optionId = (simulationId: string, candidateId: string) =>
    `${simulationId}:${candidateId}`;

export function toRecovery(
    sim: RecoverySimulation,
    names: ReadonlyMap<string, string>,
    look: Lookup,
): RecoveryOption[] {
    const unknowns = sim.assumptions.map((a) => a.description);
    return sim.candidates.map((c, n) => {
        const add = c.operations.find((o) => o.type === 'ADD_TASK')?.payload;
        const assets = c.equipmentIds.map((id) => names.get(id) ?? '장비');
        const releases = c.operations
            .filter(
                (o) =>
                    o.type === 'CANCEL_EQUIPMENT_RESERVATION' ||
                    o.type === 'UPDATE_EQUIPMENT_RESERVATION',
            )
            .map((o) => str(o.payload.reservationId) ?? '');
        return {
            id: optionId(sim.id, c.candidateId),
            issueId: sim.issueId ?? '',
            title: `대안 ${n + 1}${assets.length ? `: ${assets.join(' / ')}` : ''}`,
            verdict: VERDICT[c.status],
            readyAt: c.readyAt ? kstTime(c.readyAt) : null,
            deadline: kstTime(c.requiredReadyAt),
            margin: margin(c),
            assets: c.equipmentIds,
            work: c.steps.map((s) => ({
                label: (s.taskId && look.tasks.get(s.taskId)?.title) || '작업',
                min: s.startAt && s.endAt ? minutes(s.startAt, s.endAt) : null,
            })),
            staff: c.steps.find((s) => s.assigneeCandidateId)?.assigneeCandidateId ?? null,
            effects: [...c.programImpacts.map((p) => p.description), ...c.conflicts],
            reason: c.reasons.join(' / ') || '계산 근거 없음',
            unknowns,
            reservations: c.occupancy.flatMap((o) =>
                o.assetIds.map((assetId) => ({
                    assetId,
                    kind: '예약' as const,
                    start: kstTime(o.startAt),
                    end: kstTime(o.endAt),
                    label: '복구 예약',
                })),
            ),
            releases: releases.length ? releases : undefined,
            task: {
                title: str(add?.title) ?? '복구 작업',
                zone: look.zoneName.get(str(add?.zoneId) ?? '') ?? '',
                start: time(str(add?.startAt)),
                end: time(str(add?.endAt)),
                steps: Array.isArray(add?.steps) ? (add.steps as string[]) : [],
                assets: c.equipmentIds,
                pickup: '',
                dest: '',
                reason: c.reasons[0] ?? '',
            },
        };
    });
}

/** 업무 id → 화면 업무(편집 되돌리기 등에서 쓴다) */
export type TaskIndex = Map<string, Task>;
