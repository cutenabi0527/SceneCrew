import { ContractViolationError } from '../../shared/api/contract';
import { checkV9Shape, parseV9 } from '../../shared/api/v9';

// 상황 보고·대응·변경 승인·명시 확인(#287)의 응답 검사.
// 스키마는 v9 계약 묶음(shared/api/v9, frontend/docs/tools/v9_bundle.py가 만든다)을 쓴다.
// 065~071은 처음부터 V9ChangeSetView(연산 11종)로 읽는다. 기존 ChangeSetView 소비자는 없다.

export const parse = parseV9;
// 이슈 응답(IssueView 계열)은 계약에 없는 필드를 받는다(#386, V9-D51). 서버가 필드를 더해도 이슈 화면이
// 깨지지 않게 한다. 필수·형식·enum·상한과 아래의 공연·이슈 일치 검사는 그대로다.
// 필드 삭제나 필수 변경처럼 이전 앱이 견딜 수 없는 변경은 배포 기록의 최소 앱 버전을 올린다.
const parseIssueShape = checkV9Shape;
// AI 대응안 결과(063 GENERATE_CHANGE)와 변경안 조회도 계약에 없는 필드를 받는다(#389, V9-D55).
// BE가 결과6의 coordination·alternatives를 모양 확정 전에 먼저 실어도 대응안·변경안 화면이 깨지지 않게 한다.
const parseChangeShape = checkV9Shape;

// AI 처리 기록·검토한 다른 방법(V9-D55). 판단 근거나 승인 조건이 아닌 참고 기록이라, 모양이 계약과 다르면
// 응답 전체를 거절하지 않고 그 칸만 버린다(화면에 보이지 않는다). 나머지 필드 검사는 그대로다.
export type ChangeAgentRole = 'SUPERVISOR' | 'SCHEDULE' | 'STAFFING' | 'GUIDE' | 'REVIEW';

export interface ChangeAlternative {
    summary: string;
}

export interface ChangeCoordination {
    graph: {
        taskId: string;
        parentTaskId: string | null;
        role: ChangeAgentRole;
        status: 'SUCCEEDED' | 'NEEDS_INFO' | 'FAILED';
    }[];
    redelegations: { taskId: string; reason: string }[];
    resolvedConflicts: { refs: string[]; resolution: string }[];
    contributions: { role: ChangeAgentRole; summary: string }[];
}

interface AgentRecords {
    alternatives?: ChangeAlternative[];
    coordination?: ChangeCoordination;
}

const valid = (check: () => unknown) => {
    try {
        check();
        return true;
    } catch (error) {
        if (error instanceof ContractViolationError) return false;
        throw error;
    }
};

/** 참고 기록 두 칸 중 계약 모양이 아닌 칸을 뺀 얕은 사본. 객체가 아니면 그대로 돌려 원래 검사가 거절하게 한다. */
function keepAgentRecords(value: unknown): unknown {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
    const copy = { ...(value as Record<string, unknown>) };
    if (Object.hasOwn(copy, 'alternatives')) {
        const list = copy.alternatives;
        const ok =
            Array.isArray(list) &&
            list.length <= 3 &&
            list.every((a) => valid(() => parse('V9ChangeAlternative', a)));
        if (!ok) delete copy.alternatives;
    }
    if (
        Object.hasOwn(copy, 'coordination') &&
        !valid(() => parse('V9ChangeCoordination', copy.coordination))
    )
        delete copy.coordination;
    return copy;
}

export type IssueStatus = 'RECEIVED' | 'RESPONDING' | 'RESOLUTION_REPORTED' | 'CLOSED';
export type ReportKind = 'INITIAL' | 'FOLLOWUP' | 'RESOLUTION';

export interface Report {
    id: string;
    issueId: string;
    authorMemberId: string;
    text: string;
    audioId: string | null;
    kind: ReportKind;
    // 서버가 작성 당시 고정한 작성자 참여 회차. 현재 회차로 바꾸지 않는다.
    participationRevision: string;
    // #371 서버 저장 시각(UTC). 옛 응답에는 없을 수 있다.
    createdAt?: string;
}

export interface Analysis {
    analysisValidity: 'CURRENT' | 'PARTIAL_STALE' | 'STALE';
    category: string | null;
    reportedState: string | null;
    zoneCandidates: {
        zoneId: string;
        source: 'ZONE' | 'PROGRAM' | 'ROLE';
        sourceRef: string;
        contextRevision: string;
    }[];
}

