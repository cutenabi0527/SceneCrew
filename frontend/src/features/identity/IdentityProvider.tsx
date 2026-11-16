import { useEffect, useState, type ReactNode } from 'react';
import { androidOAuthEnabled, apiBaseUrl, platform } from '../../platform/runtime';
import { isNative } from '../../platform/runtime';
import { App as CapacitorApp } from '@capacitor/app';
import { useScopes } from '../../shared/scope/scope-context';
import { PushBridge } from '../push/PushBridge';
import { IdentityContext } from './context';
import { IdentitySession } from './session';
import { nativeRefreshStore } from './refresh-store';
import { nativeProofStore } from './android-oauth';

export function IdentityProvider({ children }: { children: ReactNode }) {
    const scopes = useScopes();
    const [session] = useState(
        () =>
            new IdentitySession(
                platform === 'web' ? 'WEB' : 'ANDROID',
                apiBaseUrl,
                scopes,
                fetch,
                platform === 'android' ? nativeRefreshStore(apiBaseUrl) : undefined,
                // Android 소셜 로그인(#388)은 BE가 Android 공개 API를 연 빌드에서만 켠다.
                platform === 'android' && androidOAuthEnabled ? nativeProofStore() : undefined,
            ),
    );
    useEffect(() => {
        void session.bootstrap();
    }, [session]);
    useEffect(() => {
        if (!isNative) return;
        const handle = CapacitorApp.addListener('appStateChange', ({ isActive }) => {
            if (isActive) window.dispatchEvent(new Event('focus'));
        });
        return () => {
            void handle.then((listener) => listener.remove());
        };
    }, []);
    return (
        <IdentityContext.Provider value={session}>
            <PushBridge>{children}</PushBridge>
        </IdentityContext.Provider>
    );
}
