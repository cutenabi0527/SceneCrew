import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { ScopeManager } from './scope-manager';
import { IntentKeyStore } from './intent-keys';
import {
    type AccountScope,
    type ParticipationScope,
    type Scope,
    ScopeContractError,
    ScopeDisposedError,
    runInScope,
    scopedKey,
} from './scope';

// 시드를 고정한 무작위 작업 열을 실행하고, 매 단계 독립적으로 유지한 단순 모델과 대조한다.
// 손으로 쓴 사례가 놓치는 조합(전환·이탈·재참여·늦은 응답·키 보관의 순서)을 찾는 용도다.

function mulberry32(seed: number) {
    let a = seed;
    return () => {
        a = (a + 0x6d2b79f5) | 0;
        let t = Math.imul(a ^ (a >>> 15), 1 | a);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

const ACCOUNTS = ['A', 'B', null] as const;
const EVENTS = ['e1', 'e2'];
const MEMBERS = ['m1', 'm1', 'm1', 'm2'];
const INTENTS = ['i1', 'i2'];

interface Tracked {
    scope: Scope;
    cleanups: { calls: number }[];
}

interface Pending {
    scope: Scope;
    resolve: () => void;
    settled: Promise<'ok' | 'disposed' | 'other'>;
    currentAtResolve?: boolean;
}

const flush = async () => {
    for (let i = 0; i < 5; i++) await Promise.resolve();
};

async function run(seed: number, steps: number) {
    const random = mulberry32(seed);
    const pick = <T>(items: readonly T[]): T => items[Math.floor(random() * items.length)];
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: Infinity } },
    });
    const store = new IntentKeyStore({
        createKey: (() => {
            let n = 0;
            return () => `k${++n}`;
        })(),
    });
    const manager = new ScopeManager(client, store);

    // --- 모델 ---
    let account: string | null = null;
    let accountScope: AccountScope | null = null;
    const events = new Map<string, { member: string; rev: bigint; scope: ParticipationScope }>();
    const seen = new Map<string, bigint>();
    const keys = new Set<string>();
    const tracked: Tracked[] = [];
    const pendings: Pending[] = [];
    const log: string[] = [];

    const keyOf = (s: Scope, op: string, intent: string) =>
        'eventId' in s
            ? [s.accountId, s.eventId, s.memberId, s.participationRevision, op, intent].join('|')
            : [s.accountId, '-', '-', '-', op, intent].join('|');
    const dropKeys = (match: (parts: string[]) => boolean) => {
        for (const k of [...keys]) if (match(k.split('|'))) keys.delete(k);
    };
    const track = (scope: Scope) => {
        if (!tracked.some((t) => t.scope === scope)) tracked.push({ scope, cleanups: [] });
    };
    const currentScopes = (): Scope[] => [
        ...(accountScope ? [accountScope] : []),
        ...[...events.values()].map((e) => e.scope),
    ];

    const check = () => {
        expect(manager.currentAccount?.accountId ?? null).toBe(account);
        expect(manager.currentAccount ?? null).toBe(accountScope);
        for (const id of EVENTS) {
            const model = events.get(id);
            const actual = manager.participation(id);
            expect(actual ?? null).toBe(model?.scope ?? null);
        }
        expect(store.size).toBe(keys.size);
        // 모델이 현재라고 보는 범위만 살아 있고, 나머지는 모두 종료·취소되어 있다.
        const live = new Set<Scope>(currentScopes());
        for (const t of tracked) {
            expect(t.scope.isCurrent()).toBe(live.has(t.scope));
            expect(t.scope.signal.aborted).toBe(!live.has(t.scope));
            for (const c of t.cleanups) expect(c.calls).toBe(live.has(t.scope) ? 0 : 1);
        }
        // 캐시에는 현재 범위의 데이터만 있다.
        for (const query of client.getQueryCache().getAll()) {
            const key = query.queryKey as unknown[];
            expect(key[0]).toBe('account');
            expect(key[1]).toBe(account);
            if (key[2] === 'event') {
                const model = events.get(key[3] as string);
                expect(model).toBeDefined();
                expect(key[5]).toBe(model!.member);
                expect(key[7]).toBe(String(model!.rev));
            }
        }
    };

    for (let step = 0; step < steps; step++) {
        const op = Math.floor(random() * 9);
        if (op === 0) {
            const next = pick(ACCOUNTS);
            log.push(`setAccount(${next})`);
            const result = manager.setAccount(next);
            if (next !== account) {
                account = next;
                events.clear();
                accountScope = result;
                if (result) track(result);
                else expect(result).toBeNull();
            } else {
                expect(result).toBe(accountScope);
            }
        } else if (op === 1 || op === 2) {
            const eventId = pick(EVENTS);
            const memberId = pick(MEMBERS);
            const rev = BigInt(Math.floor(random() * 5));
            log.push(`setParticipation(${eventId},${memberId},${rev})`);
            if (!account) {
                expect(() =>
                    manager.setParticipation({
                        eventId,
                        memberId,
                        participationRevision: String(rev),
                    }),
                ).toThrow(ScopeContractError);
            } else {
                const result = manager.setParticipation({
                    eventId,
                    memberId,
                    participationRevision: String(rev),
                });
                const seenKey = [account, eventId, memberId].join('|');
                const known = seen.get(seenKey);
                const current = events.get(eventId);
                if (known !== undefined && rev < known) {
                    expect(result.change).toBe('stale');
                    expect(result.scope).toBe(current?.scope ?? null);
                } else {
                    seen.set(seenKey, rev);
                    if (current && current.member === memberId && rev === current.rev) {
                        expect(result.change).toBe('unchanged');
                        expect(result.scope).toBe(current.scope);
                    } else {
                        const expected = !current
                            ? 'entered'
                            : current.member !== memberId
                              ? 'replaced'
                              : 'advanced';
                        expect(result.change).toBe(expected);
                        const scope = result.scope!;
                        track(scope);
                        events.set(eventId, { member: memberId, rev, scope });
                        dropKeys(
                            (p) =>
                                p[0] === account &&
                                p[1] === eventId &&
                                (p[2] !== memberId || p[3] !== String(rev)),
                        );
                    }
                }
            }
        } else if (op === 3) {
            const eventId = pick(EVENTS);
            log.push(`leave(${eventId})`);
            manager.leaveParticipation(eventId);
            events.delete(eventId);
            if (account) dropKeys((p) => p[0] === account && p[1] === eventId);
        } else if (op === 4) {
            const scopes = currentScopes();
            if (scopes.length === 0) continue;
            const scope = pick(scopes);
            const name = pick(['a', 'b']);
            log.push(`fetch(${name})`);
            let resolve!: () => void;
            const gate = new Promise<void>((r) => (resolve = r));
            const key = scopedKey(scope, name);
            void client
                .fetchQuery({ queryKey: key, queryFn: () => gate.then(() => name) })
                .catch(() => undefined);
            const settled = runInScope(scope, () => gate).then(
                () => 'ok' as const,
                (error) =>
                    error instanceof ScopeDisposedError
                        ? ('disposed' as const)
                        : ('other' as const),
            );
            pendings.push({ scope, resolve, settled });
        } else if (op === 5) {
            if (pendings.length === 0) continue;
            const index = Math.floor(random() * pendings.length);
            const [item] = pendings.splice(index, 1);
            log.push('resolve-late');
            item.currentAtResolve = item.scope.isCurrent();
            item.resolve();
            await flush();
            // 범위 작업의 결과는 해결 시점에 범위가 살아 있을 때만 호출자에게 전달된다.
            expect(await item.settled).toBe(item.currentAtResolve ? 'ok' : 'disposed');
        } else if (op === 6) {
            const scopes = currentScopes();
            if (scopes.length === 0) continue;
            const scope = pick(scopes);
            const intent = pick(INTENTS);
            log.push(`claim(${intent})`);
            const claim = store.claim(scope, 'op', intent, 'body');
            const id = keyOf(scope, 'op', intent);
            expect(claim.status).toBe('active');
            expect('resumed' in claim && claim.resumed).toBe(keys.has(id));
            keys.add(id);
        } else if (op === 7) {
            const scopes = currentScopes();
            if (scopes.length === 0) continue;
            const scope = pick(scopes);
            const intent = pick(INTENTS);
            log.push(`release(${intent})`);
            store.release(scope, 'op', intent);
            keys.delete(keyOf(scope, 'op', intent));
        } else {
            const scopes = currentScopes();
            if (scopes.length === 0) continue;
            const scope = pick(scopes);
            log.push('onDispose');
            const counter = { calls: 0 };
            scope.onDispose(() => {
                counter.calls++;
            });
            tracked.find((t) => t.scope === scope)!.cleanups.push(counter);
        }
        await flush();
        check();
    }
    // 남은 늦은 응답을 모두 해결해도 캐시·범위는 일관된다.
    for (const item of pendings.splice(0)) {
        item.currentAtResolve = item.scope.isCurrent();
        item.resolve();
        await flush();
        expect(await item.settled).toBe(item.currentAtResolve ? 'ok' : 'disposed');
    }
    check();
    manager.dispose();
    for (const t of tracked) {
        expect(t.scope.isCurrent()).toBe(false);
        for (const c of t.cleanups) expect(c.calls).toBe(1);
    }
    expect(client.getQueryCache().getAll()).toHaveLength(0);
    return log;
}

describe('무작위 작업 열에서 모델과 불변식을 유지한다', () => {
    it('300개 시드 × 80단계', async () => {
        for (let seed = 1; seed <= 300; seed++) {
            try {
                await run(seed, 80);
            } catch (error) {
                throw new Error(`seed ${seed}에서 실패: ${(error as Error).message}`, {
                    cause: error,
                });
            }
        }
    }, 60_000);
});
