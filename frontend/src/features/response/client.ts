import type { CommandOutcome, CommandSpec } from '../../shared/api/api';
import { ContractViolationError, asUuid } from '../../shared/api/contract';
import type { Failure } from '../../shared/api/failure';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import {
    parseAck,
    parseAckPage,
    parseAnalysisJob,
    parseChangeJob,
    parsePointer,
    parseRecoveryJob,
    parseSimulation,
    type RecoveryCandidate,
    type RecoverySimulation,
    parseChangeSet,
    parseChangeSetPage,
    parseEvidenceSnapshot,
    parseIssue,
    parseIssueCreated,
    parseIssuePage,
    parseReportCreated,
    parseSituationReceipt,
    type SituationType,
    type ChangeSet,
    type IssueStatus,
} from './contract';

// 상황 보고·대응·변경 승인·명시 확인(#287) 요청.
// - 같은 키 재요청(멱등 REQUIRED): 056 보고 접수, 059 추가 보고, 062 분석, 064 변경 생성, 070 적용.
//   응답을 받지 못하면 같은 의도(키·본문)로 사용자가 다시 보낼 수 있다. BE는 최초 응답을 24시간 되돌려 준다(G02).
// - 비멱등: 058 이슈 상태, 068 편집, 069 미리보기, 071 폐기, 077 확인. 결과를 모르면 다시 보내지 않고 현재 상태를 조회한다.
// - 결과 불명(IDEMPOTENCY_OUTCOME_UNKNOWN)·만료는 같은 키도 다시 보내지 않는다. 새 의도는 사용자가 명시할 때만 만든다.

export type Result<T> =
    // 처리 결과를 받았다.
    | { kind: 'done'; data: T }
    // 비동기 Job을 접수했다. 접수와 완료는 별개다.
    | { kind: 'job'; jobId: string }
    // 응답을 받지 못했거나 서버가 잠시 처리하지 못했다. 같은 의도로 다시 보낼 수 있다.
    | { kind: 'retry'; failure: Failure }
    // 처리 여부를 알 수 없다. 현재 상태를 조회해 확인한다. 같은 요청을 자동으로 다시 보내지 않는다.
    | { kind: 'check' }
    // 기대 revision이 현재와 다르다. target은 서버가 알려 준 대상(event·changeSet·reservation·equipment).
    | { kind: 'conflict'; target: string | null }
    // 서버가 확정해서 거절했다.
    | { kind: 'rejected'; failure: Failure };

const OP = {
    createIssue: 'response:create-issue',
    addReport: 'response:add-report',
    patchIssue: 'response:patch-issue',
    analyze: 'response:analyze',
    generate: 'response:generate-change',
    preview: 'response:preview',
    apply: 'response:apply',
    discard: 'response:discard',
    edit: 'response:edit-change',
    ack: 'response:ack',
    recover: 'response:recover',
    createChange: 'response:create-change',
    correctSituation: 'response:correct-situation',
} as const;

const conflictTarget = (failure: Failure) => failure.target ?? null;

/** 같은 키 재요청 명령의 결과를 화면 상태로 바꾼다. keep이면 의도를 유지한다. */
export function sameKeyResult<T>(outcome: CommandOutcome<T>): { result: Result<T>; keep: boolean } {
    switch (outcome.status) {
        case 'succeeded':
            return outcome.data === undefined
                ? { result: { kind: 'check' }, keep: true }
                : { result: { kind: 'done', data: outcome.data }, keep: false };
        case 'accepted':
            return { result: { kind: 'job', jobId: outcome.jobId }, keep: false };
        case 'reconcile':
        case 'expired':
            return { result: { kind: 'check' }, keep: true };
        case 'failed': {
            const f = outcome.failure;
            if (f.code === 'IDEMPOTENCY_OUTCOME_UNKNOWN')
                return { result: { kind: 'check' }, keep: true };
            if (
                f.kind === 'result-expired' ||
                f.kind === 'idempotency-mismatch' ||
                f.kind === 'text-not-retained'
            )
                return { result: { kind: 'check' }, keep: true };
            if (
                f.kind === 'unknown-outcome' ||
                f.kind === 'unavailable' ||
                f.kind === 'rate-limited' ||
                f.kind === 'request-in-progress'
            )
                return { result: { kind: 'retry', failure: f }, keep: true };
            if (f.kind === 'version-conflict')
                return { result: { kind: 'conflict', target: conflictTarget(f) }, keep: false };
            return { result: { kind: 'rejected', failure: f }, keep: f.intent === 'pending' };
        }
    }
}

