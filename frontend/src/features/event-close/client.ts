import type { CommandOutcome } from '../../shared/api/api';
import {
    ContractViolationError,
    asBigIntString,
    asRecord,
    asString,
} from '../../shared/api/contract';
import type { Failure } from '../../shared/api/failure';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import { parse, type EventDetail } from '../plan-workflow/contract';

// 공연 종료(SC-API-031)와 일회 종료 요약(SC-API-080).
// 요약 본문은 최초 200 응답에만 온다. 서버도 FE도 보관하지 않는다(D10).
// 같은 키 재요청에서 410 TEXT_NOT_RETAINED는 "생성됐지만 다시 받을 수 없음", 409 IDEMPOTENCY_OUTCOME_UNKNOWN은
// "처리 여부 불명"이다. 둘 다 자동으로 다시 보내지 않고, 새 생성은 사용자가 명시할 때만 새 키로 시작한다.

const END = 'event-close:end';
const SUMMARY = 'event-close:summary';

export interface SummaryText {
    text: string;
    format: 'TEXT' | 'MARKDOWN';
    contextRevision: string | null;
}

export function parseSummary(data: unknown): SummaryText {
    const body = asRecord(data, 'summary');
    for (const key of Object.keys(body))
        if (!['text', 'format', 'contextRevision'].includes(key))
            throw new ContractViolationError(`summary.${key}`, '계약에 없는 필드입니다.');
    const text = asString(body['text'], 'summary.text');
    if (!text.trim())
        throw new ContractViolationError('summary.text', '빈 요약은 받을 수 없습니다.');
    const format = body['format'] ?? 'TEXT';
    if (format !== 'TEXT' && format !== 'MARKDOWN')
        throw new ContractViolationError('summary.format', 'TEXT 또는 MARKDOWN이어야 합니다.');
    const contextRevision =
        body['contextRevision'] === undefined
            ? null
            : asBigIntString(body['contextRevision'], 'summary.contextRevision');
    return { text, format, contextRevision };
}

export type SummaryState =
    // 최초 생성 성공. 화면에만 있고 떠나면 사라진다.
    | { kind: 'text'; summary: SummaryText }
    // 410: 이미 생성됐지만 서버가 원문을 보관하지 않는다.
    | { kind: 'not-retained' }
    // 409 IDEMPOTENCY_OUTCOME_UNKNOWN 등: 처리 여부를 알 수 없다. 같은 키도 다시 보내지 않는다.
    | { kind: 'outcome-unknown' }
    // 응답을 받지 못했다(단절·5xx). 사용자가 누르면 같은 키로 처리 사실을 확인한다.
    | { kind: 'no-response' }
    // 409 REQUEST_IN_PROGRESS: 같은 요청을 처리 중이다.
    | { kind: 'in-progress' }
    // 서버가 확정해서 거절했다(권한·상태·revision 등).
    | { kind: 'rejected'; failure: Failure };

export type EndResult =
    | { kind: 'ended'; event: EventDetail }
    | { kind: 'version-conflict' }
    | { kind: 'check-state' }
    | { kind: 'rejected'; failure: Failure };

/** 요약 명령 결과를 화면 상태로 바꾼다. 키를 계속 쓸지(keep)도 함께 돌려준다. */
export function summaryState(outcome: CommandOutcome<SummaryText>): {
    state: SummaryState;
    keep: boolean;
} {
    switch (outcome.status) {
        case 'succeeded':
            if (!outcome.data) return { state: { kind: 'outcome-unknown' }, keep: true };
            return { state: { kind: 'text', summary: outcome.data }, keep: false };
        case 'accepted':
        case 'reconcile':
        case 'expired':
            return { state: { kind: 'outcome-unknown' }, keep: true };
        case 'failed': {
            const f = outcome.failure;
            if (f.kind === 'text-not-retained')
                return { state: { kind: 'not-retained' }, keep: true };
            if (f.kind === 'request-in-progress')
                return { state: { kind: 'in-progress' }, keep: true };
            if (f.kind === 'unknown-outcome' && f.code === 'IDEMPOTENCY_OUTCOME_UNKNOWN')
                return { state: { kind: 'outcome-unknown' }, keep: true };
            if (f.kind === 'unknown-outcome' || f.kind === 'unavailable')
                return { state: { kind: 'no-response' }, keep: true };
            if (f.kind === 'idempotency-mismatch' || f.kind === 'result-expired')
                return { state: { kind: 'outcome-unknown' }, keep: true };
            return { state: { kind: 'rejected', failure: f }, keep: f.intent === 'pending' };
        }
    }
}