/** 운영 상황 6종(V9-D03). 미분류는 별도 코드가 아니라 null이다(V9-D48). */
export type SituationType =
    | 'SCHEDULE_DELAY'
    | 'STAFF_GAP'
    | 'TASK_CONFLICT'
    | 'EQUIPMENT_ANOMALY'
    | 'PERFORMANCE_ORDER_CHANGE'
    | 'AUDIENCE_SAFETY';

/** 이슈의 운영 상황(V9-D48, 총괄 전용). 총괄 정정값이 우선하고, 아니면 최신 보고의 AI 추천이다. */
export interface IssueSituation {
    primaryType: SituationType | null;
    relatedTypes: SituationType[];
    confirmed: boolean;
    confirmedByMemberId: string | null;
    confirmedAt: string | null;
    reason: string | null;
    suggestion: { primaryType: SituationType | null; relatedTypes: SituationType[] } | null;
}

/** EXT19 응답 영수증. 상세는 이슈를 다시 읽는다. */
export interface SituationReceipt {
    id: string;
    revision: string;
    eventRevision: string;
}

export function parseSituationReceipt(data: unknown): SituationReceipt {
    return parse<SituationReceipt>('FinalReceipt', data);
}

export interface Issue {
    id: string;
    eventId: string;
    title: string;
    status: IssueStatus;
    zoneId: string;
    workGroupId: string;
    taskId: string | null;
    relatedIssueId: string | null;
    // CHIEF는 허용된 원문 전체, WORKER는 본인 원문만 받는다.
    reports: Report[];
    // WORKER에게는 항상 null이다.
    analysis: Analysis | null;
    // 운영 상황(#359). WORKER에게는 null이고, 옛 응답에는 없을 수 있다.
    situation?: IssueSituation | null;
    eventRevision: string;
    // #371 이슈(최초 보고) 저장 시각(UTC). 옛 응답에는 없을 수 있다.
    createdAt?: string;
}

export interface Page<T> {
    items: T[];
    nextCursor: string | null;
}

export interface IssueCreated {
    issue: Issue;
    report: Report;
    analysisJobId: string | null;
}

export interface ReportCreated {
    report: Report;
    analysisJobId: string | null;
    eventRevision: string;
}

export type OperationType =
    | 'ADD_TASK'
    | 'UPDATE_TASK'
    | 'ASSIGN_TASK'
    | 'CANCEL_TASK'
    | 'UPDATE_PROGRAM'
    | 'UPSERT_WORK_GROUP'
    | 'DEACTIVATE_WORK_GROUP'
    | 'UPDATE_INSTRUCTIONS'
    | 'RESERVE_EQUIPMENT'
    | 'UPDATE_EQUIPMENT_RESERVATION'
    | 'CANCEL_EQUIPMENT_RESERVATION';

export interface Reservation {
    id: string;
    assetIds: string[];
    startAt: string;
    endAt: string;
    status: string;
    [key: string]: unknown;
}

export interface EvidenceRef {
    kind: string;
    id: string;
    revision: string | null;
    contentHash: string | null;
    observedAt: string | null;
    preconditions: string[];
}

export interface DiffItem {
    operationIndex: number;
    type: OperationType;
    targetId: string | null;
    // 서버가 계산한 변경 요약. 화면은 이 값을 기본으로 보여 주고 클라이언트가 결과를 만들지 않는다.
    summary: string;
    before?: Reservation | null;
    after?: Reservation | null;
    evidenceRefs?: EvidenceRef[];
}

export type ChangeSetStatus = 'DRAFT' | 'READY' | 'APPLIED' | 'DISCARDED';

export interface ChangeSet extends AgentRecords {
    id: string;
    eventId: string;
    status: ChangeSetStatus;
    revision: string;
    baseRevision: string;
    mode: 'MANUAL' | 'AI';
    operations: { type: OperationType; payload: Record<string, unknown> }[];
    rationale: string;
    sourceJobId: string | null;
    issueId: string | null;
    previewHash: string | null;
    diff: DiffItem[];
    eventRevision: string;
    evidenceRefs?: EvidenceRef[];
    simulationRefs?: { simulationId: string; candidateId: string; inputHash: string }[];
    // 이 변경안 revision에 연결된 근거(R2). 과거 근거가 없던 변경안은 null이다.
    evidenceSnapshot: EvidenceSnapshot | null;
}

/** 변경안 revision별 근거 기록(R2). 서버가 불변으로 보관하며 원문 대신 확인 요약을 담는다. */
export interface EvidenceSnapshot {
    changeSetId: string;
    changeSetRevision: string;
    capturedAt: string;
    evidence: {
        kind: string;
        id: string;
        revision: string | null;
        contentHash: string | null;
        summary: string;
        confirmedAt: string | null;
        preconditions: string[];
    }[];
    selections: {
        simulationId: string;
        candidateId: string;
        inputHash: string;
        summary: string;
        reason: string;
    }[];
}