/** 비멱등 명령의 결과. 성공·확정 거절 외에는 모두 현재 상태 조회로 보낸다. */
export function stateQueryResult<T>(outcome: CommandOutcome<T>): Result<T> {
    if (outcome.status === 'succeeded' && outcome.data !== undefined)
        return { kind: 'done', data: outcome.data };
    if (outcome.status === 'failed') {
        const f = outcome.failure;
        if (f.kind === 'version-conflict') return { kind: 'conflict', target: conflictTarget(f) };
        if (f.intent === 'finished' || f.kind === 'unauthenticated')
            return { kind: 'rejected', failure: f };
    }
    return { kind: 'check' };
}

type Spec = Omit<CommandSpec, 'operation' | 'intentId'>;

const clients = new WeakMap<ParticipationScope, Responder>();
/** 같은 참여 회차 안에서는 화면을 다시 열어도 남은 의도(키·본문)를 이어 간다. */
export function responder(session: IdentitySession, scope: ParticipationScope) {
    let value = clients.get(scope);
    if (!value) {
        value = new Responder(session, scope);
        clients.set(scope, value);
    }
    return value;
}

export class Responder {
    // 같은 키 재요청 의도. 키는 명령 종류와 대상(이슈·보고·변경안)으로 구분한다.
    private intents = new Map<string, { id: string; spec: Spec }>();

    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {
        scope.onDispose(() => this.intents.clear());
    }

    private assert() {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
    }

    private get<T>(path: string, parser: (data: unknown) => T, signal?: AbortSignal) {
        this.assert();
        return this.session.api.get(this.scope, path, parser, { signal });
    }

    /** 이 대상에 확인이 필요한 의도가 남아 있는가 */
    pending(operation: keyof typeof OP, target: string) {
        return this.intents.has(`${OP[operation]}:${target}`);
    }

    /** 사용자가 결과를 확인한 뒤 새로 하겠다고 명시했다. 남은 의도와 키를 버린다. */
    forget(operation: keyof typeof OP, target: string) {
        const key = `${OP[operation]}:${target}`;
        const intent = this.intents.get(key);
        if (intent) this.session.scopes.intentKeys.release(this.scope, OP[operation], intent.id);
        this.intents.delete(key);
    }

    private async sameKey<T>(
        operation: keyof typeof OP,
        target: string,
        spec: Spec,
        parser: (data: unknown) => T,
        replace = false,
    ): Promise<Result<T>> {
        this.assert();
        const key = `${OP[operation]}:${target}`;
        // 최종값으로 덮어쓰는 명령(replace)은 입력이 바뀌면 남은 의도를 버리고 새 값으로 보낸다.
        const left = this.intents.get(key);
        if (replace && left && JSON.stringify(left.spec) !== JSON.stringify(spec))
            this.forget(operation, target);
        // 남은 의도가 있으면 원래 본문 그대로 보낸다(새 입력을 같은 키에 끼워 넣지 않는다).
        const intent = this.intents.get(key) ?? { id: crypto.randomUUID(), spec };
        this.intents.set(key, intent);
        const outcome = await this.session.api.command(
            this.scope,
            { ...intent.spec, operation: OP[operation], intentId: intent.id, recovery: 'same-key' },
            parser,
        );
        const { result, keep } = sameKeyResult(outcome);
        if (!keep && this.intents.get(key) === intent) this.intents.delete(key);
        return result;
    }

