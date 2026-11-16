import { describe, expect, it, vi } from 'vitest';
import { LeaveGuardController, type BackOutcome } from './leave-guard';

function setup() {
    const controller = new LeaveGuardController();
    const ports = { goBack: vi.fn(), minimize: vi.fn() };
    return { controller, ports };
}
const prompt = (controller: LeaveGuardController) => controller.getSnapshot().prompt;

describe('Back 우선순위: 확인 창 → 모달 → 이탈 확인 → 이력 뒤로 → 최소화', () => {
    it('아무것도 없으면 이력이 있을 때 뒤로, 없으면 최소화한다', () => {
        const { controller, ports } = setup();
        expect(controller.handleBack({ canGoBack: true }, ports)).toBe('went-back');
        expect(ports.goBack).toHaveBeenCalledTimes(1);
        expect(controller.handleBack({ canGoBack: false }, ports)).toBe('minimized');
        expect(ports.minimize).toHaveBeenCalledTimes(1);
    });

    it('모달이 열려 있으면 모달만 닫고 이동하지 않는다', () => {
        const { controller, ports } = setup();
        const close = vi.fn();
        controller.registerModal(close);
        controller.registerProtection({ reason: 'unsaved' });
        expect(controller.handleBack({ canGoBack: true }, ports)).toBe('closed-modal');
        expect(close).toHaveBeenCalledTimes(1);
        expect(ports.goBack).not.toHaveBeenCalled();
        expect(prompt(controller)).toBeNull();
    });

    it('모달은 늦게 연 것부터 닫는다', () => {
        const { controller, ports } = setup();
        const order: string[] = [];
        const closeFirst = controller.registerModal(() => order.push('first'));
        controller.registerModal(() => order.push('second'));
        controller.handleBack({ canGoBack: true }, ports);
        // 모달이 닫혔다고 가정하고 등록을 해제한다.
        closeFirst();
        expect(order).toEqual(['second']);
    });

    it('모달을 모두 닫은 뒤의 Back은 이탈 확인을 연다', () => {
        const { controller, ports } = setup();
        const release = controller.registerModal(() => undefined);
        controller.registerProtection({ reason: 'unsaved' });
        controller.handleBack({ canGoBack: true }, ports);
        release();
        expect(controller.handleBack({ canGoBack: true }, ports)).toBe('prompted');
        expect(prompt(controller)).not.toBeNull();
        expect(ports.goBack).not.toHaveBeenCalled();
    });

    it('이탈 확인이 열려 있을 때의 Back은 확인 창만 닫는다', () => {
        const { controller, ports } = setup();
        controller.registerProtection({ reason: 'unsaved' });
        controller.handleBack({ canGoBack: true }, ports);
        expect(controller.handleBack({ canGoBack: true }, ports)).toBe('dismissed-prompt');
        expect(prompt(controller)).toBeNull();
        expect(ports.goBack).not.toHaveBeenCalled();
    });

    it('확인(나가기) 뒤에는 이동이 정확히 한 번만 실행된다', () => {
        const { controller, ports } = setup();
        controller.registerProtection({ reason: 'recording' });
        controller.handleBack({ canGoBack: true }, ports);
        controller.leave();
        controller.leave();
        expect(ports.goBack).toHaveBeenCalledTimes(1);
        expect(prompt(controller)).toBeNull();
    });

    it('계속하기는 보류한 이동을 버리고 보호는 유지한다', () => {
        const { controller, ports } = setup();
        controller.registerProtection({ reason: 'unsaved' });
        controller.handleBack({ canGoBack: false }, ports);
        controller.stay();
        controller.leave();
        expect(ports.minimize).not.toHaveBeenCalled();
        expect(controller.isProtected()).toBe(true);
    });

    it('루트에서 보호 중이면 최소화 전에도 확인한다', () => {
        const { controller, ports } = setup();
        controller.registerProtection({ reason: 'recording' });
        expect(controller.handleBack({ canGoBack: false }, ports)).toBe('prompted');
        expect(ports.minimize).not.toHaveBeenCalled();
        controller.leave();
        expect(ports.minimize).toHaveBeenCalledTimes(1);
    });

    it('표: 상태 조합별 결과', () => {
        const cases: [string, boolean, boolean, boolean, BackOutcome][] = [
            // [이름, 모달, 보호, 이력 있음, 기대]
            ['없음/이력', false, false, true, 'went-back'],
            ['없음/루트', false, false, false, 'minimized'],
            ['모달', true, false, true, 'closed-modal'],
            ['모달+보호', true, true, true, 'closed-modal'],
            ['보호/이력', false, true, true, 'prompted'],
            ['보호/루트', false, true, false, 'prompted'],
        ];
        for (const [name, modal, protect, canGoBack, expected] of cases) {
            const { controller, ports } = setup();
            if (modal) controller.registerModal(() => undefined);
            if (protect) controller.registerProtection({ reason: 'unsaved' });
            expect(controller.handleBack({ canGoBack }, ports), name).toBe(expected);
        }
    });
});

