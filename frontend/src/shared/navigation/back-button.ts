import type { LeaveGuardController } from './leave-guard';

// Capacitor App 플러그인의 backButton 이벤트를 컨트롤러에 연결한다.
// 플러그인을 직접 import하지 않고 포트로 받아서, 실제 기기 없이도 연결 로직을 검사할 수 있다.
// (이 검사는 실제 기기의 Back 동작을 대신하지 않는다.)

// 로그인 후 첫 화면. 실제 계정의 행사 목록, 시연 행사 목록, 앱 홈이다.
const HOME_PATHS = new Set(['/account/events', '/events', '/e/home']);
export const isHomePath = (pathname: string) =>
    HOME_PATHS.has(pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname);

export interface BackButtonHandle {
    remove(): void | Promise<void>;
}

export interface BackButtonPort {
    addListener(
        event: 'backButton',
        listener: (event: { canGoBack: boolean }) => void,
    ): Promise<BackButtonHandle>;
    minimize(): void | Promise<void>;
}

// 반환한 cleanup은 등록이 끝나기 전에 불려도, 여러 번 불려도 리스너를 정확히 한 번만 제거한다
// (React StrictMode의 effect 이중 실행).
// isHome이 true인 화면(로그인 후 첫 화면)에서는 이력이 남아 있어도 앱을 최소화한다.
// 로그인 화면으로 되돌아가지 않게 하기 위해서다(V9-D32).
export function connectBackButton(
    port: BackButtonPort,
    controller: LeaveGuardController,
    goBack: () => void,
    isHome: () => boolean = () => false,
): () => void {
    let disposed = false;
    let handle: BackButtonHandle | null = null;
    // 등록 실패는 Back 처리를 못 하는 것이므로 삼키지 않는다(처리되지 않은 거부로 드러난다).
    void port
        .addListener('backButton', (event) => {
            // 해제된 뒤 도착한 이벤트는 무시한다.
            if (disposed) return;
            const canGoBack = event.canGoBack && !isHome();
            controller.handleBack(
                { canGoBack },
                {
                    goBack,
                    minimize: () => {
                        void port.minimize();
                    },
                },
            );
        })
        .then((registered) => {
            // 등록이 끝나기 전에 해제됐다면 방금 만들어진 리스너를 바로 제거한다.
            if (disposed) void registered.remove();
            else handle = registered;
        });
    return () => {
        disposed = true;
        const current = handle;
        handle = null;
        if (current) void current.remove();
    };
}
