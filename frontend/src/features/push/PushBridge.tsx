import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { isNative, platform } from '../../platform/runtime';
import { useIdentity } from '../identity/context';
import { PushContext } from './context';
import { nativePush } from './native';
import { PushRegistrar, type PushTarget } from './registrar';

// Android 앱에서 로그인하면 이미 허용된 알림 권한으로 기기를 다시 등록한다. WEB은 아무것도 하지 않는다.
export function PushBridge({ children }: { children: ReactNode }) {
    const session = useIdentity();
    const [registrar] = useState(() =>
        isNative && platform === 'android'
            ? new PushRegistrar(session, nativePush, (target: PushTarget | null) => {
                  // 알림을 누르면 그 공연의 운영 화면을 열고 그 알림을 연다(#307, Android는 해시 경로).
                  // data가 없거나 형식이 다르면 내 알림 화면으로 간다
                  window.location.hash = target
                      ? `#/live/events/${target.eventId}?next=notice&n=${target.notificationId}`
                      : '#/account/notifications';
              })
            : null,
    );
    const { account } = useSyncExternalStore(session.subscribe, session.snapshot);
    useEffect(() => {
        if (!registrar) return;
        // 세션이 만료돼 계정이 사라져도 다음 로그인에서 새 세션으로 다시 등록한다(#241)
        if (!account) return registrar.forget();
        void registrar.resume().catch(() => undefined);
    }, [registrar, account]);
    // 로그아웃 직전에 이 기기의 수신 연결을 해제한다(#241)
    useEffect(
        () => (registrar ? session.beforeLogout(() => registrar.unregister()) : undefined),
        [registrar, session],
    );
    return <PushContext.Provider value={registrar}>{children}</PushContext.Provider>;
}