const closers = new WeakMap<ParticipationScope, EventCloser>();
/** 같은 참여 회차 안에서는 화면을 다시 열어도 진행 중인 요약 의도(키)를 이어 간다. */
export function eventCloser(session: IdentitySession, scope: ParticipationScope) {
    let value = closers.get(scope);
    if (!value) {
        value = new EventCloser(session, scope);
        closers.set(scope, value);
    }
    return value;
}

export class EventCloser {
    // 진행 중인 요약 의도. 본문 revision은 의도가 끝날 때까지 바꾸지 않는다.
    private summaryIntent: { id: string; revision: string } | null = null;

    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {
        scope.onDispose(() => {
            this.summaryIntent = null;
        });
    }

    private assert() {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
    }

    private event(data: unknown) {
        const event = parse<EventDetail>('Event', data);
        if (event.id !== this.scope.eventId)
            throw new ContractViolationError('event.id', '현재 공연이어야 합니다.');
        return event;
    }

    /** 현재 공연 상태를 다시 조회한다. */
    read(signal?: AbortSignal) {
        this.assert();
        return this.session.api.get(
            this.scope,
            `/events/${this.scope.eventId}`,
            (d) => this.event(d),
            { signal },
        );
    }

    /** 공연 종료. 멱등 대상이 아니므로 결과가 불명하면 다시 보내지 않고 상태를 조회하게 한다. */
    async end(revision: string): Promise<EndResult> {
        this.assert();
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation: END,
                intentId,
                method: 'POST',
                path: `/events/${this.scope.eventId}/end`,
                body: { expectedEventRevision: revision },
                idempotency: 'none',
                response: 'data',
            },
            (d) => {
                const event = this.event(d);
                if (event.state !== 'ENDED')
                    throw new ContractViolationError('event.state', '종료된 공연이어야 합니다.');
                return event;
            },
        );
        // 다음 판단은 항상 현재 상태 조회로 한다. 남은 키는 재전송에 쓰지 않으므로 정리한다.
        this.session.scopes.intentKeys.release(this.scope, END, intentId);
        if (outcome.status === 'succeeded' && outcome.data)
            return { kind: 'ended', event: outcome.data };
        if (outcome.status === 'failed') {
            const f = outcome.failure;
            if (f.kind === 'version-conflict') return { kind: 'version-conflict' };
            if (f.intent === 'finished' || f.kind === 'unauthenticated')
                return { kind: 'rejected', failure: f };
        }
        return { kind: 'check-state' };
    }

    /** 요약 의도가 남아 있는가(결과 확인이나 명시적 새 생성이 필요하다). */
    get hasIntent() {
        return this.summaryIntent !== null;
    }

    /**
     * 요약 생성. 남은 의도가 있으면 같은 키·같은 본문으로 보낸다(처리 사실 확인).
     * 서버가 결과 불명·원문 미보관을 알린 키는 공통 API가 막으므로 다시 나가지 않는다.
     */
    async summarize(revision: string): Promise<SummaryState> {
        this.assert();
        const intent = (this.summaryIntent ??= { id: crypto.randomUUID(), revision });
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation: SUMMARY,
                intentId: intent.id,
                method: 'POST',
                path: `/events/${this.scope.eventId}/summary`,
                body: { expectedEventRevision: intent.revision },
                recovery: 'same-key',
                response: 'data',
            },
            parseSummary,
        );
        const { state, keep } = summaryState(outcome);
        if (!keep && this.summaryIntent === intent) this.summaryIntent = null;
        return state;
    }

    /** 사용자가 새 생성을 명시했다. 이전 의도를 버리고 새 키로 시작한다. */
    startOver(revision: string) {
        this.assert();
        if (this.summaryIntent)
            this.session.scopes.intentKeys.release(this.scope, SUMMARY, this.summaryIntent.id);
        this.summaryIntent = null;
        return this.summarize(revision);
    }
}
