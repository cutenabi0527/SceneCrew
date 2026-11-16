import type { QueryClient } from '@tanstack/react-query';
import { IntentKeyStore } from './intent-keys';
import {
    type AccountScope,
    type ParticipationScope,
    Lifetime,
    ScopeContractError,
    accountPrefix,
    eventPrefix,
    parseBigInt,
    requireId,
} from './scope';

// 정리 함수가 던진 오류는 다른 정리를 막지 않고 모아서 보고한다.
export type CleanupReporter = (errors: unknown[]) => void;

export type ParticipationChange =
    // 처음 들어왔다.
    | 'entered'
    // 같은 회차를 다시 받았다. 아무것도 하지 않는다.
    | 'unchanged'
    // 회차가 올랐다. 옛 회차의 요청·캐시·구독을 폐기했다.
    | 'advanced'
    // Member가 달라졌다. 옛 범위를 폐기했다.
    | 'replaced'
    // 본 적 있는 회차보다 낮다(늦게 도착한 옛 응답). 무시하고 현재 범위를 유지했다.
    | 'stale';

export type ParticipationResult =
    | { change: Exclude<ParticipationChange, 'stale'>; scope: ParticipationScope }
    // 현재 범위가 없으면(이탈 뒤 늦게 온 옛 회차) scope는 null이다.
    | { change: 'stale'; scope: ParticipationScope | null };

interface ParticipationEntry {
    scope: ParticipationScope;
    lifetime: Lifetime;
}

interface AccountEntry {
    scope: AccountScope;
    lifetime: Lifetime;
}

// 계정과 행사 참여 회차의 수명을 관리한다. 인증 방식·API 경로·실시간 프로토콜을 알지 못한다.
//  - 계정이 바뀌면(로그아웃 포함) 그 계정의 모든 요청 취소·캐시 폐기·구독 정리.
//  - 행사 이탈·참여 회차 변경은 그 행사만 정리하고 다른 행사와 계정 세션은 보존한다.
export class ScopeManager {
    private account: AccountEntry | null = null;
    private readonly events = new Map<string, ParticipationEntry>();
    private readonly seenRevisions = new Map<string, bigint>();

    constructor(
        private readonly queryClient: QueryClient,
        readonly intentKeys: IntentKeyStore = new IntentKeyStore(),
        private readonly report: CleanupReporter = () => undefined,
    ) {}

    get currentAccount(): AccountScope | null {
        return this.account?.scope ?? null;
    }

    participation(eventId: string): ParticipationScope | null {
        return this.events.get(eventId)?.scope ?? null;
    }

    // accountId=null은 로그아웃이다. 같은 계정을 다시 받으면 아무것도 하지 않는다.
    setAccount(accountId: string | null): AccountScope | null {
        if (accountId !== null) requireId(accountId, 'accountId');
        if (this.account?.scope.accountId === accountId) return this.account.scope;
        this.disposeAccount();
        if (accountId === null) return null;
        const lifetime = new Lifetime();
        const scope: AccountScope = {
            accountId,
            signal: lifetime.signal,
            isCurrent: () => lifetime.isCurrent(),
            onDispose: (cleanup) => lifetime.onDispose(cleanup),
        };
        this.account = { scope, lifetime };
        return scope;
    }

