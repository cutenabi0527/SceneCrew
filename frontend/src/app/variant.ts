import { platform } from '../platform/runtime';
import { isPhoneBrowser, resolveUiVariant } from '../platform/ui-variant';

const KEY = 'scenecrew-ui';

function readStored() {
    try {
        return sessionStorage.getItem(KEY);
    } catch {
        return null;
    }
}

const phone = platform === 'web' && isPhoneBrowser(navigator.userAgent);

/** 앱 시작 시 한 번 정한다. 개발 서버·휴대폰의 `?ui=` 값은 같은 탭에서 유지한다. */
export const uiVariant = resolveUiVariant(
    platform,
    import.meta.env.DEV,
    window.location.search,
    readStored(),
    phone,
);

if (import.meta.env.DEV || phone) {
    try {
        sessionStorage.setItem(KEY, uiVariant);
    } catch {
        // 저장 실패 시 기본 화면으로 돌아간다.
    }
}