describe('이탈 요청', () => {
    it('보호가 없으면 즉시 실행한다', () => {
        const { controller } = setup();
        const run = vi.fn();
        expect(controller.request(run)).toBe('ran');
        expect(run).toHaveBeenCalledTimes(1);
    });

    it('확인이 열려 있는 동안의 두 번째 요청은 무시하고 첫 요청을 유지한다', () => {
        const { controller } = setup();
        controller.registerProtection({ reason: 'unsaved' });
        const first = vi.fn();
        const second = vi.fn();
        controller.request(first);
        controller.request(second);
        controller.leave();
        expect(first).toHaveBeenCalledTimes(1);
        expect(second).not.toHaveBeenCalled();
    });

    it('나가기를 실행하는 동안 이어지는 이동은 다시 묻지 않는다', () => {
        const { controller } = setup();
        controller.registerProtection({ reason: 'unsaved' });
        const nested = vi.fn();
        controller.request(() => {
            expect(controller.isBypassing()).toBe(true);
            controller.request(nested);
        });
        controller.leave();
        expect(nested).toHaveBeenCalledTimes(1);
        expect(controller.isBypassing()).toBe(false);
    });

    it('보류한 이동이 던져도 우회 상태가 남지 않는다', () => {
        const { controller } = setup();
        controller.registerProtection({ reason: 'unsaved' });
        controller.request(() => {
            throw new Error('navigation failed');
        });
        expect(() => controller.leave()).toThrow('navigation failed');
        expect(controller.isBypassing()).toBe(false);
        expect(controller.request(() => undefined)).toBe('prompted');
    });

    it('보호가 모두 해제되면 열려 있던 확인과 보류를 버린다', () => {
        const { controller } = setup();
        const release = controller.registerProtection({ reason: 'unsaved' });
        const run = vi.fn();
        controller.request(run);
        release();
        expect(prompt(controller)).toBeNull();
        controller.leave();
        expect(run).not.toHaveBeenCalled();
    });

    it('보호를 여러 개 등록하면 하나가 남는 동안 계속 막는다', () => {
        const { controller } = setup();
        const releaseA = controller.registerProtection({ reason: 'unsaved' });
        controller.registerProtection({ reason: 'recording' });
        releaseA();
        expect(controller.isProtected()).toBe(true);
    });

    it('보호 해제는 여러 번 불러도 다른 보호를 해제하지 않는다', () => {
        const { controller } = setup();
        const releaseA = controller.registerProtection({ reason: 'unsaved' });
        controller.registerProtection({ reason: 'unsaved' });
        releaseA();
        releaseA();
        expect(controller.isProtected()).toBe(true);
    });
});

describe('확인 문구와 구독', () => {
    it('녹음 중이면 미저장보다 녹음 안내를 우선한다', () => {
        const { controller } = setup();
        controller.registerProtection({ reason: 'recording' });
        controller.registerProtection({ reason: 'unsaved' });
        controller.request(() => undefined);
        expect(prompt(controller)?.reason).toBe('recording');
    });

    it('화면이 준 문구가 기본 문구를 덮어쓰고, 빠진 항목은 기본값을 쓴다', () => {
        const { controller } = setup();
        controller.registerProtection({ reason: 'unsaved', copy: { title: '보고서 작성 중' } });
        controller.request(() => undefined);
        expect(prompt(controller)?.copy.title).toBe('보고서 작성 중');
        expect(prompt(controller)?.copy.leave).toBe('나가기');
    });

    it('undefined 문구 항목이 기본값을 지우지 않는다', () => {
        const { controller } = setup();
        controller.registerProtection({
            reason: 'unsaved',
            copy: { title: undefined, message: undefined },
        });
        controller.request(() => undefined);
        expect(prompt(controller)?.copy.title).toBeTruthy();
        expect(prompt(controller)?.copy.message).toBeTruthy();
    });

    it('구독자는 상태가 바뀔 때만 알림을 받고 스냅샷은 그때만 새로 만들어진다', () => {
        const { controller } = setup();
        const listener = vi.fn();
        const unsubscribe = controller.subscribe(listener);
        const initial = controller.getSnapshot();
        controller.registerModal(() => undefined);
        expect(listener).not.toHaveBeenCalled();
        expect(controller.getSnapshot()).toBe(initial);
        const release = controller.registerProtection({ reason: 'unsaved' });
        expect(listener).toHaveBeenCalledTimes(1);
        const protectedSnapshot = controller.getSnapshot();
        expect(controller.getSnapshot()).toBe(protectedSnapshot);
        release();
        expect(listener).toHaveBeenCalledTimes(2);
        unsubscribe();
        controller.registerProtection({ reason: 'unsaved' });
        expect(listener).toHaveBeenCalledTimes(2);
    });
});
