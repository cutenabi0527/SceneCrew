import { Capacitor, registerPlugin } from '@capacitor/core';

// Android 소셜 로그인(#388). 외부 브라우저로 인증하고 App Link(/app/oauth)로 앱에 돌아와,
// 시작할 때 만든 S256 증명(verifier)으로 일회용 티켓을 교환한다. WEB의 브라우저 Cookie 증명을 대신한다.
//
// BE 계약이 확정되기 전의 FE 가정을 이 파일에 모았다. 확정되면 아래 이름만 맞추면 된다.
// - 시작: GET /auth/oauth/{provider}/authorize?purpose=LOGIN&returnRoute=HOME&codeChallenge=…&codeChallengeMethod=S256
// - 복귀: https://<도메인>/app/oauth?ticket=… 또는 ?error=…
// - 교환·가입 완료: POST /auth/oauth/exchange 본문에 codeVerifier를 더한다. 응답은 Android 로그인과 같은 LoginResult다.

/** 앱으로 돌아오는 App Link 경로. AndroidManifest의 intent-filter와 같아야 한다. */
export const ANDROID_OAUTH_RETURN_PATH = '/app/oauth';

export const CHALLENGE_PARAM = 'codeChallenge';
export const CHALLENGE_METHOD_PARAM = 'codeChallengeMethod';
export const VERIFIER_FIELD = 'codeVerifier';

/** 가장 최근 시도 1건의 증명. 새로 시작하면 이전 시도의 증명은 사라진다. */
export interface OAuthProofStore {
    /** 새 증명을 만들어 보관하고 S256 challenge를 돌려준다. */
    begin(): Promise<string>;
    /** 보관한 verifier. 없거나 만료됐으면 null이다. */
    read(): Promise<string | null>;
    clear(): Promise<void>;
}

interface NativeProof {
    begin(): Promise<{ challenge: string }>;
    read(): Promise<{ verifier?: string | null }>;
    clear(): Promise<void>;
}

export function nativeProofStore(): OAuthProofStore | undefined {
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') return undefined;
    const plugin = registerPlugin<NativeProof>('OAuthProof');
    return {
        async begin() {
            const { challenge } = await plugin.begin();
            if (!/^[A-Za-z0-9_-]{43}$/.test(challenge))
                throw new Error('로그인 증명을 준비하지 못했습니다.');
            return challenge;
        },
        async read() {
            const { verifier } = await plugin.read();
            return typeof verifier === 'string' && verifier ? verifier : null;
        },
        clear: () => plugin.clear(),
    };
}

interface NativeAppLinks {
    take(): Promise<{ url?: string | null }>;
    addListener(event: 'received', listener: () => void): Promise<{ remove(): Promise<void> }>;
}

/**
 * 네이티브 AppLinkActivity가 받은 App Link의 받은편지함. 가장 최근 링크 1개를 한 번만 꺼낸다.
 * MainActivity가 링크를 직접 받으면 앱 프로세스 종료 뒤 Chrome이 연 링크가 전달되지 않아 따로 받는다.
 */
export function appLinkInbox() {
    const plugin = registerPlugin<NativeAppLinks>('AppLinks');
    return {
        async take() {
            const { url } = await plugin.take();
            return typeof url === 'string' && url ? url : null;
        },
        onReceived: (listener: () => void) => plugin.addListener('received', listener),
    };
}

/**
 * 앱으로 들어온 App Link를 앱 안 화면 경로로 바꾼다. 우리 도메인의 소셜 복귀·초대 링크만 받는다.
 * 그 밖의 주소는 null이라 무시한다.
 */
export function appLinkRoute(url: string, host: string) {
    let parsed: URL;
    try {
        parsed = new URL(url);
    } catch {
        return null;
    }
    if (parsed.protocol !== 'https:' || parsed.host !== host || parsed.username || parsed.password)
        return null;
    if (parsed.pathname === ANDROID_OAUTH_RETURN_PATH) {
        const params = new URLSearchParams();
        for (const key of ['ticket', 'error'] as const) {
            const value = parsed.searchParams.get(key);
            if (value) params.set(key, value);
        }
        const query = params.toString();
        return query ? `${ANDROID_OAUTH_RETURN_PATH}?${query}` : ANDROID_OAUTH_RETURN_PATH;
    }
    if (/^\/invites\/[^/?#]+$/.test(parsed.pathname)) {
        // 그룹별 초대 링크(?group=)면 그 그룹을 골라 둔 채 열리도록 group만 옮긴다.
        const group = parsed.searchParams.get('group');
        return group
            ? `${parsed.pathname}?${new URLSearchParams({ group }).toString()}`
            : parsed.pathname;
    }
    return null;
}
