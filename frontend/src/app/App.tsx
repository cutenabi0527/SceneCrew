import { useEffect, useState, type ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { App as CapacitorApp } from '@capacitor/app';
import {
    UNSAFE_createBrowserHistory as createBrowserHistory,
    UNSAFE_createHashHistory as createHashHistory,
    unstable_HistoryRouter as HistoryRouter,
} from 'react-router';
import { apiBaseUrl, isNative, platform } from '../platform/runtime';
import { appLinkInbox, appLinkRoute } from '../features/identity/android-oauth';
import { DemoStoreProvider } from '../domain/DemoStoreProvider';
import { connectBackButton, isHomePath } from '../shared/navigation/back-button';
import { createGuardedHistory } from '../shared/navigation/guarded-history';
import { LeaveGuardController } from '../shared/navigation/leave-guard';
import { LeaveGuardHost } from '../shared/navigation/LeaveGuardHost';
import { ScopeProvider } from '../shared/scope/ScopeProvider';
import { ScopeManager } from '../shared/scope/scope-manager';
import { IdentityProvider } from '../features/identity/IdentityProvider';
import { readNsProbe } from '../features/voice/ns-probe';
import { UpdateGate } from '../features/distribution/UpdateGate';
import { createQueryClient } from './query-client';
import { ErrorBoundary } from './ErrorBoundary';
import { LiveBridge } from '../live/LiveBridge';
import { AppRoutes } from './routes/AppRoutes';
import { WebRoutes } from './routes/WebRoutes';
import { uiVariant } from './variant';

// 라우터가 쿼리를 지우기 전에 녹음 비교용 표시를 이 탭에 저장한다(#356).
readNsProbe();

const queryClient = createQueryClient();
// 계정·행사 참여 회차별 요청·캐시·구독 수명. 로그인과 참여 기능이 이 관리자에 값을 넘긴다.
const scopes = new ScopeManager(queryClient, undefined, (errors) =>
    console.error('범위 정리 중 오류가 발생했습니다.', errors),
);
// 선언형 Router에는 useBlocker가 없어서 이동을 가로채는 history를 직접 만든다.
// v5Compat은 BrowserRouter/HashRouter와 같다.
const leaveGuard = new LeaveGuardController();
const history = createGuardedHistory(
    (platform === 'android' ? createHashHistory : createBrowserHistory)({ v5Compat: true }),
    leaveGuard,
);

// Android 하드웨어 Back: 확인 창 닫기 → 모달 닫기 → 이탈 확인 → 화면 이력 → 루트 최소화.
// 이력 이동은 보호하는 history를 거치므로 확인을 마친 뒤에만 실행된다.
// 로그인 후 첫 화면(행사 목록·앱 홈)은 루트로 보고 최소화한다. 로그인 화면으로 되돌아가지 않는다(V9-D32).
function NativeBackHandler() {
    useEffect(() => {
        if (!isNative) return;
        return connectBackButton(
            {
                addListener: (event, listener) => CapacitorApp.addListener(event, listener),
                minimize: () => CapacitorApp.minimizeApp(),
            },
            leaveGuard,
            () => history.go(-1),
            () => isHomePath(history.location.pathname),
        );
    }, []);
    return null;
}

// App Link(#388): 소셜 로그인 복귀(/app/oauth)와 초대 링크를 앱 안 화면으로 연다.
// 링크는 네이티브 AppLinkActivity가 받아 받은편지함에 둔다(앱 프로세스 종료 뒤에도 링크를 잃지 않게).
// 시작할 때, 앱이 다시 앞으로 올 때, 새 링크 알림이 올 때 받은편지함을 꺼낸다.
// 시작할 때는 첫 화면을 그리기 전에 꺼낸다. 첫 화면(/)의 로그인 이동이 링크 화면을 덮어쓰지 않게 한다.
const appLinks = isNative && platform === 'android';
function AppLinkGate({ children }: { children: ReactNode }) {
    const [ready, setReady] = useState(!appLinks);
    useEffect(() => {
        if (!appLinks) return;
        const host = new URL(apiBaseUrl).host;
        const inbox = appLinkInbox();
        const drain = () =>
            inbox.take().then(
                (url) => {
                    const route = url ? appLinkRoute(url, host) : null;
                    if (route) history.push(route);
                },
                () => undefined,
            );
        void drain().then(() => setReady(true));
        const later = () => void drain();
        const handles = [inbox.onReceived(later), CapacitorApp.addListener('resume', later)];
        return () => {
            for (const handle of handles) void handle.then((listener) => listener.remove());
        };
    }, []);
    return ready ? children : null;
}

export function App() {
    return (
        <LeaveGuardHost controller={leaveGuard}>
            <AppContent />
        </LeaveGuardHost>
    );
}

function AppContent() {
    return (
        <ErrorBoundary>
            <QueryClientProvider client={queryClient}>
                <ScopeProvider manager={scopes}>
                    <HistoryRouter history={history}>
                        <NativeBackHandler />
                        <a
                            className="skip-link"
                            href="#main"
                            onClick={(event) => {
                                event.preventDefault();
                                document.getElementById('main')?.focus();
                            }}
                        >
                            본문으로 이동
                        </a>
                        {/* 레이아웃 분기는 여기 한 곳: 같은 데이터·컴포넌트, 다른 라우트 표와 셸 */}
                        {/* 서버 계약보다 오래된 Android 앱은 화면 대신 설치 안내만 보인다(#386) */}
                        <UpdateGate>
                            <AppLinkGate>
                                <IdentityProvider>
                                    <DemoStoreProvider>
                                        <LiveBridge>
                                            {uiVariant === 'app' ? <AppRoutes /> : <WebRoutes />}
                                        </LiveBridge>
                                    </DemoStoreProvider>
                                </IdentityProvider>
                            </AppLinkGate>
                        </UpdateGate>
                    </HistoryRouter>
                </ScopeProvider>
            </QueryClientProvider>
        </ErrorBoundary>
    );
}