export type AckChangeKind =
    'ASSIGNEE' | 'TIME' | 'ZONE' | 'ADDED' | 'CANCELED' | 'STEPS' | 'CHECKLIST';

export interface Acknowledgment {
    id: string;
    participationRevision: string;
    state: 'PENDING' | 'ACKNOWLEDGED' | 'NO_CURRENT_TARGETS';
    targetsHash: string;
    targets: {
        taskId: string;
        title: string;
        changeKinds: AckChangeKind[];
        removed: boolean;
        changedAt: string;
    }[];
    acknowledgedAt: string | null;
}

type JobError = { code: string; retryable: boolean };
type JobBase<K, R> = { jobId: string; kind: K; attempt: string } & (
    | { state: 'QUEUED' | 'RUNNING'; result: null; error: null }
    | { state: 'SUCCEEDED'; result: R; error: null }
    | { state: 'FAILED'; result: null; error: JobError }
);

export interface ChangeGenerationResult extends AgentRecords {
    // 변경안이 만들어졌으면 그 ID. 질문만 있으면 null이다.
    changeSetId: string | null;
    operations: ChangeSet['operations'];
    rationale: string;
    questions: { key: string; field: string; question: string }[];
    evidenceRefs: EvidenceRef[];
    simulationRefs: NonNullable<ChangeSet['simulationRefs']>;
    situation: unknown;
}

export type AnalysisJob = JobBase<'ANALYZE_REPORT', Analysis>;
export type ChangeJob = JobBase<'GENERATE_CHANGE', ChangeGenerationResult>;

const mismatch = (path: string, reason: string): never => {
    throw new ContractViolationError(path, reason);
};

/** 현재 공연의 이슈인가 */
export function parseIssue(data: unknown, eventId: string, issueId?: string): Issue {
    const issue = parseIssueShape<Issue>('IssueView', data);
    if (issue.eventId !== eventId) mismatch('issue.eventId', '현재 공연이어야 합니다.');
    if (issueId && issue.id !== issueId) mismatch('issue.id', '요청한 이슈여야 합니다.');
    for (const r of issue.reports)
        if (r.issueId !== issue.id) mismatch('issue.reports', '같은 이슈의 보고여야 합니다.');
    return issue;
}

export function parseIssuePage(data: unknown, eventId: string): Page<Issue> {
    const page = parseIssueShape<Page<Issue>>('IssuePage', data);
    page.items.forEach((issue) => parseIssue(issue, eventId));
    return page;
}

export function parseIssueCreated(data: unknown, eventId: string): IssueCreated {
    const created = parseIssueShape<IssueCreated>('IssueCreated', data);
    parseIssue(created.issue, eventId);
    if (created.report.issueId !== created.issue.id || created.report.kind !== 'INITIAL')
        mismatch('report', '새 이슈의 최초 보고여야 합니다.');
    return created;
}

export function parseReportCreated(data: unknown, issueId: string): ReportCreated {
    const created = parse<ReportCreated>('ReportCreated', data);
    if (created.report.issueId !== issueId) mismatch('report.issueId', '요청한 이슈여야 합니다.');
    return created;
}

export function parseChangeSet(data: unknown, eventId: string, changeSetId?: string): ChangeSet {
    const set = parseChangeShape<ChangeSet>('V9ChangeSetView', keepAgentRecords(data));
    // 모르는 필드는 받지만, 이전 단수 simulationRef는 배열(simulationRefs)로 바뀐 옛 모양이라 계속 거절한다
    if (Object.hasOwn(set, 'simulationRef'))
        mismatch('changeSet.simulationRef', '계산 참조는 simulationRefs 배열이어야 합니다.');
    if (set.eventId !== eventId) mismatch('changeSet.eventId', '현재 공연이어야 합니다.');
    if (changeSetId && set.id !== changeSetId)
        mismatch('changeSet.id', '요청한 변경안이어야 합니다.');
    // 적용에는 미리보기 해시가 필요하다. 편집 뒤의 DRAFT는 해시가 없다(previewHash=null·diff=[]).
    if (set.status === 'READY' && set.previewHash === null)
        mismatch('changeSet.previewHash', '미리보기를 마친 변경안은 해시가 있어야 합니다.');
    if (set.status === 'DRAFT' && set.previewHash !== null)
        mismatch('changeSet.previewHash', '검토 전 변경안은 해시가 없어야 합니다.');
    for (const item of set.diff)
        if (item.operationIndex >= set.operations.length)
            mismatch('changeSet.diff', '연산 순서 안의 항목이어야 합니다.');
    const snap = set.evidenceSnapshot;
    if (snap) {
        if (snap.changeSetId !== set.id)
            mismatch('changeSet.evidenceSnapshot', '같은 변경안의 근거여야 합니다.');
        if (BigInt(snap.changeSetRevision) > BigInt(set.revision))
            mismatch('changeSet.evidenceSnapshot', '현재보다 나중 revision의 근거일 수 없습니다.');
    }
    return set;
}

