import type { CommandOutcome, CommandSpec } from '../../shared/api/api';
import {
    ContractViolationError,
    asRecord,
    asBigIntString as asBigRevision,
} from '../../shared/api/contract';
import type { IdentitySession } from '../identity/session';
import type { ParticipationScope } from '../../shared/scope/scope';
import { ScopeDisposedError } from '../../shared/scope/scope';
import { parsePlanJob, parseVenueDraft } from '../plan-job/contract';
import {
    clearJobMemory,
    jobMemoryKey,
    readJobMemory,
    writeJobMemory,
    type JobKind,
} from './job-memory';
import {
    currentDraft,
    parse,
    validateDraft,
    refId,
    type Draft,
    type EventDetail,
    type Extraction,
    type Plan,
    type Task,
    type Upload,
} from './contract';

export class CommandNeedsReview extends Error {
    constructor(
        readonly outcome: CommandOutcome<unknown>,
        /** 결과를 모르는 이전 요청 때문에 이 요청을 보내지 않았으면 그 이전 요청의 이름 */
        readonly blocking?: string,
    ) {
        super('요청 결과와 현재 상태를 확인해주세요. 자동으로 다시 보내지 않습니다.');
    }
}
/**
 * AI 작업(문서 추출·계획 생성) 접수가 503으로 거절됐다. 서버는 작업을 만들기 전에 거절하고(트랜잭션 롤백),
 * 같은 키 재요청도 안전하므로 결과 불명으로 남기지 않는다. 다른 요청을 막지 않는다.
 */
