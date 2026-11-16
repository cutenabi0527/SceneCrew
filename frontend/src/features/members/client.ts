import { asUuid } from '../../shared/api/contract';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import { stateQueryResult } from '../response/client';
import {
    parseGroupPage,
    parseInvite,
    parseChiefTransfer,
    parseMemberChanged,
    parseMemberPage,
    type Member,
    type Page,
} from './contract';

// 참여 인원 관리(#285·#286·#347·#353). 조회 3개와 초대 재발급(033)·인원 수정(038)·총괄 인계(042).
// 038은 키를 무시하는 연산이라 키 없이 한 번 보낸다. 결과를 모르면 다시 보내지 않고 인원 목록을 다시 조회한다.
// 그룹 생성·수정·비활성은 직접 API가 아니라 변경안 연산(UPSERT_WORK_GROUP 등)으로 한다(V9-D14, 022·029·045 제외).

const MAX_PAGES = 10;

export class MemberDesk {
    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {}

    private get<T>(path: string, parser: (d: unknown) => T, signal?: AbortSignal) {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        return this.session.api.get(this.scope, path, parser, { signal });
    }

    invite(signal?: AbortSignal) {
        return this.get(
            `/events/${this.scope.eventId}/invite`,
            (d) => parseInvite(d, this.scope.eventId),
            signal,
        );
    }

    /**
     * 033 초대 링크 재발급(현재 총괄, #353). 기존 링크는 즉시 무효가 되고 공연 revision이 오른다.
     * 키를 무시하는 연산이라 키 없이 한 번 보낸다. 결과를 모르면 다시 보내지 않고 현재 링크를 다시 조회한다.
     */
    async rotateInvite(expectedEventRevision: string) {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const operation = 'members:invite-rotate';
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation,
                intentId,
                method: 'POST',
                path: `/events/${this.scope.eventId}/invite/rotate`,
                body: { expectedEventRevision },
                idempotency: 'none',
                response: 'data',
            },
            (d) => parseInvite(d, this.scope.eventId),
        );
        this.session.scopes.intentKeys.release(this.scope, operation, intentId);
        return stateQueryResult(outcome);
    }

    groups(signal?: AbortSignal) {
        return this.get(`/events/${this.scope.eventId}/work-groups`, parseGroupPage, signal);
    }

    /** 참여 인원 전체. 최대 10페이지까지 따라가고 넘거나 같은 cursor가 오면 잘렸다고 알린다. */
    async members(signal?: AbortSignal): Promise<{ items: Member[]; truncated: boolean }> {
        const base = `/events/${this.scope.eventId}/members`;
        const items: Member[] = [];
        const seen = new Set<string>();
        let cursor: string | null = null;
        for (let i = 0; i < MAX_PAGES; i++) {
            const path: string = cursor ? `${base}?cursor=${encodeURIComponent(cursor)}` : base;
            const page: Page<Member> = await this.get(path, parseMemberPage, signal);
            items.push(...page.items);
            if (page.nextCursor === null) return { items, truncated: false };
            if (seen.has(page.nextCursor)) return { items, truncated: true };
            seen.add(page.nextCursor);
            cursor = page.nextCursor;
        }
        return { items, truncated: true };
    }

    /**
     * 042 총괄 인계(현재 총괄). 후임을 총괄로, 나를 고른 그룹의 팀원으로 한 번에 바꾼다.
     * 키를 무시하는 연산이라 키 없이 한 번 보낸다. 결과를 모르면 다시 보내지 않고 현재 상태를 조회한다.
     */
    async transferChief(
        previousId: string,
        successorId: string,
        groupId: string,
        expectedEventRevision: string,
    ) {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const expected = {
            previousId: asUuid(previousId, 'previousChiefMemberId'),
            successorId: asUuid(successorId, 'successorMemberId'),
            groupId: asUuid(groupId, 'previousChiefWorkGroupId'),
        };
        const operation = 'members:chief-transfer';
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation,
                intentId,
                method: 'POST',
                path: `/events/${this.scope.eventId}/chief-transfer`,
                body: {
                    successorMemberId: expected.successorId,
                    previousChiefWorkGroupId: expected.groupId,
                    expectedEventRevision,
                },
                idempotency: 'none',
                response: 'data',
            },
            (d) => parseChiefTransfer(d, expected),
        );
        this.session.scopes.intentKeys.release(this.scope, operation, intentId);
        return stateQueryResult(outcome);
    }

    /** 038 인원의 그룹·예비 지정 변경(총괄). 바꾼 항목만 보낸다. */
    async update(
        memberId: string,
        changes: { workGroupId?: string; isReserve?: boolean },
        expectedEventRevision: string,
    ) {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const id = asUuid(memberId, 'memberId');
        const operation = 'members:update';
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation,
                intentId,
                method: 'PATCH',
                path: `/members/${id}`,
                body: { ...changes, expectedEventRevision },
                idempotency: 'none',
                response: 'data',
            },
            (d) => parseMemberChanged(d, id),
        );
        this.session.scopes.intentKeys.release(this.scope, operation, intentId);
        return stateQueryResult(outcome);
    }
}