/** R2 과거 revision 근거. 요청한 변경안·revision의 기록이어야 한다. */
export function parseEvidenceSnapshot(
    data: unknown,
    changeSetId: string,
    revision: string,
): EvidenceSnapshot {
    const snap = parse<EvidenceSnapshot>('FinalEvidenceSnapshot', data);
    if (snap.changeSetId !== changeSetId)
        mismatch('evidence.changeSetId', '요청한 변경안이어야 합니다.');
    if (snap.changeSetRevision !== revision)
        mismatch('evidence.changeSetRevision', '요청한 revision이어야 합니다.');
    return snap;
}

export function parseChangeSetPage(data: unknown, eventId: string): Page<ChangeSet> {
    const raw = data as { items?: unknown } | null;
    const cleaned =
        raw && typeof raw === 'object' && Array.isArray(raw.items)
            ? { ...raw, items: raw.items.map(keepAgentRecords) }
            : data;
    const page = parseChangeShape<Page<ChangeSet>>('V9ChangeSetPage', cleaned);
    page.items.forEach((set) => parseChangeSet(set, eventId));
    return page;
}

export function parseAckPage(data: unknown): Page<Acknowledgment> {
    return parse<Page<Acknowledgment>>('AcknowledgmentPage', data);
}

export function parseAck(data: unknown, ackId: string): Acknowledgment {
    const ack = parse<Acknowledgment>('AcknowledgmentView', data);
    if (ack.id !== ackId) mismatch('acknowledgment.id', '요청한 확인 대상이어야 합니다.');
    return ack;
}

/** 063 Job의 결과(result)에서 모양이 틀린 참고 기록만 뺀다. */
function withCleanResult(data: unknown): unknown {
    if (!data || typeof data !== 'object' || Array.isArray(data)) return data;
    const job = data as Record<string, unknown>;
    return Object.hasOwn(job, 'result') ? { ...job, result: keepAgentRecords(job.result) } : data;
}

function parseJob<K extends 'ANALYZE_REPORT' | 'GENERATE_CHANGE' | 'SIMULATE_RECOVERY'>(
    data: unknown,
    jobId: string,
    kind: K,
) {
    const job =
        kind === 'GENERATE_CHANGE'
            ? parseChangeShape<ChangeJob>('V9JobRead', withCleanResult(data))
            : parse<AnalysisJob | RecoveryJob>('V9JobRead', data);
    if (job.jobId.toLowerCase() !== jobId.toLowerCase())
        mismatch('job.jobId', '요청한 Job이어야 합니다.');
    if (job.kind !== kind) mismatch('job.kind', `${kind} Job이어야 합니다.`);
    return job;
}

export const parseAnalysisJob = (data: unknown, jobId: string) =>
    parseJob(data, jobId, 'ANALYZE_REPORT') as AnalysisJob;
export const parseChangeJob = (data: unknown, jobId: string) =>
    parseJob(data, jobId, 'GENERATE_CHANGE') as ChangeJob;

// 복구 계산(#308) -------------------------------------------------------------
// EXT06 접수 → Job(SIMULATE_RECOVERY) → 결과 ID → EXT07 조회. 다른 기기는 Q1 최근 결과로 같은 ID를 찾는다.
// 계산은 운영 상태를 바꾸지 않는다. 고른 후보는 066 변경안(simulationRefs)으로 만들어 총괄이 승인한다.

export type CandidateStatus = 'FEASIBLE' | 'CONDITIONAL' | 'INFEASIBLE' | 'UNDETERMINED';

export interface RecoveryCandidate {
    candidateId: string;
    status: CandidateStatus;
    reasons: string[];
    evidenceRefs: EvidenceRef[];
    equipmentIds: string[];
    componentIds: string[];
    steps: {
        taskId: string | null;
        assigneeCandidateId: string | null;
        startAt: string | null;
        endAt: string | null;
        timeEvidence: unknown;
    }[];
    occupancy: { assetIds: string[]; startAt: string; endAt: string }[];
    readyAt: string | null;
    requiredReadyAt: string;
    slackSec: number | null;
    shortfallSec: number | null;
    programImpacts: { programId: string; description: string }[];
    conflicts: string[];
    operations: ChangeSet['operations'];
    // 서버가 계산한 변경 미리보기. 화면은 이 요약을 그대로 보인다.
    preview: DiffItem[];
}

