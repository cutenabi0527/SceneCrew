import { describe, expect, it } from 'vitest';
import { FakeBrowser } from './fake-browser';
import { createGuardedHistory } from './guarded-history';
import { LeaveGuardController } from './leave-guard';

function setup(...pages: string[]) {
    const browser = new FakeBrowser();
    const controller = new LeaveGuardController();
    const history = createGuardedHistory(browser.history, controller, browser.window);
    const seen: string[] = [];
    history.listen((update) => seen.push(`${update.action}:${update.location.pathname}`));
    // 라우터가 이동하는 것처럼 화면을 쌓는다(예: '/', '/a', '/b'). 첫 항목은 시작 위치다.
    for (const page of pages) history.push(page);
    seen.length = 0;
    return { browser, controller, history, seen };
}

const prompting = (controller: LeaveGuardController) => controller.getSnapshot().prompt !== null;

describe('보호 중 링크/navigate 이동', () => {
    it('보호가 없으면 그대로 이동한다', () => {
        const { browser, history, seen } = setup();
        history.push('/a');
        expect(browser.path).toBe('/a');
        expect(seen).toEqual(['PUSH:/a']);
    });

    it('화면을 벗어나면 확인이 끝날 때까지 이동하지 않고, 계속하기는 이동을 버린다', () => {
        const { browser, controller, history, seen } = setup('/form');
        controller.registerProtection({ reason: 'unsaved' });
        history.push('/other');
        expect(browser.path).toBe('/form');
        expect(prompting(controller)).toBe(true);
        controller.stay();
        expect(browser.path).toBe('/form');
        expect(seen).toEqual([]);
        expect(prompting(controller)).toBe(false);
    });

    it('나가기는 보류한 이동을 정확히 한 번 실행한다', () => {
        const { browser, controller, history, seen } = setup('/form');
        controller.registerProtection({ reason: 'unsaved' });
        history.push('/other');
        controller.leave();
        controller.leave();
        expect(browser.path).toBe('/other');
        expect(browser.entries).toHaveLength(3);
        expect(seen).toEqual(['PUSH:/other']);
    });

    it('같은 화면 안의 search 변경은 막지 않는다', () => {
        const { browser, controller, history, seen } = setup('/form');
        controller.registerProtection({ reason: 'unsaved' });
        history.push('/form?tab=2');
        expect(browser.path).toBe('/form?tab=2');
        expect(prompting(controller)).toBe(false);
        expect(seen).toEqual(['PUSH:/form']);
    });

    it('replace로 화면을 벗어나는 경우도 확인한다', () => {
        const { browser, controller, history } = setup('/form');
        controller.registerProtection({ reason: 'recording' });
        history.replace('/other');
        expect(browser.path).toBe('/form');
        controller.leave();
        expect(browser.path).toBe('/other');
        expect(browser.entries).toHaveLength(2);
    });

    it('이력 이동(navigate(-1))도 보호 중에는 확인한다', async () => {
        const { browser, controller, history, seen } = setup('/a', '/b');
        controller.registerProtection({ reason: 'unsaved' });
        history.go(-1);
        await browser.settle();
        expect(browser.path).toBe('/b');
        expect(prompting(controller)).toBe(true);
        controller.leave();
        await browser.settle();
        expect(browser.path).toBe('/a');
        expect(seen).toEqual(['POP:/a']);
    });

    it('보호를 해제하면 막지 않는다', () => {
        const { browser, controller, history } = setup('/form');
        const release = controller.registerProtection({ reason: 'unsaved' });
        release();
        release();
        history.push('/other');
        expect(browser.path).toBe('/other');
    });
});