    private async once<T>(
        operation: keyof typeof OP,
        spec: Spec,
        parser: (data: unknown) => T,
    ): Promise<Result<T>> {
        this.assert();
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            { ...spec, operation: OP[operation], intentId, idempotency: 'none', response: 'data' },
            parser,
        );
        // 다음 판단은 현재 상태 조회로 한다. 재전송에 쓰지 않는 키는 정리한다.
        this.session.scopes.intentKeys.release(this.scope, OP[operation], intentId);
        return stateQueryResult(outcome);
    }

    // 조회 ---------------------------------------------------------------

    issues(signal?: AbortSignal) {
        return this.get(
            `/events/${this.scope.eventId}/issues?limit=50`,
            (d) => parseIssuePage(d, this.scope.eventId),
            signal,
        );
    }

    issue(issueId: string, signal?: AbortSignal) {
        const id = asUuid(issueId, 'issueId');
        return this.get(`/issues/${id}`, (d) => parseIssue(d, this.scope.eventId, id), signal);
    }

    changeSets(signal?: AbortSignal) {
        return this.get(
            `/events/${this.scope.eventId}/change-sets?limit=50`,
            (d) => parseChangeSetPage(d, this.scope.eventId),
            signal,
        );
    }

    changeSet(changeSetId: string, signal?: AbortSignal) {
        const id = asUuid(changeSetId, 'changeSetId');
        return this.get(
            `/change-sets/${id}`,
            (d) => parseChangeSet(d, this.scope.eventId, id),
            signal,
        );
    }

    myChanges(signal?: AbortSignal, cursor?: string) {
        const next = cursor ? `&cursor=${encodeURIComponent(cursor)}` : '';
        return this.get(
            `/events/${this.scope.eventId}/my-changes?limit=50${next}`,
            parseAckPage,
            signal,
        );
    }

    analysisJob(jobId: string, signal?: AbortSignal) {
        const id = asUuid(jobId, 'jobId');
        return this.get(`/ai-jobs/${id}`, (d) => parseAnalysisJob(d, id), signal);
    }

    changeJob(jobId: string, signal?: AbortSignal) {
        const id = asUuid(jobId, 'jobId');
        return this.get(`/ai-jobs/${id}`, (d) => parseChangeJob(d, id), signal);
    }

    // 보고 --------------------------------------------------------------

    /** 056 최초 보고. 공식 Issue와 최초 Report를 함께 만든다. */
    createIssue(input: {
        zoneId: string;
        workGroupId?: string;
        taskId?: string | null;
        text: string;
        quickTemplateKey?: string | null;
        // 음성 보고면 전사 접수(060)의 audioId. 전사문은 본인이 확인·수정한 text로 보낸다.
        audioId?: string | null;
    }) {
        const body = {
            zoneId: input.zoneId,
            ...(input.workGroupId && { workGroupId: input.workGroupId }),
            ...(input.taskId !== undefined && { taskId: input.taskId }),
            report: {
                kind: 'INITIAL',
                text: input.text,
                quickTemplateKey: input.quickTemplateKey ?? null,
                audioId: input.audioId ? asUuid(input.audioId, 'audioId') : null,
            },
        };
        return this.sameKey(
            'createIssue',
            'new',
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/issues`,
                body,
                response: 'data',
            },
            (d) => parseIssueCreated(d, this.scope.eventId),
        );
    }

    /** 059 추가·해결 보고. 원문은 불변이며 작성 당시 참여 회차를 서버가 고정한다. */
    addReport(
        issueId: string,
        kind: 'FOLLOWUP' | 'RESOLUTION',
        text: string,
        audioId: string | null = null,
    ) {
        const id = asUuid(issueId, 'issueId');
        return this.sameKey(
            'addReport',
            `${id}:${kind}`,
            {
                method: 'POST',
                path: `/issues/${id}/reports`,
                body: {
                    kind,
                    text,
                    quickTemplateKey: null,
                    audioId: audioId ? asUuid(audioId, 'audioId') : null,
                },
                response: 'data',
            },
            (d) => parseReportCreated(d, id),
        );
    }

    /** 058 총괄의 이슈 상태 변경. 비멱등이라 결과를 모르면 다시 조회한다. */
    patchIssue(issueId: string, change: { status?: IssueStatus }, expectedEventRevision: string) {
        const id = asUuid(issueId, 'issueId');
        return this.once(
            'patchIssue',
            { method: 'PATCH', path: `/issues/${id}`, body: { ...change, expectedEventRevision } },
            (d) => parseIssue(d, this.scope.eventId, id),
        );
    }

    /**
     * EXT19 총괄의 운영 상황 확인·정정(V9-D48). 사유가 필수이고 같은 키로 다시 보낼 수 있다.
     * 최종값으로 덮어쓰는 명령이라, 결과를 모르는 사이 입력을 바꿔 다시 저장하면 바꾼 값을 새 키로 보낸다.
     * 응답은 영수증뿐이라 화면은 이슈를 다시 읽어 그린다.
     */
    correctSituation(
        issueId: string,
        input: { primaryType: SituationType | null; relatedTypes: SituationType[]; reason: string },
        expectedEventRevision: string,
    ) {
        const id = asUuid(issueId, 'issueId');
        return this.sameKey(
            'correctSituation',
            id,
            {
                method: 'PATCH',
                path: `/issues/${id}/situation`,
                body: { expectedEventRevision, ...input },
                response: 'data',
            },
            parseSituationReceipt,
            true,
        );
    }

    // 분석·변경 ----------------------------------------------------------

    /** 062 보고 분석 Job 접수 */
    analyze(reportId: string, expectedEventRevision: string) {
        const id = asUuid(reportId, 'reportId');
        return this.sameKey(
            'analyze',
            id,
            {
                method: 'POST',
                path: `/reports/${id}/analyze`,
                body: { expectedEventRevision },
                response: 'job',
            },
            (d) => d,
        );
    }

    /** 064 AI 변경안 생성 Job 접수. 결과의 changeSetId로 변경안을 연다. */
    generateChange(input: {
        issueId: string;
        instruction: string;
        reportIds: string[];
        expectedEventRevision: string;
    }) {
        const id = asUuid(input.issueId, 'issueId');
        return this.sameKey(
            'generate',
            id,
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/change-generations`,
                body: {
                    issueId: id,
                    instruction: input.instruction,
                    reportIds: input.reportIds,
                    expectedEventRevision: input.expectedEventRevision,
                },
                response: 'job',
            },
            (d) => d,
        );
    }

    /** 069 미리보기. 서버가 현재 상태로 diff와 적용 해시를 계산한다. */
    preview(changeSetId: string, expectedChangeSetRevision: string, expectedEventRevision: string) {
        const id = asUuid(changeSetId, 'changeSetId');
        return this.once(
            'preview',
            {
                method: 'POST',
                path: `/change-sets/${id}/preview`,
                body: { expectedChangeSetRevision, expectedEventRevision },
            },
            (d) => parseChangeSet(d, this.scope.eventId, id),
        );
    }

    /** 070 총괄 승인·적용. 미리보기에서 받은 해시와 두 revision을 그대로 보낸다. */
    apply(
        changeSetId: string,
        input: {
            expectedChangeSetRevision: string;
            previewHash: string;
            expectedEventRevision: string;
        },
    ) {
        const id = asUuid(changeSetId, 'changeSetId');
        return this.sameKey(
            'apply',
            id,
            { method: 'POST', path: `/change-sets/${id}/apply`, body: input, response: 'data' },
            (d) => {
                const set = parseChangeSet(d, this.scope.eventId, id);
                if (set.status !== 'APPLIED')
                    throw new ContractViolationError(
                        'changeSet.status',
                        '적용된 변경안이어야 합니다.',
                    );
                return set;
            },
        );
    }

    // 복구 계산(#308) -------------------------------------------------------

    /** EXT06 복구 후보 계산 접수(총괄). 운영 상태를 바꾸지 않는다. 확인된 근거·가정은 아직 보내지 않는다. */
    recover(input: {
        issueId: string | null;
        targetAssetIds: string[];
        expectedEventRevision: string;
    }) {
        const ids = input.targetAssetIds.map((id) => asUuid(id, 'targetAssetIds'));
        return this.sameKey(
            'recover',
            input.issueId ?? 'event',
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/recovery-simulations`,
                body: {
                    expectedEventRevision: input.expectedEventRevision,
                    issueId: input.issueId,
                    targetAssetIds: ids,
                    evidenceRefs: [],
                    assumptions: [],
                },
                response: 'job',
            },
            (d) => d,
        );
    }

    recoveryJob(jobId: string, signal?: AbortSignal) {
        const id = asUuid(jobId, 'jobId');
        return this.get(`/ai-jobs/${id}`, (d) => parseRecoveryJob(d, id), signal);
    }

    /** EXT07 계산 결과. 완료 7일 뒤에는 410 RECOVERY_RESULT_EXPIRED이며 자동으로 다시 계산하지 않는다. */
    simulation(simulationId: string, signal?: AbortSignal) {
        const id = asUuid(simulationId, 'simulationId');
        return this.get(
            `/recovery-simulations/${id}`,
            (d) => parseSimulation(d, this.scope.eventId, id),
            signal,
        );
    }

    /** Q1 이 이슈의 최근 완료 계산. 없으면 404이며 진행 중·실패 계산은 포함하지 않는다. */
    latestSimulation(issueId: string, signal?: AbortSignal) {
        const id = asUuid(issueId, 'issueId');
        return this.get(`/issues/${id}/recovery-simulations/latest`, parsePointer, signal);
    }

    /** 066 고른 복구 후보로 변경안을 만든다. 후보의 연산을 그대로 보내고 계산 근거를 simulationRefs로 묶는다. */
    createFromCandidate(input: {
        simulation: RecoverySimulation;
        candidate: RecoveryCandidate;
        issueId: string | null;
        expectedEventRevision: string;
    }) {
        const { simulation, candidate } = input;
        return this.sameKey(
            'createChange',
            `${simulation.id}:${candidate.candidateId}`,
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/change-sets`,
                body: {
                    mode: 'MANUAL',
                    operations: candidate.operations,
                    issueId: input.issueId,
                    rationale: `복구 대안 ${candidate.candidateId}: ${candidate.reasons.join(' / ') || '복구 계산 결과'}`,
                    expectedEventRevision: input.expectedEventRevision,
                    simulationRefs: [
                        {
                            simulationId: simulation.id,
                            candidateId: candidate.candidateId,
                            inputHash: simulation.inputHash,
                        },
                    ],
                },
                response: 'data',
            },
            (d) => parseChangeSet(d, this.scope.eventId),
        );
    }

    /**
     * 066 안전 인력 지원안(#361, V9-D57). 연산 대신 고른 후보를 supportSelection으로 보내면 BE가 다시 검사해 연산을 만든다.
     * 의도 이름에 관측·구역·시간·고른 인원(참여 회차)·기대 revision을 넣어, 같은 요청은 같은 키로만 다시 보내고
     * 선택이나 revision이 바뀌면 새 키를 쓴다.
     */
    createSupport(input: {
        selection: {
            observationId: string;
            sourceZoneId: string;
            targetZoneId: string;
            workGroupId: string;
            startAt: string;
            endAt: string;
            candidates: {
                memberId: string;
                participationRevision: string;
                sourceTaskId: string;
            }[];
        };
        expectedEventRevision: string;
    }) {
        const s = input.selection;
        const members = s.candidates
            .map((m) => `${m.memberId}@${m.participationRevision}`)
            .sort()
            .join(',');
        return this.sameKey(
            'createChange',
            `support:${s.observationId}:${s.sourceZoneId}>${s.targetZoneId}:${s.workGroupId}:${s.startAt}~${s.endAt}:${members}@${input.expectedEventRevision}`,
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/change-sets`,
                // 최상위에는 mode·expectedEventRevision·supportSelection만 보낸다(BE #361 계약)
                body: {
                    mode: 'MANUAL',
                    expectedEventRevision: input.expectedEventRevision,
                    supportSelection: s,
                },
                response: 'data',
            },
            (d) => parseChangeSet(d, this.scope.eventId),
        );
    }

    /**
     * 066 총괄이 직접 만드는 변경안(MANUAL). 같은 의도는 같은 키로만 다시 보낸다.
     * target은 의도를 구별하는 이름이다(예: 업무·새 담당).
     */
    createManual(input: {
        target: string;
        operations: { type: string; payload: Record<string, unknown> }[];
        rationale: string;
        expectedEventRevision: string;
    }) {
        return this.sameKey(
            'createChange',
            input.target,
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/change-sets`,
                body: {
                    mode: 'MANUAL',
                    operations: input.operations,
                    issueId: null,
                    rationale: input.rationale,
                    expectedEventRevision: input.expectedEventRevision,
                },
                response: 'data',
            },
            (d) => parseChangeSet(d, this.scope.eventId),
        );
    }

    /**
     * 068 변경안 편집(근거 문장·연산). 키는 무시되는 연산이라 보내지 않고, 결과를 모르면 현재 상태를 조회한다.
     * 편집 뒤에는 서버가 미리보기를 지우므로(DRAFT) 다시 미리보기한 뒤 승인한다.
     */
    async edit(
        changeSetId: string,
        changes: { operations?: ChangeSet['operations']; rationale?: string },
        expectedChangeSetRevision: string,
        expectedEventRevision: string,
    ) {
        const id = asUuid(changeSetId, 'changeSetId');
        if (changes.operations && !changes.operations.length)
            throw new ContractViolationError('operations', '연산을 하나 이상 남겨야 합니다.');
        return this.once(
            'edit',
            {
                method: 'PATCH',
                path: `/change-sets/${id}`,
                body: { ...changes, expectedChangeSetRevision, expectedEventRevision },
            },
            (d) => parseChangeSet(d, this.scope.eventId, id),
        );
    }

    /** R2 과거 revision의 근거 기록. 기록이 없으면 서버가 404를 준다(빈 근거로 바꾸지 않는다). */
    async evidence(changeSetId: string, revision: string, signal?: AbortSignal) {
        const id = asUuid(changeSetId, 'changeSetId');
        if (!/^(0|[1-9][0-9]*)$/.test(revision))
            throw new ContractViolationError('revision', '10진 revision이어야 합니다.');
        return this.get(
            `/change-sets/${id}/evidence/${revision}`,
            (d) => parseEvidenceSnapshot(d, id, revision),
            signal,
        );
    }

    /** 071 폐기 */
    discard(changeSetId: string, expectedChangeSetRevision: string) {
        const id = asUuid(changeSetId, 'changeSetId');
        return this.once(
            'discard',
            {
                method: 'POST',
                path: `/change-sets/${id}/discard`,
                body: { expectedChangeSetRevision },
            },
            (d) => parseChangeSet(d, this.scope.eventId, id),
        );
    }

    /** 077 팀원의 변경 명시 확인. 화면에 보인 대상 묶음의 해시를 보낸다. */
    ack(ackId: string, targetsHash: string) {
        const id = asUuid(ackId, 'ackId');
        return this.once(
            'ack',
            { method: 'POST', path: `/change-acknowledgments/${id}/ack`, body: { targetsHash } },
            (d) => parseAck(d, id),
        );
    }
}
