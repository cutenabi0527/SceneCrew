import { describe, expect, it, vi } from 'vitest';
import { connectBackButton, isHomePath, type BackButtonPort } from './back-button';
import { LeaveGuardController } from './leave-guard';

// 플러그인을 대신하는 가짜 포트. 실제 기기의 Back 동작을 검사하는 것이 아니다.
function fakePort(registration: 'immediate' | 'deferred' = 'immediate') {
    const listeners = new Set<(event: { canGoBack: boolean }) => void>();
    let removeCalls = 0;
    let finish!: () => void;
    const port: BackButtonPort = {
        addListener: (_event, listener) => {
            const register = () => {
                listeners.add(listener);
                return {
                    remove: () => {
                        removeCalls++;
                        listeners.delete(listener);
                    },
                };
            };
            if (registration === 'immediate') return Promise.resolve(register());
            return new Promise((resolve) => {
                finish = () => resolve(register());
            });
        },
        minimize: vi.fn(),
    };
    return {
        port,
        listeners,
        removed: () => removeCalls,
        finish: () => finish(),
        press: (canGoBack: boolean) =>
            [...listeners].forEach((listener) => listener({ canGoBack })),
    };
}
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('Android Back 어댑터 연결', () => {
    it('로그인 후 첫 화면에서는 이력이 남아 있어도 최소화한다(V9-D32)', async () => {
        const fake = fakePort();
        const goBack = vi.fn();
        let home = true;
        connectBackButton(fake.port, new LeaveGuardController(), goBack, () => home);
        await flush();
        fake.press(true);
        expect(goBack).not.toHaveBeenCalled();
        expect(fake.port.minimize).toHaveBeenCalledTimes(1);
        home = false;
        fake.press(true);
        expect(goBack).toHaveBeenCalledTimes(1);
    });

    it('로그인 후 첫 화면만 홈으로 본다', () => {
        expect(isHomePath('/account/events')).toBe(true);
        expect(isHomePath('/account/events/')).toBe(true);
        expect(isHomePath('/e/home')).toBe(true);
        expect(isHomePath('/events')).toBe(true);
        expect(isHomePath('/account/events/abc')).toBe(false);
        expect(isHomePath('/e/today')).toBe(false);
        expect(isHomePath('/login')).toBe(false);
    });

    it('Back 이벤트를 컨트롤러에 전달한다', async () => {
        const fake = fakePort();
        const goBack = vi.fn();
        connectBackButton(fake.port, new LeaveGuardController(), goBack);
        await flush();
        fake.press(true);
        expect(goBack).toHaveBeenCalledTimes(1);
        fake.press(false);
        expect(fake.port.minimize).toHaveBeenCalledTimes(1);
    });

    it('해제하면 리스너가 정확히 한 번 제거되고 더 이상 반응하지 않는다', async () => {
        const fake = fakePort();
        const goBack = vi.fn();
        const disconnect = connectBackButton(fake.port, new LeaveGuardController(), goBack);
        await flush();
        disconnect();
        disconnect();
        expect(fake.removed()).toBe(1);
        expect(fake.listeners.size).toBe(0);
        fake.press(true);
        expect(goBack).not.toHaveBeenCalled();
    });

    it('StrictMode처럼 연결-해제-연결해도 리스너는 하나만 남는다', async () => {
        const fake = fakePort();
        const goBack = vi.fn();
        const controller = new LeaveGuardController();
        const first = connectBackButton(fake.port, controller, goBack);
        first();
        connectBackButton(fake.port, controller, goBack);
        await flush();
        expect(fake.listeners.size).toBe(1);
        fake.press(true);
        expect(goBack).toHaveBeenCalledTimes(1);
    });

    it('등록이 끝나기 전에 해제해도 늦게 만들어진 리스너를 제거한다', async () => {
        const fake = fakePort('deferred');
        const disconnect = connectBackButton(fake.port, new LeaveGuardController(), vi.fn());
        disconnect();
        fake.finish();
        await flush();
        expect(fake.listeners.size).toBe(0);
        expect(fake.removed()).toBe(1);
    });

    it('등록이 끝나기 전에 여러 번 해제해도 제거는 한 번이다', async () => {
        const fake = fakePort('deferred');
        const disconnect = connectBackButton(fake.port, new LeaveGuardController(), vi.fn());
        disconnect();
        disconnect();
        fake.finish();
        await flush();
        expect(fake.removed()).toBe(1);
    });

    it('보호 중 Back은 확인 뒤에만 이동하고, 이후 Back은 확인 창을 닫는다', async () => {
        const fake = fakePort();
        const goBack = vi.fn();
        const controller = new LeaveGuardController();
        controller.registerProtection({ reason: 'unsaved' });
        connectBackButton(fake.port, controller, goBack);
        await flush();
        fake.press(true);
        expect(goBack).not.toHaveBeenCalled();
        fake.press(true);
        expect(controller.getSnapshot().prompt).toBeNull();
        fake.press(true);
        controller.leave();
        expect(goBack).toHaveBeenCalledTimes(1);
    });
});
