// 계정·행사 참여 회차 범위의 타입, query key 규약, 수명.
// 인증 transport(쿠키/Bearer)와 무관하다. 식별자와 participationRevision은 호출자가 넘긴다.

export class ScopeContractError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'ScopeContractError';
    }
}

// 범위가 폐기된 뒤 도착한 응답/등록은 이 오류로 거절한다. 새 범위의 화면·캐시에 닿지 않는다.
export class ScopeDisposedError extends Error {
    constructor() {
        super('범위가 종료되어 결과를 사용하지 않습니다.');
        this.name = 'ScopeDisposedError';
    }
}

// BIGINT(participationRevision 등)는 10진 문자열로만 받는다(fe-be-contract).
const BIGINT_PATTERN = /^(0|[1-9][0-9]*)$/;
// DB의 BIGINT는 부호 있는 64비트다. 넘는 값은 계약 위반이며, 긴 숫자열 변환 비용도 막는다.
const MAX_BIGINT = 9223372036854775807n;
const MAX_BIGINT_DIGITS = 19;

export function parseBigInt(value: unknown, name: string): bigint {
    if (typeof value !== 'string' || !BIGINT_PATTERN.test(value)) {
        throw new ScopeContractError(`${name}는 10진 문자열이어야 합니다.`);
    }
    // 변환 전에 길이로 먼저 거른다. 부호 있는 64비트의 최대값은 19자리다.
    if (value.length > MAX_BIGINT_DIGITS) {
        throw new ScopeContractError(`${name}가 BIGINT(64비트) 범위를 넘습니다.`);
    }
    const parsed = BigInt(value);
    if (parsed > MAX_BIGINT) {
        throw new ScopeContractError(`${name}가 BIGINT(64비트) 범위를 넘습니다.`);
    }
    return parsed;
}

export function requireId(value: unknown, name: string): string {
    if (typeof value !== 'string' || value.trim() === '') {
        throw new ScopeContractError(`${name}는 비어 있지 않은 문자열이어야 합니다.`);
    }
    return value;
}

export interface ScopeLifetime {
    // 범위가 끝나면 abort된다. 조회 요청에 넘겨 늦은 응답 수신을 멈춘다.
    readonly signal: AbortSignal;
    // 아직 이 범위가 현재 범위인지 확인한다.
    isCurrent(): boolean;
    // 범위가 끝날 때 실행할 정리(SSE/WS 닫기 등). 반환한 함수로 등록을 취소한다.
    // 이미 끝난 범위에 등록하면 즉시 실행하고 ScopeDisposedError를 던진다.
    onDispose(cleanup: () => void): () => void;
}

export interface AccountScope extends ScopeLifetime {
    readonly accountId: string;
}

export interface ParticipationScope extends ScopeLifetime {
    readonly accountId: string;
    readonly eventId: string;
    readonly memberId: string;
    // 10진 문자열. 비교는 BigInt로 한다.
    readonly participationRevision: string;
}

export type Scope = AccountScope | ParticipationScope;

// 수명 구현. 정리 함수가 던져도 나머지를 계속 실행하고, 중복 dispose는 한 번만 처리한다.
export class Lifetime implements ScopeLifetime {
    private readonly controller = new AbortController();
    private readonly cleanups = new Set<() => void>();
    private disposed = false;

    get signal() {
        return this.controller.signal;
    }

    isCurrent() {
        return !this.disposed;
    }

    onDispose(cleanup: () => void) {
        if (this.disposed) {
            // 늦게 등록한 구독도 남기지 않고 바로 닫은 뒤 호출자에게 알린다.
            try {
                cleanup();
            } catch {
                // 정리 실패보다 범위가 끝났다는 사실을 우선 알린다.
            }
            throw new ScopeDisposedError();
        }
        this.cleanups.add(cleanup);
        return () => {
            this.cleanups.delete(cleanup);
        };
    }

    // 정리 중 발생한 오류 목록을 반환한다. 이미 폐기됐으면 빈 목록이다.
    dispose(): unknown[] {
        if (this.disposed) return [];
        this.disposed = true;
        this.controller.abort(new ScopeDisposedError());
        const pending = [...this.cleanups];
        this.cleanups.clear();
        const failures: unknown[] = [];
        for (const cleanup of pending) {
            try {
                cleanup();
            } catch (error) {
                failures.push(error);
            }
        }
        return failures;
    }
}

// 범위 안에서 실행한 작업의 결과는 범위가 끝났다면 호출자에게 전달하지 않는다.
// 조회는 signal을 요청에 넘겨 취소한다. 명령은 signal을 요청에 넘기지 않는다
// (전송 후 취소하면 처리 여부를 알 수 없다). 결과만 새 범위에서 차단한다.
export async function runInScope<T>(
    scope: ScopeLifetime,
    task: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
    if (!scope.isCurrent()) throw new ScopeDisposedError();
    try {
        const value = await task(scope.signal);
        if (!scope.isCurrent()) throw new ScopeDisposedError();
        return value;
    } catch (error) {
        if (!scope.isCurrent()) throw new ScopeDisposedError();
        throw error;
    }
}

// query key 규약. 모든 범위 데이터는 이 접두로 시작한다.
//   계정 데이터:  ['account', accountId, ...]
//   행사 데이터:  ['account', accountId, 'event', eventId, 'member', memberId, 'round', rev, ...]
// 접두가 다르면 다른 계정·다른 회차의 캐시와 만나지 않는다. 접두 단위로 취소·폐기한다.
export type QueryKey = readonly unknown[];

export function accountPrefix(accountId: string): QueryKey {
    return ['account', accountId];
}

export function eventPrefix(accountId: string, eventId: string): QueryKey {
    return ['account', accountId, 'event', eventId];
}

export function participationPrefix(scope: ParticipationScope): QueryKey {
    return [
        ...eventPrefix(scope.accountId, scope.eventId),
        'member',
        scope.memberId,
        'round',
        scope.participationRevision,
    ];
}

export function scopedKey(scope: Scope, ...parts: readonly unknown[]): QueryKey {
    const prefix = 'eventId' in scope ? participationPrefix(scope) : accountPrefix(scope.accountId);
    return [...prefix, ...parts];
}
