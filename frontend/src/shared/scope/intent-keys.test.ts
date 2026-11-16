import { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it } from 'vitest';
import { IntentConflictError, IntentKeyStore } from './intent-keys';
import { ScopeManager, type ParticipationResult } from './scope-manager';
import { ScopeDisposedError } from './scope';

// 참여 범위가 실제로 만들어졌는지 확인한다(stale이면 범위가 없을 수 있다).
function entered(result: ParticipationResult) {
    if (result.change === 'stale' || !result.scope) {
        throw new Error('참여 범위가 만들어지지 않았습니다.');
    }
    return result.scope;
}

const HOUR = 60 * 60 * 1000;

describe('같은 의도의 Idempotency-Key 보관', () => {
    let now: number;
    let created: number;
    let store: IntentKeyStore;
    let scopes: ScopeManager;

    beforeEach(() => {
        now = 1_000_000;
        created = 0;
        store = new IntentKeyStore({ now: () => now, createKey: () => `key-${++created}` });
        scopes = new ScopeManager(new QueryClient(), store);
    });

    it('같은 의도/같은 내용의 재전송은 같은 키를 쓴다', () => {
        const a = scopes.setAccount('A')!;
        expect(store.claim(a, 'create-issue', 'form-1', 'body')).toEqual({
            status: 'active',
            key: 'key-1',
            resumed: false,
        });
        expect(store.claim(a, 'create-issue', 'form-1', 'body')).toEqual({
            status: 'active',
            key: 'key-1',
            resumed: true,
        });
        expect(created).toBe(1);
    });

    it('새 의도는 사용자가 명시한 새 intentId에서만 새 키를 받는다', () => {
        const a = scopes.setAccount('A')!;
        store.claim(a, 'create-issue', 'form-1', 'body');
        const next = store.claim(a, 'create-issue', 'form-2', 'body');
        expect(next).toMatchObject({ key: 'key-2', resumed: false });
    });

    it('같은 의도에서 내용이 바뀌면 키를 새로 만들지 않고 거절한다', () => {
        const a = scopes.setAccount('A')!;
        store.claim(a, 'create-issue', 'form-1', 'body');
        expect(() => store.claim(a, 'create-issue', 'form-1', 'changed')).toThrow(
            IntentConflictError,
        );
        expect(created).toBe(1);
    });

    it('계정이 다르면 같은 operation/의도라도 키가 섞이지 않는다', () => {
        const a = scopes.setAccount('A')!;
        const keyA = store.claim(a, 'op', 'same', 'body');
        const b = scopes.setAccount('B')!;
        const keyB = store.claim(b, 'op', 'same', 'body');
        expect(keyA).toMatchObject({ key: 'key-1' });
        expect(keyB).toMatchObject({ key: 'key-2', resumed: false });
    });

    it('A→B→A로 돌아오면 A의 미확인 의도는 같은 키를 이어서 쓴다', () => {
        const first = scopes.setAccount('A')!;
        store.claim(first, 'op', 'same', 'body');
        scopes.setAccount('B');
        const again = scopes.setAccount('A')!;
        expect(store.claim(again, 'op', 'same', 'body')).toMatchObject({
            key: 'key-1',
            resumed: true,
        });
    });

    it('행사가 다르면 같은 operation/의도라도 키가 섞이지 않는다', () => {
        scopes.setAccount('A');
        const e1 = entered(
            scopes.setParticipation({
                eventId: 'e1',
                memberId: 'm',
                participationRevision: '1',
            }),
        );
        const e2 = entered(
            scopes.setParticipation({
                eventId: 'e2',
                memberId: 'm',
                participationRevision: '1',
            }),
        );
        expect(store.claim(e1, 'op', 'same', 'b')).toMatchObject({ key: 'key-1' });
        expect(store.claim(e2, 'op', 'same', 'b')).toMatchObject({ key: 'key-2' });
    });

    describe('24시간', () => {
        it('24시간이 지나도 새 키를 만들지 않고 만료로 알린다', () => {
            const a = scopes.setAccount('A')!;
            store.claim(a, 'op', 'form-1', 'body');
            now += 24 * HOUR;
            expect(store.claim(a, 'op', 'form-1', 'body')).toEqual({ status: 'expired' });
            expect(store.claim(a, 'op', 'form-1', 'body')).toEqual({ status: 'expired' });
            expect(created).toBe(1);
        });

        it('만료 직전(24시간 미만)에는 같은 키로 재전송한다', () => {
            const a = scopes.setAccount('A')!;
            store.claim(a, 'op', 'form-1', 'body');
            now += 24 * HOUR - 1;
            expect(store.claim(a, 'op', 'form-1', 'body')).toMatchObject({
                key: 'key-1',
                resumed: true,
            });
        });

        it('만료된 의도는 내용이 달라져도 새 키로 이어가지 않는다', () => {
            const a = scopes.setAccount('A')!;
            store.claim(a, 'op', 'form-1', 'body');
            now += 25 * HOUR;
            expect(store.claim(a, 'op', 'form-1', 'changed')).toEqual({ status: 'expired' });
            expect(created).toBe(1);
        });

        it('결과를 확인해 release한 뒤 시작한 의도만 새 키를 받는다', () => {
            const a = scopes.setAccount('A')!;
            store.claim(a, 'op', 'form-1', 'body');
            now += 30 * HOUR;
            store.release(a, 'op', 'form-1');
            expect(store.claim(a, 'op', 'form-1', 'body')).toMatchObject({
                key: 'key-2',
                resumed: false,
            });
        });
    });

    describe('행사 이탈/회차 변경', () => {
        it('이탈한 행사의 보관 키만 버리고 다른 행사와 계정 키는 남긴다', () => {
            const account = scopes.setAccount('A')!;
            const e1 = entered(
                scopes.setParticipation({
                    eventId: 'e1',
                    memberId: 'm',
                    participationRevision: '1',
                }),
            );
            const e10 = entered(
                scopes.setParticipation({
                    eventId: 'e10',
                    memberId: 'm',
                    participationRevision: '1',
                }),
            );
            store.claim(account, 'op', 'i', 'b');
            store.claim(e1, 'op', 'i', 'b');
            store.claim(e10, 'op', 'i', 'b');

            scopes.leaveParticipation('e1');

            expect(store.size).toBe(2);
            expect(store.claim(e10, 'op', 'i', 'b')).toMatchObject({ resumed: true });
            expect(store.claim(account, 'op', 'i', 'b')).toMatchObject({ resumed: true });
        });

        it('재참여 회차는 옛 회차의 미확인 의도 키를 이어받지 않는다', () => {
            scopes.setAccount('A');
            const old = entered(
                scopes.setParticipation({
                    eventId: 'e1',
                    memberId: 'm',
                    participationRevision: '1',
                }),
            );
            store.claim(old, 'op', 'i', 'b');
            const next = entered(
                scopes.setParticipation({
                    eventId: 'e1',
                    memberId: 'm',
                    participationRevision: '2',
                }),
            );
            expect(store.size).toBe(0);
            expect(store.claim(next, 'op', 'i', 'b')).toMatchObject({
                key: 'key-2',
                resumed: false,
            });
        });
    });

    describe('계정 전환/로그아웃과 행사 키', () => {
        const join = (revision: string, eventId = 'e1', memberId = 'm') =>
            entered(
                scopes.setParticipation({ eventId, memberId, participationRevision: revision }),
            );

        it('A→B→A로 돌아와 같은 회차에 다시 들어오면 행사 단위 미확인 의도도 같은 키를 쓴다', () => {
            scopes.setAccount('A');
            const before = join('3');
            store.claim(before, 'op', 'i', 'b');

            scopes.setAccount('B');
            scopes.setAccount('A');
            const after = join('3');

            expect(after).not.toBe(before);
            expect(store.claim(after, 'op', 'i', 'b')).toMatchObject({
                key: 'key-1',
                resumed: true,
            });
        });

        it('로그아웃은 보관 키를 지우지 않는다', () => {
            scopes.setAccount('A');
            store.claim(join('3'), 'op', 'i', 'b');
            scopes.setAccount(null);
            expect(store.size).toBe(1);
        });

        it('로그아웃 중에 회차가 올랐다면 복귀 시 옛 회차의 키는 남지 않고 새 키를 받는다', () => {
            scopes.setAccount('A');
            const old = join('3');
            store.claim(old, 'op', 'i', 'b');
            scopes.setAccount(null);

            scopes.setAccount('A');
            const next = join('4');

            expect(store.size).toBe(0);
            expect(store.claim(next, 'op', 'i', 'b')).toMatchObject({
                key: 'key-2',
                resumed: false,
            });
        });

        it('같은 행사라도 회차가 다르면 같은 operation/의도의 키가 섞이지 않는다', () => {
            scopes.setAccount('A');
            const first = join('1');
            store.claim(first, 'op', 'i', 'b');
            scopes.leaveParticipation('e1');
            const second = join('2');
            expect(store.claim(second, 'op', 'i', 'b')).toMatchObject({
                key: 'key-2',
                resumed: false,
            });
        });

        it('로그아웃 상태의 회차 확정은 다른 Member의 옛 키를 버린다', () => {
            scopes.setAccount('A');
            store.claim(join('5', 'e1', 'm1'), 'op', 'i', 'b');
            scopes.setAccount(null);
            scopes.setAccount('A');
            join('1', 'e1', 'm2');
            expect(store.size).toBe(0);
        });

        it('다른 행사의 키는 회차 정리에 영향받지 않는다', () => {
            scopes.setAccount('A');
            store.claim(join('1', 'e2'), 'op', 'i', 'b');
            join('1', 'e1');
            join('2', 'e1');
            expect(store.size).toBe(1);
        });
    });

    describe('보관소 단독 격리(관리자의 정리에 기대지 않는다)', () => {
        // 관리자는 한 행사에 한 회차만 허용하므로, 보관소 자체의 분리는 직접 만든 범위로 검사한다.
        const fake = (revision: string, memberId = 'm') => ({
            accountId: 'A',
            eventId: 'e1',
            memberId,
            participationRevision: revision,
            signal: new AbortController().signal,
            isCurrent: () => true,
            onDispose: () => () => undefined,
        });

        it('회차가 다르면 같은 operation/의도라도 서로 다른 키를 받는다', () => {
            expect(store.claim(fake('1'), 'op', 'i', 'b')).toMatchObject({ key: 'key-1' });
            expect(store.claim(fake('2'), 'op', 'i', 'b')).toMatchObject({
                key: 'key-2',
                resumed: false,
            });
            expect(store.claim(fake('1'), 'op', 'i', 'b')).toMatchObject({
                key: 'key-1',
                resumed: true,
            });
        });

        it('Member가 다르면 같은 회차 숫자라도 키가 섞이지 않는다', () => {
            store.claim(fake('1', 'm1'), 'op', 'i', 'b');
            expect(store.claim(fake('1', 'm2'), 'op', 'i', 'b')).toMatchObject({
                key: 'key-2',
                resumed: false,
            });
        });

        it('pending은 같은 행사의 다른 회차 의도를 보여주지 않는다', () => {
            store.claim(fake('1'), 'op', 'i', 'b');
            expect(store.pending(fake('2'))).toEqual([]);
            expect(store.pending(fake('1'))).toHaveLength(1);
        });
    });

    describe('미확인 의도 목록', () => {
        it('현재 범위의 의도만 보이고 결과를 확인(release)하면 사라진다', () => {
            const a = scopes.setAccount('A')!;
            const e1 = entered(
                scopes.setParticipation({
                    eventId: 'e1',
                    memberId: 'm',
                    participationRevision: '1',
                }),
            );
            store.claim(a, 'account-op', 'x', 'b');
            store.claim(e1, 'event-op', 'y', 'b');

            expect(store.pending(a)).toEqual([
                { operation: 'account-op', intentId: 'x', expired: false, createdAt: now },
            ]);
            expect(store.pending(e1)).toEqual([
                { operation: 'event-op', intentId: 'y', expired: false, createdAt: now },
            ]);

            store.release(e1, 'event-op', 'y');
            expect(store.pending(e1)).toEqual([]);
        });

        it('다른 계정/다른 회차의 의도는 보이지 않는다', () => {
            scopes.setAccount('A');
            const round1 = entered(
                scopes.setParticipation({
                    eventId: 'e1',
                    memberId: 'm',
                    participationRevision: '1',
                }),
            );
            store.claim(round1, 'op', 'i', 'b');
            const round2 = entered(
                scopes.setParticipation({
                    eventId: 'e1',
                    memberId: 'm',
                    participationRevision: '2',
                }),
            );
            const b = scopes.setAccount('B')!;
            expect(store.pending(round2)).toEqual([]);
            expect(store.pending(b)).toEqual([]);
        });

        it('24시간이 지난 의도는 expired로 표시되고 목록에서 사라지지 않는다', () => {
            const a = scopes.setAccount('A')!;
            store.claim(a, 'op', 'i', 'b');
            now += 24 * HOUR;
            expect(store.pending(a)).toMatchObject([{ operation: 'op', expired: true }]);
        });

        it('A→B→A로 돌아오면 화면 상태 없이도 미확인 의도를 찾아 이어갈 수 있다', () => {
            const first = scopes.setAccount('A')!;
            store.claim(first, 'op', 'form-7', 'body');
            scopes.setAccount('B');
            const again = scopes.setAccount('A')!;
            const [found] = store.pending(again);
            expect(store.claim(again, found.operation, found.intentId, 'body')).toMatchObject({
                key: 'key-1',
                resumed: true,
            });
        });
    });

    it('종료된 범위의 옛 화면은 새 키를 만들 수 없다', () => {
        const a = scopes.setAccount('A')!;
        scopes.setAccount('B');
        expect(() => store.claim(a, 'op', 'i', 'b')).toThrow(ScopeDisposedError);
        expect(created).toBe(0);
    });

    it('기본 키 생성기는 호출마다 서로 다른 값을 만든다', () => {
        const real = new IntentKeyStore();
        const a = scopes.setAccount('A')!;
        const one = real.claim(a, 'op', 'i1', 'b');
        const two = real.claim(a, 'op', 'i2', 'b');
        expect(one).toMatchObject({ status: 'active' });
        expect(two).toMatchObject({ status: 'active' });
        expect(one).not.toEqual(two);
    });
});