export class AiRequestUnavailable extends Error {}
export class PlanContextChanged extends Error {
    constructor(
        message = '생성 후 입력이 바뀌었습니다. 최신 입력을 확인하고 새 생성을 직접 요청해주세요.',
    ) {
        super(message);
    }
}
const clients = new WeakMap<ParticipationScope, Workflow>();
export function workflow(session: IdentitySession, scope: ParticipationScope) {
    let value = clients.get(scope);
    if (!value) {
        value = new Workflow(session, scope);
        clients.set(scope, value);
    }
    return value;
}
export class Workflow {
    private intents = new Map<string, CommandSpec>();
    readonly jobs = new Map<JobKind, string>();
    private jobRevisions = new Map<string, string>();
    private readonly memoryKey: string;
    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {
        // 새로고침 뒤에도 같은 참여 회차의 작업과 접수 당시 revision을 이어서 쓴다(#268).
        this.memoryKey = jobMemoryKey(scope);
        const memory = readJobMemory(this.memoryKey);
        for (const [kind, id] of Object.entries(memory.jobs) as [JobKind, string][])
            this.jobs.set(kind, id);
        for (const [id, rev] of Object.entries(memory.revisions)) this.jobRevisions.set(id, rev);
        scope.onDispose(() => {
            this.intents.clear();
            this.jobs.clear();
            this.jobRevisions.clear();
            clearJobMemory(this.memoryKey);
        });
    }
    private saveJobs() {
        const jobs: Partial<Record<JobKind, string>> = Object.fromEntries(this.jobs);
        const revisions = Object.fromEntries(
            [...this.jobRevisions].filter(([id]) => Object.values(jobs).includes(id)),
        );
        writeJobMemory(this.memoryKey, { jobs, revisions });
    }
    /** 결과를 닫으면 작업 기억도 지운다. 새로고침해도 다시 열리지 않는다. */
    forgetJob(kind: JobKind) {
        const id = this.jobs.get(kind);
        this.jobs.delete(kind);
        if (id) this.jobRevisions.delete(id);
        this.saveJobs();
    }
    pending(operation: string) {
        return this.intents.has(operation);
    }
    private assert() {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
    }
    private get<T>(path: string, parser: (data: unknown) => T, signal?: AbortSignal) {
        this.assert();
        return this.session.api.get(this.scope, path, parser, { signal });
    }
    async read() {
        const event = await this.get(`/events/${this.scope.eventId}`, (d) =>
            parse<EventDetail>('Event', d),
        );
        if (
            event.id !== this.scope.eventId ||
            !event.myParticipation.active ||
            event.myParticipation.memberId !== this.scope.memberId ||
            event.myParticipation.participationRevision !== this.scope.participationRevision
        )
            throw new ScopeDisposedError();
        const plan = await this.get(`/events/${this.scope.eventId}/plan`, (d) =>
            parse<Plan>('PlanView', d),
        );
        if (
            plan.eventId !== event.id ||
            plan.eventRevision !== event.revision ||
            plan.viewerRole !== event.myParticipation.eventRole
        )
            throw new ContractViolationError(
                'plan',
                '현재 행사/회차/권한/revision이 일치해야 합니다. 다시 조회해주세요.',
            );
        let draft: Draft | null = null;
        if (
            plan.viewerRole === 'WORKER' &&
            (plan.workGroups.some(
                (g) => g.id !== event.myParticipation.workGroupId && g.instructions !== null,
            ) ||
                plan.instructions.some(
                    (i) =>
                        i.scope === 'WORK_GROUP' &&
                        i.workGroupId !== event.myParticipation.workGroupId,
                ))
        )
            throw new ContractViolationError(
                'plan.instructions',
                '본인 그룹에 공개된 안내만 조회해야 합니다.',
            );
        if (plan.viewerRole === 'CHIEF' && event.state === 'DRAFT') {
            const tasks: Task[] = [];
            for (let i = 0; i < plan.tasks.length; i += 10)
                tasks.push(
                    ...(await Promise.all(plan.tasks.slice(i, i + 10).map((t) => this.task(t.id)))),
                );
            draft = currentDraft(plan, tasks);
        }
        return { event, plan, draft };
    }
    async task(id: string) {
        return this.get(`/tasks/${id}`, (d) => {
            const task = parse<Task>('V9Task', d);
            if (task.id !== id)
                throw new ContractViolationError('task.id', '요청한 업무여야 합니다.');
            return task;
        });
    }
    tasks(cursor?: string) {
        return this.get(
            `/events/${this.scope.eventId}/tasks${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`,
            (d) => parse<{ items: Task[]; nextCursor: string | null }>('TaskPage', d),
        );
    }
    private async command<T>(
        operation: string,
        spec: Omit<CommandSpec, 'operation' | 'intentId'>,
        parser: (d: unknown) => T,
    ): Promise<T> {
        this.assert();
        const blocking = [...this.intents.keys()].find((key) => key !== operation);
        if (blocking) throw new CommandNeedsReview({ status: 'reconcile' }, blocking);
        let command = this.intents.get(operation);
        if (!command) {
            command = {
                ...spec,
                // 문서 정리(047)는 204로 본문 없이 끝난다. 본문을 요구하면 성공을 결과 불명으로 잘못 본다.
                response:
                    operation === 'generate' || operation === 'extract'
                        ? 'job'
                        : operation === 'deleteDocuments'
                          ? undefined
                          : 'data',
                body: spec.body === undefined ? undefined : structuredClone(spec.body),
                operation: `plan-workflow:${operation}`,
                intentId: crypto.randomUUID(),
            };
            this.intents.set(operation, command);
        }
        const result = await this.session.api.command(this.scope, command, parser);
        if (
            result.status === 'succeeded' ||
            result.status === 'accepted' ||
            (result.status === 'failed' && result.failure.intent === 'finished')
        )
            this.intents.delete(operation);
        if (result.status === 'succeeded') return result.data!;
        if (result.status === 'accepted' && (operation === 'generate' || operation === 'extract'))
            return result as T;
        throw new CommandNeedsReview(result);
    }
    /** 결과를 모르는 채 남아 다음 명령을 막는 의도의 operation 이름 */
    pendingOperations() {
        return [...this.intents.keys()];
    }
    /**
     * 남은 의도를 버린다. 실서버 운영 화면은 결과를 모르면 서버에서 다시 읽어 지금 상태를 보이므로,
     * 상태로 판정되는 업무 시작·완료·체크(서버가 중복을 상태로 거절한다)는 새 의도로 다시 보내도 된다(#373 리뷰).
     */
    discardIntent(operation: string) {
        this.intents.delete(operation);
    }
    async generate(kind: 'generate' | 'extract', revision: string) {
        const originalRevision =
            (this.intents.get(kind)?.body as { expectedEventRevision?: string } | undefined)
                ?.expectedEventRevision ?? revision;
        let result: { jobId: string };
        try {
            result = await this.command<{ jobId: string }>(
                kind,
                {
                    method: 'POST',
                    path: `/events/${this.scope.eventId}/${kind === 'generate' ? 'plan-generations' : 'extractions'}`,
                    body: { expectedEventRevision: revision },
                    recovery: 'same-key',
                },
                () => {
                    throw new ContractViolationError('status', 'Job 접수202가 필요합니다.');
                },
            );
        } catch (error) {
            const outcome = error instanceof CommandNeedsReview ? error.outcome : null;
            if (outcome?.status === 'failed' && outcome.failure.kind === 'unavailable') {
                this.intents.delete(kind);
                throw new AiRequestUnavailable(
                    outcome.failure.message ??
                        `지금은 ${kind === 'generate' ? '계획 생성' : '문서 추출'}을 사용할 수 없습니다. 잠시 뒤 다시 시도해주세요.`,
                );
            }
            throw error;
        }
        this.jobs.set(kind, result.jobId);
        this.jobRevisions.set(result.jobId, originalRevision);
        this.saveJobs();
        return result.jobId;
    }
    extraction(id: string, signal?: AbortSignal) {
        return this.get(
            `/ai-jobs/${id}`,
            (data) => {
                const result = parse<Extraction>('PublicJobExtractEventInput', data);
                if (result.jobId !== id)
                    throw new ContractViolationError('jobId', '접수한 Job이어야 합니다.');
                return result;
            },
            signal,
        );
    }
    candidate(id: string, currentRevision: string) {
        // 접수 당시 revision을 모르는 작업(다른 탭·지워진 기억)은 지금 입력에 맞는지 알 수 없어 가져오지 않는다.
        const original = this.jobRevisions.get(id);
        if (original === undefined)
            throw new PlanContextChanged(
                '이 생성 결과를 만든 시점의 입력을 확인할 수 없습니다. 최신 입력으로 새 생성을 직접 요청해주세요.',
            );
        if (original !== currentRevision) throw new PlanContextChanged();
        return this.get(`/ai-jobs/${id}`, (data) => {
            const result = parsePlanJob(data, id);
            if (result.state !== 'SUCCEEDED' || result.result.status !== 'READY')
                throw new ContractViolationError('result', '준비된 공개 계획 후보가 필요합니다.');
            const body = asRecord(asRecord(data, 'job').result, 'result');
            const plan = validateDraft(body.plan as Draft, '0', true);
            // 결과6 평면도 초안(#372). 후보 구역을 가리키며 저장 전까지 어디에도 반영하지 않는다.
            const layout = parseVenueDraft(
                body.venueDraft,
                plan.zones.map((z) => refId(z)),
            );
            return { plan, layout, jobId: id };
        });
    }
    async save(draft: Draft, revision: string) {
        validateDraft(draft, revision);
        return this.command(
            'save',
            {
                method: 'PUT',
                path: `/events/${this.scope.eventId}/draft-plan`,
                body: { ...draft, expectedEventRevision: revision },
                idempotency: 'none',
            },
            (data) => {
                const saved = parse<{ plan: Plan; idMappings: { tempId: string; id: string }[] }>(
                    'DraftPlanSaved',
                    data,
                );
                const temps = [draft.workGroups, draft.zones, draft.programs, draft.tasks].flatMap(
                    (a) => a.flatMap((item) => (item.tempId ? [item.tempId] : [])),
                );
                const mapped = saved.idMappings.map((m) => m.tempId);
                const pairs = [
                    [draft.workGroups, saved.plan.workGroups],
                    [draft.zones, saved.plan.zones],
                    [draft.programs, saved.plan.programs],
                    [draft.tasks, saved.plan.tasks],
                ] as const;
                const wrongResource = pairs.some(([original, current]) =>
                    original.some(
                        (item) =>
                            item.tempId &&
                            !current.some(
                                (target) =>
                                    target.id ===
                                    saved.idMappings.find((m) => m.tempId === item.tempId)?.id,
                            ),
                    ),
                );
                const ids = [
                    saved.plan.workGroups,
                    saved.plan.zones,
                    saved.plan.programs,
                    saved.plan.tasks,
                ].flatMap((a) => a.map(refId));
                if (
                    saved.plan.eventId !== this.scope.eventId ||
                    wrongResource ||
                    mapped.length !== temps.length ||
                    new Set(mapped).size !== mapped.length ||
                    new Set(saved.idMappings.map((m) => m.id)).size !== mapped.length ||
                    temps.some((t) => !mapped.includes(t)) ||
                    saved.idMappings.some((m) => !ids.includes(m.id))
                )
                    throw new ContractViolationError(
                        'idMappings',
                        '신규 항목의 매핑을 확인해야 합니다. 현재 계획을 다시 조회해주세요.',
                    );
                return saved;
            },
        );
    }
    /** 047: 내가 이 공연에 올린 입력 문서 원본을 모두 지운다(문서 하나만 지우는 API는 없다). 204, 본문 없음. */
    deleteDocuments(revision: string) {
        return this.command(
            'deleteDocuments',
            {
                method: 'DELETE',
                path: `/events/${this.scope.eventId}/documents`,
                body: { expectedEventRevision: revision },
                idempotency: 'none',
            },
            () => undefined,
        );
    }
    input(fields: Record<string, string>, revision: string) {
        if (Object.values(fields).some((value) => typeof value !== 'string'))
            throw new ContractViolationError('confirmedInput', '조건 값은 문자열이어야 합니다.');
        return this.command(
            'input',
            {
                method: 'PATCH',
                path: `/events/${this.scope.eventId}/input`,
                body: { confirmedInput: fields, expectedEventRevision: revision },
                idempotency: 'none',
            },
            (d) => parse<EventDetail>('Event', d),
        );
    }
    basics(fields: Record<string, string | null>, revision: string) {
        const body = { ...fields, expectedEventRevision: revision };
        parse('EventPatch', body);
        return this.command(
            'basics',
            { method: 'PATCH', path: `/events/${this.scope.eventId}`, body, idempotency: 'none' },
            (d) => parse<EventDetail>('Event', d),
        );
    }
    start(revision: string) {
        return this.command(
            'start-event',
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/start`,
                body: { expectedEventRevision: revision },
                idempotency: 'none',
            },
            (d) => parse<EventDetail>('Event', d),
        );
    }
    /** 행사 기본정보만 읽는다(#286). 계획(/plan) API가 아직 없어도 공연 관리 화면을 쓸 수 있게 한다. */
    /** 공개 조건 안내용 저장된 계획의 직무 그룹·업무 수(027 계획 조회). 업무 단건은 읽지 않는다. */
    async planCounts() {
        const plan = await this.get(`/events/${this.scope.eventId}/plan`, (d) =>
            parse<Plan>('PlanView', d),
        );
        return { workGroups: plan.workGroups.length, tasks: plan.tasks.length };
    }
    async detail() {
        const event = await this.get(`/events/${this.scope.eventId}`, (d) =>
            parse<EventDetail>('Event', d),
        );
        if (
            event.id !== this.scope.eventId ||
            event.myParticipation.memberId !== this.scope.memberId ||
            event.myParticipation.participationRevision !== this.scope.participationRevision
        )
            throw new ScopeDisposedError();
        return event;
    }
    /** 029 공개. 요청 키를 쓰고, 결과를 모르면 같은 키로만 다시 보낸다. 검사 위반은 422 EVENT_INCOMPLETE다. */
    publish(revision: string) {
        return this.command(
            'publish-event',
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/publish`,
                body: { expectedEventRevision: revision },
                recovery: 'same-key',
            },
            (d) => parse<EventDetail>('Event', d),
        );
    }
    /** 031 종료. 사유는 비우면 보내지 않는다. */
    end(reason: string, revision: string) {
        const trimmed = reason.trim();
        return this.command(
            'end-event',
            {
                method: 'POST',
                path: `/events/${this.scope.eventId}/end`,
                body: trimmed
                    ? { reason: trimmed, expectedEventRevision: revision }
                    : { expectedEventRevision: revision },
                idempotency: 'none',
            },
            (d) => parse<EventDetail>('Event', d),
        );
    }
    async perform(
        task: Task,
        operation: 'start' | 'complete' | 'checklist',
        revision: string,
        checks?: { key: string; checked: boolean }[],
    ) {
        if (task.assigneeId !== this.scope.memberId)
            throw new ContractViolationError(
                'assigneeId',
                '본인에게 배정된 업무만 수행할 수 있습니다.',
            );
        return this.command(
            `task:${task.id}`,
            {
                method: operation === 'checklist' ? 'PATCH' : 'POST',
                path: `/tasks/${task.id}/${operation}`,
                body: { expectedEventRevision: revision, ...(checks && { checks }) },
                idempotency: 'none',
            },
            (d) => {
                const value = asRecord(d, 'taskResult');
                const result = {
                    task: parse<Task>('Task', value.task),
                    eventRevision: asBigRevision(value.eventRevision, 'eventRevision'),
                };
                if (result.task.id !== task.id || result.task.assigneeId !== this.scope.memberId)
                    throw new ContractViolationError('task', '본인 업무의 응답이어야 합니다.');
                return result;
            },
        );
    }
    /**
     * EXT09 실제 담당자의 장비 인계·설치·재시험 기록. 같은 키 재요청 대상이라 응답을 못 받으면 같은 본문으로만
     * 다시 보낸다. 성공 응답은 최소 접수증(id·revision·eventRevision)이며 상세는 다시 조회한다.
     */
    async equipmentCheck(
        task: Task,
        input: {
            assetId: string;
            kind: 'HANDOVER' | 'INSTALLATION' | 'RETEST';
            passed: boolean;
            note?: string;
        },
        revision: string,
    ) {
        if (task.assigneeId !== this.scope.memberId)
            throw new ContractViolationError(
                'assigneeId',
                '본인에게 배정된 업무만 기록할 수 있습니다.',
            );
        return this.command(
            // 단계(kind)까지 키에 넣는다. 결과 모름으로 남은 인수 기록이 설치·재시험으로 다시 나가지 않게 한다
            `check:${task.id}:${input.assetId}:${input.kind}`,
            {
                method: 'POST',
                path: `/tasks/${task.id}/equipment-checks`,
                body: {
                    expectedEventRevision: revision,
                    assetId: input.assetId,
                    kind: input.kind,
                    passed: input.passed,
                    evidence: {
                        sourceKind: 'MANUAL_CONFIRMATION',
                        sourceRef: `task:${task.id}`,
                        observedAt: new Date().toISOString(),
                        ...(input.note?.trim() && { note: input.note.trim() }),
                    },
                },
                recovery: 'same-key',
            },
            (d) =>
                parse<{ id: string; revision: string; eventRevision: string }>('FinalReceipt', d),
        );
    }
    async upload(files: File[]) {
        if (this.pending('upload')) return this.sendUpload();
        if (this.intents.size) throw new CommandNeedsReview({ status: 'reconcile' });
        if (
            files.length < 1 ||
            files.length > 3 ||
            files.some((f) => f.size > 20971520 || !/\.(pdf|txt|md)$/i.test(f.name)) ||
            files.reduce((n, f) => n + f.size, 0) > 31457280
        )
            throw new Error('PDF/TXT/MD를 최대3개, 파일당20MiB/합계30MiB 이내로 선택해주세요.');
        const form = new FormData();
        const fingerprints = [];
        for (const file of files) {
            const mime =
                file.type ||
                (/\.pdf$/i.test(file.name)
                    ? 'application/pdf'
                    : /\.md$/i.test(file.name)
                      ? 'text/markdown'
                      : 'text/plain');
            const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
            this.assert();
            fingerprints.push([
                file.name,
                mime,
                file.size,
                [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join(''),
            ]);
            form.append('files', new Blob([file], { type: mime }), file.name);
        }
        this.intents.set('upload', {
            method: 'POST',
            path: `/events/${this.scope.eventId}/documents`,
            form: { data: form, fingerprint: JSON.stringify(fingerprints) },
            recovery: 'same-key',
            operation: 'plan-workflow:upload',
            response: 'data',
            intentId: crypto.randomUUID(),
        });
        return this.sendUpload();
    }
    private sendUpload() {
        const original = this.intents.get('upload')!;
        return this.command('upload', original, (d) => {
            const result = parse<Upload>('UploadResult', d);
            const count = original.form!.data.getAll('files').length;
            if (
                result.files.length !== count ||
                new Set(result.files.map((f) => f.index)).size !== count ||
                result.files.some((f) => f.index >= count)
            )
                throw new ContractViolationError('files', '파일별 등록 결과가 필요합니다.');
            return result;
        });
    }
}
