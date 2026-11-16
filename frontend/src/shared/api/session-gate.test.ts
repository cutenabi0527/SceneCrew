import { describe, expect, it, vi } from 'vitest';
import { SessionGate } from './session-gate';

function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (reason: unknown) => void;
    const promise = new Promise<T>((res, rej) => {
        resolve = res;
        reject = rej;
    });
    return { promise, resolve, reject };
}

describe('세션 복구 게이트', () => {
    it('같은 시점의 여러 401은 refresh를 한 번만 보낸다', async () => {
        const wait = deferred<'refreshed'>();
        const refresh = vi.fn(() => wait.promise);
        const gate = new SessionGate(refresh);
        const results = [gate.recover(), gate.recover(), gate.recover()];
        wait.resolve('refreshed');
        expect(await Promise.all(results)).toEqual(['refreshed', 'refreshed', 'refreshed']);
        expect(refresh).toHaveBeenCalledTimes(1);
    });

    it('복구가 끝난 뒤의 새 401은 새 refresh를 보낸다', async () => {
        const refresh = vi.fn().mockResolvedValue('refreshed');
        const gate = new SessionGate(refresh);
        await gate.recover();
        await gate.recover();
        expect(refresh).toHaveBeenCalledTimes(2);
    });

    it('refresh 결과를 알 수 없으면(응답 유실) 다시 보내지 않고 재로그인으로 안내한다', async () => {
        const refresh = vi.fn().mockRejectedValue(new TypeError('network'));
        const gate = new SessionGate(refresh);
        expect(await gate.recover()).toBe('login-required');
        expect(await gate.recover()).toBe('login-required');
        expect(await gate.recover()).toBe('login-required');
        expect(refresh).toHaveBeenCalledTimes(1);
        expect(gate.loginRequired).toBe(true);
    });

    it('서버가 재로그인을 요구해도 다시 시도하지 않는다', async () => {
        const refresh = vi.fn().mockResolvedValue('login-required');
        const gate = new SessionGate(refresh);
        await gate.recover();
        await gate.recover();
        expect(refresh).toHaveBeenCalledTimes(1);
    });

    it('다시 로그인(reset)하면 이후 401에서 refresh를 다시 시도할 수 있다', async () => {
        const refresh = vi
            .fn()
            .mockRejectedValueOnce(new Error('lost'))
            .mockResolvedValueOnce('refreshed');
        const gate = new SessionGate(refresh);
        expect(await gate.recover()).toBe('login-required');
        gate.reset();
        expect(await gate.recover()).toBe('refreshed');
        expect(refresh).toHaveBeenCalledTimes(2);
    });

    it('진행 중인 refresh가 실패하면 대기하던 모든 호출이 같은 결과를 받는다', async () => {
        const wait = deferred<'refreshed'>();
        const gate = new SessionGate(() => wait.promise);
        const first = gate.recover();
        const second = gate.recover();
        wait.reject(new Error('lost'));
        expect(await Promise.all([first, second])).toEqual(['login-required', 'login-required']);
    });
});
