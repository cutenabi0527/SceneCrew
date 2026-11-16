import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ScopeManager, type ParticipationResult } from './scope-manager';
import {
    ScopeContractError,
    ScopeDisposedError,
    participationPrefix,
    runInScope,
    scopedKey,
} from './scope';

// 참여 범위가 실제로 만들어졌는지 확인한다(stale이면 범위가 없을 수 있다).
function entered(result: ParticipationResult) {
    if (result.change === 'stale' || !result.scope) {
        throw new Error('참여 범위가 만들어지지 않았습니다.');
    }
    return result.scope;
}

const round = (eventId: string, revision: string, memberId = 'm1') => ({
    eventId,
    memberId,
    participationRevision: revision,
});

function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((r) => (resolve = r));
    return { promise, resolve };
}

describe('계정/참여 회차 범위 수명', () => {
    let client: QueryClient;
    let scopes: ScopeManager;
    const report = vi.fn();

    beforeEach(() => {
        client = new QueryClient({
            defaultOptions: { queries: { retry: false, gcTime: Infinity } },
        });
        report.mockClear();
        scopes = new ScopeManager(client, undefined, report);
    });
    afterEach(() => {
        scopes.dispose();
        client.clear();
    });

    describe('A→B 계정 전환', () => {
        it('A의 진행 중 조회는 취소되고 늦은 응답이 B의 캐시에 닿지 않는다', async () => {
            const a = scopes.setAccount('A')!;
            const late = deferred<string>();
            const keyA = scopedKey(a, 'profile');
            let aborted = false;
            const fetching = client
                .fetchQuery({
                    queryKey: keyA,
                    queryFn: ({ signal }) => {
                        signal.addEventListener('abort', () => (aborted = true));
                        return late.promise;
                    },
                })
                .catch(() => 'cancelled');

            const b = scopes.setAccount('B')!;
            late.resolve('A의 데이터');
            await fetching;

            expect(aborted).toBe(true);
            expect(client.getQueryData(keyA)).toBeUndefined();
            expect(client.getQueryCache().findAll({ queryKey: ['account', 'A'] })).toHaveLength(0);
            expect(client.getQueryData(scopedKey(b, 'profile'))).toBeUndefined();
        });

        it('범위 작업의 결과는 전환 뒤에 도착하면 호출자에게 전달하지 않는다', async () => {
            const a = scopes.setAccount('A')!;
            const late = deferred<string>();
            const result = runInScope(a, () => late.promise);
            scopes.setAccount('B');
            late.resolve('A의 응답');
            await expect(result).rejects.toBeInstanceOf(ScopeDisposedError);
        });

        it('이미 완료되어 결과를 넘기기 직전인 작업도 전환 뒤에는 전달하지 않는다', async () => {
            const a = scopes.setAccount('A')!;
            const result = runInScope(a, async () => '이미 끝난 응답');
            scopes.setAccount('B');
            await expect(result).rejects.toBeInstanceOf(ScopeDisposedError);
        });

        it('작업이 실패해도 전환 뒤에는 옛 계정의 오류를 새 화면에 알리지 않는다', async () => {
            const a = scopes.setAccount('A')!;
            const late = deferred<string>();
            const result = runInScope(a, () =>
                late.promise.then(() => Promise.reject(new Error('A의 실패'))),
            );
            scopes.setAccount('B');
            late.resolve('x');
            await expect(result).rejects.toBeInstanceOf(ScopeDisposedError);
        });

        it('옛 구독은 한 번 정리되고, 전환 뒤 늦게 등록한 구독은 즉시 닫힌다', () => {
            const a = scopes.setAccount('A')!;
            const close = vi.fn();
            a.onDispose(close);
            scopes.setAccount('B');
            expect(close).toHaveBeenCalledTimes(1);
            const lateClose = vi.fn();
            expect(() => a.onDispose(lateClose)).toThrow(ScopeDisposedError);
            expect(lateClose).toHaveBeenCalledTimes(1);
        });

        it('행사 참여 범위의 구독과 요청도 계정과 함께 종료된다', () => {
            scopes.setAccount('A');
            const scope = entered(scopes.setParticipation(round('e1', '1')));
            const close = vi.fn();
            scope.onDispose(close);
            scopes.setAccount('B');
            expect(close).toHaveBeenCalledTimes(1);
            expect(scope.signal.aborted).toBe(true);
            expect(scopes.participation('e1')).toBeNull();
        });

        it('전환한 계정 B는 A의 참여 범위를 물려받지 않는다', () => {
            scopes.setAccount('A');
            scopes.setParticipation(round('e1', '1'));
            scopes.setAccount('B');
            expect(scopes.participation('e1')).toBeNull();
            scopes.setAccount(null);
            expect(() => scopes.setParticipation(round('e1', '1'))).toThrow(ScopeContractError);
        });

        it('같은 계정을 다시 받으면 범위와 구독을 유지한다', () => {
            const first = scopes.setAccount('A')!;
            const close = vi.fn();
            first.onDispose(close);
            expect(scopes.setAccount('A')).toBe(first);
            expect(close).not.toHaveBeenCalled();
            expect(first.isCurrent()).toBe(true);
        });
    });

    describe('로그아웃/중복 정리', () => {
        it('로그아웃을 여러 번 해도 정리는 한 번만 실행한다', () => {
            const a = scopes.setAccount('A')!;
            const close = vi.fn();
            a.onDispose(close);
            scopes.setAccount(null);
            scopes.setAccount(null);
            scopes.dispose();
            expect(close).toHaveBeenCalledTimes(1);
            expect(scopes.currentAccount).toBeNull();
        });

        it('정리 함수 하나가 던져도 나머지는 실행하고 오류를 보고한다', () => {
            const a = scopes.setAccount('A')!;
            const boom = new Error('close failed');
            const after = vi.fn();
            a.onDispose(() => {
                throw boom;
            });
            a.onDispose(after);
            scopes.setAccount(null);
            expect(after).toHaveBeenCalledTimes(1);
            expect(report).toHaveBeenCalledWith([boom]);
            expect(client.getQueryCache().findAll()).toHaveLength(0);
        });

        it('등록을 취소한 정리는 실행하지 않는다', () => {
            const a = scopes.setAccount('A')!;
            const close = vi.fn();
            const unregister = a.onDispose(close);
            unregister();
            scopes.setAccount(null);
            expect(close).not.toHaveBeenCalled();
        });
    });

    describe('행사 이탈/재참여', () => {
        it('한 행사를 떠나도 다른 행사와 계정 데이터는 남는다', () => {
            const account = scopes.setAccount('A')!;
            const e1 = entered(scopes.setParticipation(round('e1', '1')));
            const e2 = entered(scopes.setParticipation(round('e2', '4')));
            client.setQueryData(scopedKey(account, 'profile'), 'p');
            client.setQueryData(scopedKey(e1, 'tasks'), 't1');
            client.setQueryData(scopedKey(e2, 'tasks'), 't2');
            const close1 = vi.fn();
            const close2 = vi.fn();
            e1.onDispose(close1);
            e2.onDispose(close2);

            scopes.leaveParticipation('e1');

            expect(client.getQueryData(scopedKey(e1, 'tasks'))).toBeUndefined();
            expect(client.getQueryData(scopedKey(e2, 'tasks'))).toBe('t2');
            expect(client.getQueryData(scopedKey(account, 'profile'))).toBe('p');
            expect(close1).toHaveBeenCalledTimes(1);
            expect(close2).not.toHaveBeenCalled();
            expect(e2.isCurrent()).toBe(true);
            expect(account.isCurrent()).toBe(true);
        });

        it('재참여로 회차가 오르면 옛 회차의 배정/확인 캐시를 복원하지 않는다', () => {
            scopes.setAccount('A');
            const first = entered(scopes.setParticipation(round('e1', '1')));
            client.setQueryData(scopedKey(first, 'my-assignment'), '옛 배정');
            scopes.leaveParticipation('e1');

            const second = scopes.setParticipation(round('e1', '2'));

            expect(second.change).toBe('entered');
            expect(client.getQueryData(scopedKey(second.scope!, 'my-assignment'))).toBeUndefined();
            expect(client.getQueryData(scopedKey(first, 'my-assignment'))).toBeUndefined();
            expect(first.isCurrent()).toBe(false);
        });

        it('이탈하지 않고 회차만 오르면 옛 회차를 폐기하고 새 키를 쓴다', () => {
            scopes.setAccount('A');
            const first = entered(scopes.setParticipation(round('e1', '1')));
            client.setQueryData(scopedKey(first, 'tasks'), '옛 업무');
            const close = vi.fn();
            first.onDispose(close);

            const next = scopes.setParticipation(round('e1', '2'));

            expect(next.change).toBe('advanced');
            expect(close).toHaveBeenCalledTimes(1);
            expect(client.getQueryData(scopedKey(first, 'tasks'))).toBeUndefined();
            expect(participationPrefix(next.scope!)).not.toEqual(participationPrefix(first));
        });

        it('낮은 회차나 같은 회차를 받으면 현재 범위를 건드리지 않는다', () => {
            scopes.setAccount('A');
            const current = entered(scopes.setParticipation(round('e1', '10')));
            const close = vi.fn();
            current.onDispose(close);
            expect(scopes.setParticipation(round('e1', '10'))).toEqual({
                change: 'unchanged',
                scope: current,
            });
            expect(scopes.setParticipation(round('e1', '9'))).toEqual({
                change: 'stale',
                scope: current,
            });
            expect(close).not.toHaveBeenCalled();
        });

        it('이탈한 뒤 늦게 도착한 더 낮은 회차는 범위를 되살리지 못한다', () => {
            scopes.setAccount('A');
            entered(scopes.setParticipation(round('e1', '4')));
            scopes.leaveParticipation('e1');

            const late = scopes.setParticipation(round('e1', '3'));

            expect(late).toEqual({ change: 'stale', scope: null });
            expect(scopes.participation('e1')).toBeNull();
        });

        it('이탈한 뒤 같은 회차로 다시 들어오는 것은 정상이다(그룹 이동 등)', () => {
            scopes.setAccount('A');
            const first = entered(scopes.setParticipation(round('e1', '4')));
            scopes.leaveParticipation('e1');

            const again = scopes.setParticipation(round('e1', '4'));

            expect(again.change).toBe('entered');
            expect(again.scope).not.toBe(first);
            expect(again.scope?.isCurrent()).toBe(true);
        });

        it('본 회차의 기억은 계정/Member별이라 다른 계정의 낮은 회차를 막지 않는다', () => {
            scopes.setAccount('A');
            entered(scopes.setParticipation(round('e1', '9')));
            scopes.setAccount('B');
            expect(scopes.setParticipation(round('e1', '1')).change).toBe('entered');
            scopes.leaveParticipation('e1');
            expect(scopes.setParticipation(round('e1', '2', 'other-member')).change).toBe(
                'entered',
            );
        });

        it('로그아웃을 거쳐도 본 회차를 기억해 옛 회차로 되돌아가지 않는다', () => {
            scopes.setAccount('A');
            entered(scopes.setParticipation(round('e1', '5')));
            scopes.setAccount(null);
            scopes.setAccount('A');
            expect(scopes.setParticipation(round('e1', '4')).change).toBe('stale');
        });

        it('행사 범위의 정리 함수가 다른 참여 범위를 만들려 해도 사라지는 계정 아래에 남지 않는다', () => {
            scopes.setAccount('A');
            const e0 = entered(scopes.setParticipation(round('e0', '1')));
            let attempted: unknown;
            e0.onDispose(() => {
                try {
                    scopes.setParticipation(round('zombie', '1'));
                } catch (error) {
                    attempted = error;
                }
            });
            scopes.setAccount(null);
            expect(attempted).toBeInstanceOf(ScopeContractError);
            expect(scopes.participation('zombie')).toBeNull();
            expect(scopes.participation('e0')).toBeNull();
        });

        it('행사 범위 정리 함수가 계정 전환을 부르는 경우에도 새 계정 범위가 살아남는다', () => {
            scopes.setAccount('A');
            const scope = entered(scopes.setParticipation(round('e1', '1')));
            scope.onDispose(() => {
                scopes.setAccount('B');
            });
            scopes.setAccount(null);
            expect(scopes.currentAccount?.accountId).toBe('B');
            expect(scopes.currentAccount?.isCurrent()).toBe(true);
        });

        it('회차는 문자열 정수로 BigInt 비교한다(자릿수가 큰 값 포함)', () => {
            scopes.setAccount('A');
            scopes.setParticipation(round('e1', '9007199254740993'));
            expect(scopes.setParticipation(round('e1', '9007199254740992')).change).toBe('stale');
            expect(scopes.setParticipation(round('e1', '9007199254740994')).change).toBe(
                'advanced',
            );
            expect(scopes.setParticipation(round('e1', '10')).change).toBe('stale');
        });

        it.each([1, '01', '-1', '1.5', ' 1', '', null, undefined, 'abc'])(
            '계약에 맞지 않는 회차 %j는 범위를 만들지 않는다',
            (bad) => {
                scopes.setAccount('A');
                expect(() =>
                    scopes.setParticipation({
                        eventId: 'e1',
                        memberId: 'm1',
                        participationRevision: bad as string,
                    }),
                ).toThrow(ScopeContractError);
                expect(scopes.participation('e1')).toBeNull();
            },
        );

        it('Member가 바뀌면 옛 범위를 폐기하고 교체한다', () => {
            scopes.setAccount('A');
            const old = entered(scopes.setParticipation(round('e1', '5', 'm1')));
            const next = scopes.setParticipation(round('e1', '1', 'm2'));
            expect(next.change).toBe('replaced');
            expect(old.isCurrent()).toBe(false);
        });

        it('권한 회수(이탈) 중 진행하던 조회가 늦게 돌아와도 캐시를 되살리지 않는다', async () => {
            scopes.setAccount('A');
            const scope = entered(scopes.setParticipation(round('e1', '1')));
            const late = deferred<string>();
            const key = scopedKey(scope, 'tasks');
            const fetching = client
                .fetchQuery({ queryKey: key, queryFn: () => late.promise })
                .catch(() => 'cancelled');
            scopes.leaveParticipation('e1');
            late.resolve('회수 전 업무');
            await fetching;
            expect(client.getQueryData(key)).toBeUndefined();
        });
    });

    describe('동시 요청 취소', () => {
        it('같은 범위에서 진행 중인 여러 조회가 모두 취소된다', async () => {
            const a = scopes.setAccount('A')!;
            const pending = ['x', 'y', 'z'].map((name) => {
                const never = deferred<string>();
                return client
                    .fetchQuery({ queryKey: scopedKey(a, name), queryFn: () => never.promise })
                    .then(
                        () => 'done',
                        () => 'cancelled',
                    );
            });
            scopes.setAccount('B');
            expect(await Promise.all(pending)).toEqual(['cancelled', 'cancelled', 'cancelled']);
            expect(client.getQueryCache().findAll()).toHaveLength(0);
        });
    });

    describe('명령은 범위 전환으로 끊지 않는다', () => {
        it('전송한 명령은 다시 보내지 않고, 결과만 새 범위로 넘기지 않는다', async () => {
            const a = scopes.setAccount('A')!;
            const reply = deferred<string>();
            const send = vi.fn(() => reply.promise);
            // 명령은 signal을 요청에 넘기지 않는다(전송 뒤 취소하면 처리 여부를 알 수 없다).
            const result = runInScope(a, () => send());
            scopes.setAccount('B');
            reply.resolve('처리됨');
            await expect(result).rejects.toBeInstanceOf(ScopeDisposedError);
            expect(send).toHaveBeenCalledTimes(1);
            expect(send.mock.calls[0]).toEqual([]);
        });
    });

    describe('포그라운드 복귀/재연결', () => {
        it('현재 계정의 활성 조회만 다시 가져온다', async () => {
            const a = scopes.setAccount('A')!;
            const fetchA = vi.fn().mockResolvedValue('v');
            const observer = new QueryObserver(client, {
                queryKey: scopedKey(a, 'notifications'),
                queryFn: fetchA,
                staleTime: Infinity,
            });
            const unsubscribe = observer.subscribe(() => undefined);
            await vi.waitFor(() => expect(observer.getCurrentResult().isSuccess).toBe(true));

            await scopes.revalidate();

            expect(fetchA).toHaveBeenCalledTimes(2);
            unsubscribe();
        });

        it('로그아웃 상태에서는 아무것도 요청하지 않는다', async () => {
            const spy = vi.spyOn(client, 'invalidateQueries');
            await scopes.revalidate();
            expect(spy).not.toHaveBeenCalled();
        });

        it('계정 전환 뒤 revalidate는 옛 계정의 조회를 건드리지 않는다', async () => {
            const a = scopes.setAccount('A')!;
            const fetchA = vi.fn().mockResolvedValue('v');
            const observer = new QueryObserver(client, {
                queryKey: scopedKey(a, 'notifications'),
                queryFn: fetchA,
                staleTime: Infinity,
            });
            const unsubscribe = observer.subscribe(() => undefined);
            await vi.waitFor(() => expect(observer.getCurrentResult().isSuccess).toBe(true));
            scopes.setAccount('B');
            await scopes.revalidate();
            expect(fetchA).toHaveBeenCalledTimes(1);
            unsubscribe();
        });
    });

    describe('식별자 검증', () => {
        it.each(['', '  ', 1, undefined])('계정 식별자 %j는 거절한다', (bad) => {
            expect(() => scopes.setAccount(bad as string)).toThrow(ScopeContractError);
            expect(scopes.currentAccount).toBeNull();
        });
    });
});
