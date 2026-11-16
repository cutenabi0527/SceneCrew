import { type Scope, ScopeDisposedError, requireId } from './scope';

// 같은 사용자 의도의 재전송에 같은 Idempotency-Key를 쓰도록 보관한다(fe-be-contract 키 보관).
//
// 메모리에만 둔다. 새로고침이나 앱 재시작 뒤에는 키를 잃는다. 키로 결과를 되살리려면
// 같은 본문도 다시 보내야 하는데, 본문(운영 내용)을 브라우저 저장소에 남기는 것은 BE 요구도
// 없고 195 범위도 아니다. 키를 잃은 의도는 새 키로 재전송하지 않고 현재 상태 조회로 확인한다.
//
// 보관 단위: 계정 범위는 Account·operation·의도, 행사 범위는 Account·행사·Member·참여 회차·
// operation·의도. 회차가 키에 들어 있어 옛 회차의 키는 새 회차에서 접근할 수 없다.

const DAY_MS = 24 * 60 * 60 * 1000;

export class IntentConflictError extends Error {
    constructor() {
        super('같은 의도에 다른 요청 내용을 보낼 수 없습니다. 새 의도로 시작해주세요.');
        this.name = 'IntentConflictError';
    }
}

export type IntentClaim =
    | { status: 'reconcile' }
    // resumed=true면 이미 보낸 의도의 재전송이다. 같은 키·같은 내용을 쓴다.
    | { status: 'active'; key: string; resumed: boolean }
    // 24시간이 지났다. 새 키를 만들지 않는다. 현재 상태를 조회해 결과를 확인한 뒤 release한다.
    | { status: 'expired' };

// 결과를 아직 확인하지 못한 의도. 화면이 사라진 뒤에도 찾아서 확인할 수 있게 한다.
export interface PendingIntent {
    operation: string;
    intentId: string;
    expired: boolean;
    createdAt: number;
}

interface Entry {
    recovery?: 'state-query' | 'expired';
    accountId: string;
    eventId: string | null;
    memberId: string | null;
    revision: string | null;
    operation: string;
    intentId: string;
    key: string;
    fingerprint: string;
    createdAt: number;
}

interface Options {
    ttlMs?: number;
    now?: () => number;
    createKey?: () => string;
}

export class IntentKeyStore {
    private readonly entries = new Map<string, Entry>();
    private readonly ttlMs: number;
    private readonly now: () => number;
    private readonly createKey: () => string;

    constructor(options: Options = {}) {
        this.ttlMs = options.ttlMs ?? DAY_MS;
        this.now = options.now ?? Date.now;
        this.createKey = options.createKey ?? (() => globalThis.crypto.randomUUID());
    }

    // intentId는 사용자가 새 의도를 시작할 때 호출자가 만든 값이다(예: 입력 화면 인스턴스).
    // fingerprint는 요청 내용을 대표하는 문자열이다. 같은 의도에서 바뀌면 거절한다.
    claim(scope: Scope, operation: string, intentId: string, fingerprint: string): IntentClaim {
        if (!scope.isCurrent()) throw new ScopeDisposedError();
        const id = this.id(scope, operation, intentId);
        const entry = this.entries.get(id);
        if (!entry) {
            const created: Entry = {
                ...this.location(scope),
                operation,
                intentId,
                key: this.createKey(),
                fingerprint,
                createdAt: this.now(),
            };
            this.entries.set(id, created);
            return { status: 'active', key: created.key, resumed: false };
        }
        // 만료된 항목은 지우지 않는다. 지우면 같은 의도가 새 키로 다시 실행될 수 있다.
        if (this.isExpired(entry)) return { status: 'expired' };
        if (entry.fingerprint !== fingerprint) throw new IntentConflictError();
        if (entry.recovery === 'state-query') return { status: 'reconcile' };
        return { status: 'active', key: entry.key, resumed: true };
    }

    // 결과가 확인됐거나 사용자가 취소했을 때만 호출한다.
    release(scope: Scope, operation: string, intentId: string) {
        this.entries.delete(this.id(scope, operation, intentId));
    }

    // 서버가 불명/만료를 알린 의도는 같은 키도 재전송하지 않고 조회로 복구한다.
    requireQuery(scope: Scope, operation: string, intentId: string, expired: boolean) {
        const entry = this.entries.get(this.id(scope, operation, intentId));
        if (entry)
            entry.recovery = expired || entry.recovery === 'expired' ? 'expired' : 'state-query';
    }

    // 이 범위에서 결과를 아직 확인하지 못한 의도 목록. 다른 계정·행사·회차의 의도는 보이지 않는다.
    pending(scope: Scope): PendingIntent[] {
        const where = this.location(scope);
        return [...this.entries.values()]
            .filter((entry) => sameLocation(entry, where))
            .map((entry) => ({
                operation: entry.operation,
                intentId: entry.intentId,
                expired: this.isExpired(entry),
                createdAt: entry.createdAt,
            }));
    }

    // 행사 이탈·접근 회수. 그 행사의 모든 회차 키를 버린다.
    purgeEvent(accountId: string, eventId: string) {
        this.deleteWhere((entry) => entry.accountId === accountId && entry.eventId === eventId);
    }

    // 참여 회차가 확정·변경됐을 때, 같은 행사의 다른 Member·다른 회차 키를 버린다.
    // 로그아웃 중에 회차가 올랐어도 복귀 시 옛 회차의 키가 남지 않게 한다.
    keepOnlyRound(accountId: string, eventId: string, memberId: string, revision: string) {
        this.deleteWhere(
            (entry) =>
                entry.accountId === accountId &&
                entry.eventId === eventId &&
                (entry.memberId !== memberId || entry.revision !== revision),
        );
    }

    get size() {
        return this.entries.size;
    }

    private isExpired(entry: Entry) {
        return entry.recovery === 'expired' || this.now() - entry.createdAt >= this.ttlMs;
    }

    private deleteWhere(match: (entry: Entry) => boolean) {
        for (const [id, entry] of this.entries) {
            if (match(entry)) this.entries.delete(id);
        }
    }

    private location(scope: Scope) {
        return 'eventId' in scope
            ? {
                  accountId: scope.accountId,
                  eventId: scope.eventId,
                  memberId: scope.memberId,
                  revision: scope.participationRevision,
              }
            : { accountId: scope.accountId, eventId: null, memberId: null, revision: null };
    }

    private id(scope: Scope, operation: string, intentId: string) {
        const { accountId, eventId, memberId, revision } = this.location(scope);
        return JSON.stringify([
            accountId,
            eventId,
            memberId,
            revision,
            requireId(operation, 'operation'),
            requireId(intentId, 'intentId'),
        ]);
    }
}

type Location = Pick<Entry, 'accountId' | 'eventId' | 'memberId' | 'revision'>;

function sameLocation(entry: Location, where: Location) {
    return (
        entry.accountId === where.accountId &&
        entry.eventId === where.eventId &&
        entry.memberId === where.memberId &&
        entry.revision === where.revision
    );
}