describe('보호 중 브라우저 Back/Forward', () => {
    it('Back은 되돌리고 확인을 연다. 라우터에는 아무 이벤트도 가지 않는다', async () => {
        const { browser, controller, seen } = setup('/a', '/b');
        controller.registerProtection({ reason: 'unsaved' });
        browser.userGo(-1);
        await browser.settle();
        expect(browser.path).toBe('/b');
        expect(prompting(controller)).toBe(true);
        expect(seen).toEqual([]);
    });

    it('계속하기 뒤에는 원래 위치에 머문다', async () => {
        const { browser, controller, seen } = setup('/a', '/b');
        controller.registerProtection({ reason: 'unsaved' });
        browser.userGo(-1);
        await browser.settle();
        controller.stay();
        await browser.settle();
        expect(browser.path).toBe('/b');
        expect(seen).toEqual([]);
    });

    it('나가기는 눌렀던 목적지로 한 번만 이동한다', async () => {
        const { browser, controller, seen } = setup('/a', '/b');
        controller.registerProtection({ reason: 'unsaved' });
        browser.userGo(-1);
        await browser.settle();
        controller.leave();
        await browser.settle();
        expect(browser.path).toBe('/a');
        expect(seen).toEqual(['POP:/a']);
    });

    it('연속 Back을 눌러도 원래 위치로 수렴하고 확인은 하나만 열린다', async () => {
        const { browser, controller, seen } = setup('/a', '/b', '/c');
        let opened = 0;
        controller.subscribe(() => {
            if (prompting(controller)) opened++;
        });
        controller.registerProtection({ reason: 'unsaved' });
        browser.userGo(-1);
        browser.userGo(-1);
        browser.userGo(-1);
        await browser.settle();
        expect(browser.path).toBe('/c');
        expect(prompting(controller)).toBe(true);
        expect(seen).toEqual([]);
        expect(opened).toBe(1);
        controller.leave();
        await browser.settle();
        // 첫 Back의 목적지로만 이동한다.
        expect(browser.path).toBe('/b');
        expect(seen).toEqual(['POP:/b']);
    });

    it('Forward도 같은 방식으로 막는다', async () => {
        const { browser, controller, seen } = setup('/a', '/b');
        browser.userGo(-1);
        await browser.settle();
        seen.length = 0;
        controller.registerProtection({ reason: 'recording' });
        browser.userGo(1);
        await browser.settle();
        expect(browser.path).toBe('/a');
        expect(prompting(controller)).toBe(true);
        controller.leave();
        await browser.settle();
        expect(browser.path).toBe('/b');
        expect(seen).toEqual(['POP:/b']);
    });

    it('같은 화면 안(search만 다른) 이력 이동은 막지 않는다', async () => {
        const { browser, controller, seen } = setup('/form?x=1', '/form?x=2');
        controller.registerProtection({ reason: 'unsaved' });
        browser.userGo(-1);
        await browser.settle();
        expect(browser.path).toBe('/form?x=1');
        expect(prompting(controller)).toBe(false);
        expect(seen).toEqual(['POP:/form']);
    });

    it('보호를 해제하면 Back을 막지 않는다', async () => {
        const { browser, controller, seen } = setup('/a', '/b');
        const release = controller.registerProtection({ reason: 'unsaved' });
        release();
        browser.userGo(-1);
        await browser.settle();
        expect(browser.path).toBe('/a');
        expect(seen).toEqual(['POP:/a']);
    });

    it('이동을 마친 뒤에도 보호가 남아 있으면 다음 Back을 다시 확인한다(허용이 새지 않는다)', async () => {
        const { browser, controller, seen } = setup('/a', '/b', '/c');
        controller.registerProtection({ reason: 'unsaved' });
        browser.userGo(-1);
        await browser.settle();
        controller.leave();
        await browser.settle();
        expect(browser.path).toBe('/b');
        seen.length = 0;
        browser.userGo(-1);
        await browser.settle();
        expect(browser.path).toBe('/b');
        expect(prompting(controller)).toBe(true);
        expect(seen).toEqual([]);
    });

    it('범위를 벗어난 go(+1)가 남긴 허용 위치가 이후 Forward를 통과시키지 않는다', async () => {
        const { browser, controller, history, seen } = setup('/a');
        // 마지막 항목에서 앞으로 가기: 아무 일도 일어나지 않는다.
        history.go(1);
        await browser.settle();
        // 이후 push가 정확히 그 위치(idx 2)를 만든다.
        history.push('/b');
        browser.userGo(-1);
        await browser.settle();
        seen.length = 0;
        controller.registerProtection({ reason: 'unsaved' });
        browser.userGo(1);
        await browser.settle();
        expect(prompting(controller)).toBe(true);
        expect(browser.path).toBe('/a');
        expect(seen).toEqual([]);
    });

    it('라우터가 모르는 이력 항목(idx 없음)은 막지 않고 통과시킨다', async () => {
        // 다른 코드가 pushState한 항목은 history.state.idx가 없어 위치를 추적할 수 없다.
        const { browser, controller, seen } = setup('/a');
        browser.pushForeign('/a#frag');
        controller.registerProtection({ reason: 'unsaved' });
        // 라우터는 알던 위치(/a)로 돌아왔을 뿐이라 이벤트를 받지 않는다.
        browser.userGo(-1);
        await browser.settle();
        expect(browser.path).toBe('/a');
        expect(prompting(controller)).toBe(false);
        expect(seen).toEqual([]);
        // 모르는 항목으로 들어가는 이동은 근거가 없어 그대로 통과한다.
        browser.userGo(1);
        await browser.settle();
        expect(browser.path).toBe('/a#frag');
        expect(prompting(controller)).toBe(false);
        expect(seen).toEqual(['POP:/a']);
    });
});

