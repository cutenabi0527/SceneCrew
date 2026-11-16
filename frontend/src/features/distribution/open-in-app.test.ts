import { describe, expect, it } from 'vitest';
import { inviteIntentUrl, isAndroidBrowser } from './open-in-app';

describe('초대 링크 앱으로 열기(#402)', () => {
    it.each([
        ['Mozilla/5.0 (Linux; Android 14; SM-S918N) Chrome/130 Mobile Safari/537.36', true],
        ['Mozilla/5.0 (Linux; Android 14; SM-X710N) Chrome/130 Safari/537.36', true],
        ['Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile/15E148', false],
        ['Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130', false],
    ])('%s → %s', (ua, android) => {
        expect(isAndroidBrowser(ua)).toBe(android);
    });

    it('앱이 있으면 같은 초대 링크로, 없으면 설치 안내로 보낸다', () => {
        expect(inviteIntentUrl('https://service.p.example.test', 'tok-1')).toBe(
            'intent://service.p.example.test/invites/tok-1#Intent;scheme=https;package=io.scenecrew.app;' +
                'S.browser_fallback_url=https%3A%2F%2Fservice.p.example.test%2Fdownload;end',
        );
    });

    it('그룹별 초대는 group을 옮기고 토큰은 다시 인코딩한다', () => {
        const url = inviteIntentUrl('https://service.p.example.test/', 'a b#c', 'g-1');
        expect(
            url.startsWith('intent://service.p.example.test/invites/a%20b%23c?group=g-1#Intent;'),
        ).toBe(true);
        expect(url).not.toContain('fallback_url=https%3A%2F%2Fservice.p.example.test%2Finvites');
    });
});
