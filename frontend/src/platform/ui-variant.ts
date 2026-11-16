import type { AppPlatform } from './config';

/** 화면 레이아웃 갈래. 권한·데이터는 같고 배치만 다르다. 너비가 아니라 플랫폼·기기로 정한다. */
export type UiVariant = 'web' | 'app';

/**
 * 휴대폰 브라우저인지(#402). iPhone·Android 휴대폰만 본다. 태블릿(iPad, Mobile이 없는 Android)은
 * 총괄 WEB 화면을 쓸 수 있도록 휴대폰으로 보지 않는다.
 */
export function isPhoneBrowser(userAgent: string) {
    if (/iPhone|iPod/i.test(userAgent)) return true;
    return /Android/i.test(userAgent) && /Mobile/i.test(userAgent);
}

/**
 * Android 빌드는 항상 APP. WEB 빌드는 기본 WEB이고, 휴대폰 브라우저는 앱과 같은 APP 화면이다(#402,
 * iPhone은 앱이 없어 브라우저로 참여한다). 개발 서버와 휴대폰에서는 `?ui=app|web`으로 고를 수 있다.
 */
export function resolveUiVariant(
    platform: AppPlatform,
    dev: boolean,
    search: string,
    stored: string | null,
    phone = false,
): UiVariant {
    if (platform === 'android') return 'app';
    const pick = new URLSearchParams(search).get('ui') ?? stored;
    if ((dev || phone) && (pick === 'app' || pick === 'web')) return pick;
    return phone ? 'app' : 'web';
}