describe('Android 하드웨어 Back과 보호 history', () => {
    const portsFor = (history: { go(delta: number): void }) => {
        const minimized: number[] = [];
        return {
            ports: { goBack: () => history.go(-1), minimize: () => minimized.push(1) },
            minimized,
        };
    };

    it('보호가 없으면 한 단계 뒤로 간다', async () => {
        const { browser, controller, history, seen } = setup('/a', '/b');
        const { ports } = portsFor(history);
        expect(controller.handleBack({ canGoBack: true }, ports)).toBe('went-back');
        await browser.settle();
        expect(browser.path).toBe('/a');
        expect(seen).toEqual(['POP:/a']);
    });

    it('보호 중이면 먼저 확인하고, 나가기 뒤 한 번만 뒤로 간다', async () => {
        const { browser, controller, history, seen } = setup('/a', '/b');
        const { ports } = portsFor(history);
        controller.registerProtection({ reason: 'unsaved' });
        expect(controller.handleBack({ canGoBack: true }, ports)).toBe('prompted');
        await browser.settle();
        expect(browser.path).toBe('/b');
        controller.leave();
        await browser.settle();
        expect(browser.path).toBe('/a');
        expect(seen).toEqual(['POP:/a']);
    });

    it('확인이 열린 상태의 Back은 계속하기와 같다', async () => {
        const { browser, controller, history, seen } = setup('/a', '/b');
        const { ports } = portsFor(history);
        controller.registerProtection({ reason: 'unsaved' });
        controller.handleBack({ canGoBack: true }, ports);
        expect(controller.handleBack({ canGoBack: true }, ports)).toBe('dismissed-prompt');
        await browser.settle();
        expect(browser.path).toBe('/b');
        expect(seen).toEqual([]);
        expect(prompting(controller)).toBe(false);
    });

    it('루트에서 보호 중이면 최소화 전에도 확인한다', () => {
        const { controller, history } = setup();
        const { ports, minimized } = portsFor(history);
        controller.registerProtection({ reason: 'recording' });
        expect(controller.handleBack({ canGoBack: false }, ports)).toBe('prompted');
        expect(minimized).toEqual([]);
        controller.leave();
        expect(minimized).toEqual([1]);
    });
});