export interface RecoverySimulation {
    id: string;
    eventId: string;
    issueId: string | null;
    targetAssetIds: string[];
    createdByMemberId: string;
    baseRevision: string;
    inputHash: string;
    evidenceRefs: EvidenceRef[];
    policyVersion: string;
    // 확인되지 않은 가정. confirmed는 항상 false다.
    assumptions: { key: string; description: string; confirmed: false }[];
    calculatedAt: string;
    state: 'READY' | 'NEEDS_INFO' | 'FAILED' | 'STALE';
    candidates: RecoveryCandidate[];
    expiresAt: string;
}

export interface RecoveryPointer {
    simulationId: string;
    calculatedAt: string;
    expiresAt: string;
    expired: boolean;
}

export type RecoveryJob = JobBase<
    'SIMULATE_RECOVERY',
    { simulationId: string; calculatedAt: string; expiresAt: string }
>;

export const parseRecoveryJob = (data: unknown, jobId: string) =>
    parseJob(data, jobId, 'SIMULATE_RECOVERY') as RecoveryJob;

export function parseSimulation(
    data: unknown,
    eventId: string,
    simulationId: string,
): RecoverySimulation {
    const sim = parse<RecoverySimulation>('V9RecoverySimulation', data);
    if (sim.eventId !== eventId) mismatch('simulation.eventId', '현재 공연이어야 합니다.');
    if (sim.id !== simulationId) mismatch('simulation.id', '요청한 계산이어야 합니다.');
    const ids = new Set<string>();
    for (const c of sim.candidates) {
        if (ids.has(c.candidateId))
            mismatch('simulation.candidates', '후보 ID가 겹치지 않아야 합니다.');
        ids.add(c.candidateId);
        if (c.preview.some((d) => d.operationIndex >= c.operations.length))
            mismatch('simulation.candidates.preview', '연산 순서 안의 항목이어야 합니다.');
    }
    return sim;
}

export const parsePointer = (data: unknown) => parse<RecoveryPointer>('FinalRecoveryPointer', data);

export const CANDIDATE_LABEL: Record<CandidateStatus, string> = {
    FEASIBLE: '가능',
    CONDITIONAL: '조건부 가능',
    INFEASIBLE: '불가',
    UNDETERMINED: '판단 불가',
};

export const SIMULATION_STATE_LABEL: Record<RecoverySimulation['state'], string> = {
    READY: '계산 완료',
    NEEDS_INFO: '정보 부족',
    FAILED: '계산 실패',
    STALE: '현재 상태와 다름',
};

export const ISSUE_STATUS_LABEL: Record<IssueStatus, string> = {
    RECEIVED: '접수',
    RESPONDING: '대응 중',
    RESOLUTION_REPORTED: '해결 보고됨',
    CLOSED: '종료',
};

export const REPORT_KIND_LABEL: Record<ReportKind, string> = {
    INITIAL: '최초 보고',
    FOLLOWUP: '추가 보고',
    RESOLUTION: '해결 보고',
};

export const CHANGE_STATUS_LABEL: Record<ChangeSetStatus, string> = {
    DRAFT: '검토 전',
    READY: '미리보기 완료',
    APPLIED: '적용됨',
    DISCARDED: '폐기됨',
};

export const ACK_KIND_LABEL: Record<AckChangeKind, string> = {
    ASSIGNEE: '담당자',
    TIME: '시간',
    ZONE: '구역',
    ADDED: '새 업무',
    CANCELED: '취소',
    STEPS: '수행 단계',
    CHECKLIST: '체크리스트',
};

export const OPERATION_LABEL: Record<OperationType, string> = {
    ADD_TASK: '업무 추가',
    UPDATE_TASK: '업무 수정',
    ASSIGN_TASK: '담당 변경',
    CANCEL_TASK: '업무 취소',
    UPDATE_PROGRAM: '프로그램 수정',
    UPSERT_WORK_GROUP: '그룹 추가/수정',
    DEACTIVATE_WORK_GROUP: '그룹 비활성',
    UPDATE_INSTRUCTIONS: '안내 수정',
    RESERVE_EQUIPMENT: '장비 예약',
    UPDATE_EQUIPMENT_RESERVATION: '장비 예약 변경',
    CANCEL_EQUIPMENT_RESERVATION: '장비 예약 취소',
};
