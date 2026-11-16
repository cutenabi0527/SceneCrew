import { describe, expect, it } from 'vitest';
import { isPhoneBrowser, resolveUiVariant } from './ui-variant';

describe('resolveUiVariant', () => {
    it('Android 빌드는 항상 APP 레이아웃이다', () => {
        expect(resolveUiVariant('android', false, '?ui=web', 'web')).toBe('app');
    });

    it('배포 WEB은 주소 값과 무관하게 WEB 레이아웃이다', () => {
        expect(resolveUiVariant('web', false, '?ui=app', 'app')).toBe('web');
    });

    it('개발 서버에서만 주소/저장값으로 APP 레이아웃을 미리 본다', () => {
        expect(resolveUiVariant('web', true, '?ui=app', null)).toBe('app');
        expect(resolveUiVariant('web', true, '', 'app')).toBe('app');
        expect(resolveUiVariant('web', true, '?ui=web', 'app')).toBe('web');
        expect(resolveUiVariant('web', true, '', null)).toBe('web');
    });

    it('휴대폰 브라우저는 배포 WEB에서도 APP 레이아웃이고 ?ui=web으로 PC 화면을 고를 수 있다(#402)', () => {
        expect(resolveUiVariant('web', false, '', null, true)).toBe('app');
        expect(resolveUiVariant('web', false, '?ui=web', null, true)).toBe('web');
        expect(resolveUiVariant('web', false, '', 'web', true)).toBe('web');
        expect(resolveUiVariant('web', false, '?ui=app', 'web', true)).toBe('app');
    });
});

describe('isPhoneBrowser(#402)', () => {
    it.each([
        ['Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile/15E148 Safari/604.1', true],
        ['Mozilla/5.0 (Linux; Android 14; SM-S918N) Chrome/130 Mobile Safari/537.36', true],
        ['Mozilla/5.0 (Linux; Android 14; SM-X710N) Chrome/130 Safari/537.36', false],
        ['Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) Mobile/15E148 Safari/604.1', false],
        ['Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari/605.1.15', false],
        ['Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130', false],
    ])('%s → %s', (ua, phone) => {
        expect(isPhoneBrowser(ua)).toBe(phone);
    });
});
