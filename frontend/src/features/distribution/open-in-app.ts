// 휴대폰 브라우저로 연 초대 링크를 앱으로 넘긴다(#402).
// 앱이 설치돼 있고 App Link 확인이 끝났으면 링크를 누를 때 바로 앱이 열린다. 브라우저로 열렸다면
// 앱이 없거나 다른 앱(QR 스캐너 등)이 링크를 직접 연 경우라, 앱 열기·설치 안내를 먼저 보인다.

/** 공개 APK의 applicationId. android/app/build.gradle과 같아야 한다. */
export const ANDROID_PACKAGE = 'io.scenecrew.app';

/**
 * APK를 설치할 수 있는 Android 브라우저인지. iPhone은 앱이 없어 휴대폰용 앱 화면의 웹으로 바로 참여한다.
 */
export function isAndroidBrowser(userAgent: string) {
    return /Android/i.test(userAgent);
}

/**
 * Android Chrome·삼성 인터넷이 여는 intent 주소. 앱이 있으면 같은 초대 링크로 앱을 열고,
 * 없으면 설치 안내(/download)로 보낸다. 대신 여는 주소에는 초대 토큰을 넣지 않는다.
 */
export function inviteIntentUrl(origin: string, token: string, group?: string | null) {
    const base = new URL(origin);
    const query = group ? `?${new URLSearchParams({ group }).toString()}` : '';
    const fallback = encodeURIComponent(`${base.origin}/download`);
    return (
        `intent://${base.host}/invites/${encodeURIComponent(token)}${query}` +
        `#Intent;scheme=https;package=${ANDROID_PACKAGE};S.browser_fallback_url=${fallback};end`
    );
}
