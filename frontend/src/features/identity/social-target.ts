import { Capacitor } from '@capacitor/core';

// 소셜 로그인(#285)의 복귀 경로와 로그인 뒤 갈 화면.
// BE의 복귀지는 HOME 하나뿐이라, 초대 링크 등 원래 가려던 화면은 잠시 따로 둔다.
// - WEB: 이 브라우저 탭의 세션 저장소
// - Android 앱(#388): 외부 브라우저에 있는 동안 앱이 종료될 수 있어 앱 저장소(localStorage)에 15분만 둔다.

/** BE 설정 SCENECREW_AUTH_OAUTH_WEB_HOME_URI가 가리켜야 하는 FE 화면 경로 */
export const OAUTH_RETURN_PATH = '/login/oauth';
const TARGET_KEY = 'scenecrew.oauth.returnTo';
/** 앱에 보관하는 한도. 시작 증명의 보관 한도와 같다. */
const APP_TARGET_MS = 15 * 60 * 1000;

/** 로그인 뒤 갈 화면. 초대 링크 외에는 행사 목록이다(이메일 로그인과 같은 규칙). */
export function safeTarget(value: string | null) {
    return value && /^\/invites\/[^/?#]+$/.test(value) ? value : '/account/events';
}

const inApp = () => Capacitor.isNativePlatform();

export function saveTarget(target: string, now = Date.now()) {
    try {
        const path = safeTarget(target);
        if (inApp()) localStorage.setItem(TARGET_KEY, JSON.stringify({ path, at: now }));
        else sessionStorage.setItem(TARGET_KEY, path);
    } catch {
        // 저장하지 못하면 행사 목록으로 돌아온다.
    }
}

/**
 * 저장한 화면을 지우지 않고 읽는다. 없거나 허용하지 않는 경로, 앱에서 오래된 값이면 행사 목록이다.
 * 소셜 복귀가 실패·취소이면 이것만 쓴다. 늦게 도착한 이전 시도의 실패가 지금 시도의 화면을 지우면 안 된다(#388).
 */
export function peekTarget(now = Date.now()) {
    try {
        if (!inApp()) return safeTarget(sessionStorage.getItem(TARGET_KEY));
        const raw = localStorage.getItem(TARGET_KEY);
        const value: unknown = raw ? JSON.parse(raw) : null;
        if (
            value &&
            typeof value === 'object' &&
            'path' in value &&
            'at' in value &&
            typeof value.path === 'string' &&
            typeof value.at === 'number' &&
            now - value.at >= 0 &&
            now - value.at <= APP_TARGET_MS
        )
            return safeTarget(value.path);
        return safeTarget(null);
    } catch {
        return safeTarget(null);
    }
}

/** 로그인·가입 계속으로 화면을 쓸 때 꺼내고 지운다. */
export function takeTarget(now = Date.now()) {
    const target = peekTarget(now);
    try {
        if (inApp()) localStorage.removeItem(TARGET_KEY);
        else sessionStorage.removeItem(TARGET_KEY);
    } catch {
        // 지우지 못해도 다음 시작이 덮어쓰고, 앱은 보관 한도가 지나면 읽지 않는다.
    }
    return target;
}
