// 세션/보안 저장소는 호출자가 관리한다. 이 어댑터는 채널별 전송 계약만 적용한다.
export function createChannelTransport(
    channel: 'WEB' | 'ANDROID',
    transport: typeof fetch = fetch,
    accessToken: () => string | null = () => null,
): typeof fetch {
    return (input, init = {}) => {
        const request = input instanceof Request ? input : undefined;
        // fetch는 init.headers가 있으면 Request 헤더 전체를 대체한다(병합하지 않는다).
        const headers = new Headers(init.headers ?? request?.headers);
        const method = init.method ?? request?.method ?? 'GET';
        if (headers.has('Authorization'))
            throw new TypeError('인증 수단은 채널 어댑터로 전달하세요.');
        headers.set('X-SceneCrew-Client', channel);
        headers.delete('X-SceneCrew-CSRF');
        const url =
            typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
        const path = new URL(url, 'https://scenecrew.invalid').pathname;
        const tokenProof = /^\/api\/v1\/auth\/(login|refresh|logout)$/.test(path);
        // BE는 WEB의 공개 인증 POST 전부와 본인 정보 PATCH에 CSRF 헤더를 요구한다(#258, AuthTransport).
        const publicAuth =
            /^\/api\/v1\/auth\/(register|verify-email|resend-verification|password-reset\/(request|confirm))$/.test(
                path,
            );
        // 동의 변경(#229, 017)도 프로필 수정과 같은 쓰기 검사를 받는다.
        const profileWrite = path === '/api/v1/me' || path === '/api/v1/me/consents';
        // 소셜 로그인 시작(GET)과 티켓 교환(POST)도 BE가 CSRF 헤더를 요구한다(#226, backend/docs/oauth-login.md).
        const oauthStart = /^\/api\/v1\/auth\/oauth\/(GOOGLE|KAKAO|NAVER)\/authorize$/.test(path);
        const oauthExchange = path === '/api/v1/auth/oauth/exchange';
        // 소셜 계정 연결의 본인 재확인·연결 시작도 프로필 수정과 같은 쓰기 검사를 받는다(#227).
        const reauthenticate = path === '/api/v1/me/reauthenticate';
        const identity = /^\/api\/v1\/me\/identities\/(GOOGLE|KAKAO|NAVER)$/.test(path);
        // 탈퇴(#334, 018)도 프로필 수정과 같은 쓰기 검사를 받는다.
        const deactivate = path === '/api/v1/me/deactivate';
        // 초대 링크 참여(#232, 035)도 BE가 프로필 수정과 같은 쓰기 검사를 한다(#353).
        const inviteJoin = /^\/api\/v1\/invites\/[^/]+\/join$/.test(path);
        if (channel === 'WEB') {
            // Origin은 브라우저가 붙인다. WEB은 동일 Origin API를 사용한다.
            const verb = method.toUpperCase();
            if (
                ((tokenProof ||
                    publicAuth ||
                    oauthExchange ||
                    reauthenticate ||
                    identity ||
                    deactivate ||
                    inviteJoin) &&
                    verb === 'POST') ||
                (identity && verb === 'DELETE') ||
                (profileWrite && verb === 'PATCH') ||
                (oauthStart && verb === 'GET')
            ) {
                headers.set('X-SceneCrew-CSRF', '1');
            }
        } else if (!tokenProof) {
            const token = accessToken();
            if (token) headers.set('Authorization', `Bearer ${token}`);
        }
        return transport(input, {
            ...init,
            headers,
            credentials: channel === 'WEB' ? 'same-origin' : 'omit',
        });
    };
}
