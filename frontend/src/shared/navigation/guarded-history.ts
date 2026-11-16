import type { LeaveGuardController } from './leave-guard';

// 선언형 Router(BrowserRouter/HashRouter)에는 useBlocker가 없다("must be used within a data router").
// 그래서 unstable_HistoryRouter에 넘길 history를 감싸서 이동을 직접 가로챈다.
//  - push/replace/go(링크·navigate): 화면을 벗어나면 확인이 끝날 때까지 보류한다.
//  - 브라우저 Back/Forward(POP): 이미 주소가 바뀐 뒤라 막을 수 없으므로, 마지막으로 허용한 위치로
//    되돌린 뒤 확인을 연다. 위치는 history.state.idx(절대값)로 추적해서 연속 Back에도 수렴한다.
// 같은 pathname 안의 search/hash 변경은 화면을 벗어난 것으로 보지 않는다.

export interface HistoryLocation {
    pathname: string;
    search: string;
    hash: string;
}

export interface HistoryUpdate {
    action: string;
    location: HistoryLocation;
    delta: number | null;
}

// react-router History의 사용 부분만 정의한다(UNSAFE_createBrowserHistory/HashHistory와 호환).
export interface BaseHistory {
    readonly action: string;
    readonly location: HistoryLocation;
    createHref(to: unknown): string;
    createURL(to: unknown): URL;
    encodeLocation(to: unknown): HistoryLocation;
    push(to: unknown, state?: unknown): void;
    replace(to: unknown, state?: unknown): void;
    go(delta: number): void;
    listen(listener: (update: HistoryUpdate) => void): () => void;
}

export interface HistoryWindow {
    history: { state: unknown };
}

function readIdx(win: HistoryWindow): number | null {
    const state = win.history.state as { idx?: unknown } | null;
    return typeof state?.idx === 'number' ? state.idx : null;
}

// 입력 history와 같은 타입을 돌려줘서 라우터(HistoryRouter)에 그대로 넘길 수 있다.
export function createGuardedHistory<H extends BaseHistory>(
    base: H,
    controller: LeaveGuardController,
    win: HistoryWindow = window,
): H {
    // 마지막으로 사용자에게 보여 준(허용한) 위치
    let acceptedIdx = readIdx(win);
    let acceptedPath = base.location.pathname;
    // 확인을 거쳐 이동이 허용된 목표 위치. 해당 idx의 POP만 통과시킨다.
    let allowedIdx: number | null = null;

    const leavesScreen = (to: unknown) =>
        base.encodeLocation(to).pathname !== base.location.pathname;

    // 보호가 없거나, 확인을 거쳐 실행 중이거나, 같은 화면 안의 변경이면 그대로 실행한다.
    const mustConfirm = (to: unknown) =>
        !controller.isBypassing() && controller.isProtected() && leavesScreen(to);

    const history: BaseHistory = {
        get action() {
            return base.action;
        },
        get location() {
            return base.location;
        },
        createHref: (to) => base.createHref(to),
        createURL: (to) => base.createURL(to),
        encodeLocation: (to) => base.encodeLocation(to),
        push(to, state) {
            if (mustConfirm(to)) controller.request(() => base.push(to, state));
            else base.push(to, state);
        },
        replace(to, state) {
            if (mustConfirm(to)) controller.request(() => base.replace(to, state));
            else base.replace(to, state);
        },
        go(delta: number) {
            if (delta === 0) return;
            const current = readIdx(win);
            const run = () => {
                // 확인을 거쳤거나 보호가 없을 때만 도달한다. 목표 위치의 POP은 통과시킨다.
                allowedIdx = current === null ? null : current + delta;
                base.go(delta);
            };
            // 이력 이동은 어느 화면으로 가는지 미리 알 수 없으므로 보호 중에는 항상 확인한다.
            if (controller.isBypassing() || !controller.isProtected()) run();
            else controller.request(run);
        },
        listen(listener) {
            return base.listen((update) => {
                if (update.action !== 'POP') {
                    // 우리가 직접 실행한 push/replace. 허용 위치를 갱신한다.
                    // 범위를 벗어나 일어나지 않은 go()의 허용 위치가 이후 같은 idx를 통과시키지 않게 지운다.
                    acceptedIdx = readIdx(win);
                    acceptedPath = update.location.pathname;
                    allowedIdx = null;
                    listener(update);
                    return;
                }
                const idx = readIdx(win);
                // 위치를 알 수 없으면(외부에서 만든 이력 항목) 막을 근거가 없어 그대로 통과한다.
                if (idx === null || acceptedIdx === null) {
                    acceptedIdx = idx;
                    acceptedPath = update.location.pathname;
                    allowedIdx = null;
                    listener(update);
                    return;
                }
                if (allowedIdx !== null && idx === allowedIdx) {
                    allowedIdx = null;
                    acceptedIdx = idx;
                    acceptedPath = update.location.pathname;
                    listener(update);
                    return;
                }
                // 우리가 되돌린 이벤트이거나 같은 위치의 중복 이벤트
                if (idx === acceptedIdx) return;
                const sameScreen = update.location.pathname === acceptedPath;
                if (!controller.isProtected() || sameScreen) {
                    acceptedIdx = idx;
                    acceptedPath = update.location.pathname;
                    listener(update);
                    return;
                }
                // 보호 중 화면을 벗어나는 Back/Forward: 허용한 위치로 되돌리고 확인을 연다.
                const target = idx;
                base.go(acceptedIdx - idx);
                controller.request(() => {
                    allowedIdx = target;
                    base.go(target - (readIdx(win) ?? acceptedIdx!));
                });
            });
        },
    };
    // 입력과 같은 형태(추가 필드 포함)를 유지하도록 덮어쓸 메서드만 바꾼 객체를 돌려준다.
    return Object.create(base, Object.getOwnPropertyDescriptors(history)) as H;
}