    // 본인 참여 정보(memberId, participationRevision)를 받을 때마다 호출한다.
    // 값을 어느 응답에서 받는지는 호출자가 정한다. 여기서는 API를 가정하지 않는다.
    setParticipation(input: {
        eventId: string;
        memberId: string;
        participationRevision: string;
    }): ParticipationResult {
        const account = this.account;
        if (!account) throw new ScopeContractError('계정 범위 없이 참여 범위를 만들 수 없습니다.');
        const eventId = requireId(input.eventId, 'eventId');
        const memberId = requireId(input.memberId, 'memberId');
        const revision = parseBigInt(input.participationRevision, 'participationRevision');

        // 이탈 뒤 늦게 도착한 옛 회차가 범위를 되살리지 못하게, 본 적 있는 가장 높은 회차를 기억한다.
        // 같은 회차로 다시 들어오는 것(그룹 이동 등)은 정상이라 막지 않는다.
        const seenKey = JSON.stringify([account.scope.accountId, eventId, memberId]);
        const seen = this.seenRevisions.get(seenKey);
        const current = this.events.get(eventId);
        if (seen !== undefined && revision < seen) {
            return { change: 'stale', scope: current?.scope ?? null };
        }
        this.seenRevisions.set(seenKey, revision);

        let change: Exclude<ParticipationChange, 'stale'> = 'entered';
        if (current) {
            if (current.scope.memberId !== memberId) {
                change = 'replaced';
            } else {
                const known = BigInt(current.scope.participationRevision);
                if (revision < known) return { change: 'stale', scope: current.scope };
                if (revision === known) return { change: 'unchanged', scope: current.scope };
                change = 'advanced';
            }
            this.disposeEvent(eventId);
        }

        const lifetime = new Lifetime();
        const scope: ParticipationScope = {
            accountId: account.scope.accountId,
            eventId,
            memberId,
            participationRevision: input.participationRevision,
            signal: lifetime.signal,
            isCurrent: () => lifetime.isCurrent(),
            onDispose: (cleanup) => lifetime.onDispose(cleanup),
        };
        this.events.set(eventId, { scope, lifetime });
        // 로그아웃 중에 회차가 올랐어도 복귀 시 옛 회차의 미확인 키가 남지 않게 한다.
        this.intentKeys.keepOnlyRound(
            scope.accountId,
            eventId,
            memberId,
            input.participationRevision,
        );
        return { change, scope };
    }

    // 행사 이탈, 접근 회수(403/404), 소속 이동. 다른 행사와 계정 세션은 유지한다.
    leaveParticipation(eventId: string) {
        const entry = this.events.get(eventId);
        // 범위가 없어도(로그아웃 중이거나 이미 정리됨) 그 계정의 행사 키는 버린다.
        const accountId = entry?.scope.accountId ?? this.account?.scope.accountId;
        this.disposeEvent(eventId);
        if (accountId !== undefined) this.intentKeys.purgeEvent(accountId, eventId);
    }

    // 포그라운드 복귀·재연결 때 현재 계정의 영속 상태를 다시 조회하게 한다.
    // 호출 전에 본인 참여 회차를 먼저 비교해야 한다(fe-be-contract 재연결 순서).
    // 회차가 바뀌었다면 setParticipation이 옛 범위를 이미 폐기한 상태여야 한다.
    revalidate(): Promise<void> {
        if (!this.account) return Promise.resolve();
        return this.queryClient.invalidateQueries({
            queryKey: accountPrefix(this.account.scope.accountId),
        });
    }

    dispose() {
        this.disposeAccount();
    }

    private disposeAccount() {
        const account = this.account;
        if (!account) return;
        // 정리 함수가 setParticipation을 부르더라도 사라지는 계정 아래에 범위가 생기지 않게
        // 계정을 먼저 비운다.
        this.account = null;
        for (const eventId of [...this.events.keys()]) this.disposeEvent(eventId);
        this.finish(account.lifetime, accountPrefix(account.scope.accountId));
    }

    private disposeEvent(eventId: string) {
        const entry = this.events.get(eventId);
        if (!entry) return;
        this.events.delete(eventId);
        this.finish(entry.lifetime, eventPrefix(entry.scope.accountId, eventId));
    }

    // 구독 정리 → 진행 중 조회 취소와 캐시 폐기(removeQueries가 함께 처리한다).
    // 명령(mutation)은 취소하지 않는다. 이미 전송한 명령의 결과는 같은 키로 확인해야 하므로
    // 강제로 끊지 않는다.
    private finish(lifetime: Lifetime, prefix: readonly unknown[]) {
        const errors = lifetime.dispose();
        this.queryClient.removeQueries({ queryKey: prefix });
        if (errors.length > 0) this.report(errors);
    }
}
