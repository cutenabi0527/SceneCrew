import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createChannelTransport } from '../../shared/api/channel-transport';
import { ScopeManager } from '../../shared/scope/scope-manager';
import { signupNameError } from './consent';
import { IdentitySession } from './session';
import { peekTarget, safeTarget, saveTarget, takeTarget } from './social-target';

const id = '00000000-0000-4000-8000-000000000001';
const user = {
    id,
    displayName: '소셜 계정',
    primaryEmail: null,
    primaryEmailVerified: false,
    status: 'ACTIVE',
};
const login = {
    account: user,
    channel: 'WEB',
    accessExpiresAt: '2026-11-05T10:00:00Z',
    refreshExpiresAt: '2026-11-06T10:00:00Z',
};
const json = (value: unknown, status = 200) =>
    new Response(JSON.stringify(value), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });

function setup(channel: 'WEB' | 'ANDROID' = 'WEB') {
    const fetcher = vi.fn<typeof fetch>();
    const session = new IdentitySession(
        channel,
        '/api/v1',
        new ScopeManager(new QueryClient()),
        fetcher,
    );
    return { fetcher, session };
}

describe('소셜 로그인 전송 규칙', () => {
    const sent = async (method: string, path: string) => {
        let effective!: Request;
        const send = createChannelTransport('WEB', async (input, init) => {
            effective = new Request(input, init);
            return new Response(null, { status: 204 });
        });
        await send(`https://scenecrew.test/api/v1/auth/oauth/${path}`, { method });
        return effective.headers;
    };

    it('시작 GET과 교환 POST에만 CSRF를 붙인다', async () => {
        expect((await sent('GET', 'KAKAO/authorize')).get('X-SceneCrew-CSRF')).toBe('1');
        expect((await sent('POST', 'exchange')).get('X-SceneCrew-CSRF')).toBe('1');
        expect((await sent('GET', 'KAKAO/callback')).has('X-SceneCrew-CSRF')).toBe(false);
        expect((await sent('POST', 'GITHUB/authorize')).has('X-SceneCrew-CSRF')).toBe(false);
    });
});

describe('소셜 로그인 시작', () => {
    it('LOGIN/HOME으로 시작하고 제공자의 공식 인증 주소만 돌려준다', async () => {
        const { fetcher, session } = setup();
        const url = 'https://kauth.kakao.com/oauth/authorize?client_id=x&state=s';
        fetcher.mockResolvedValueOnce(json({ authorizationUrl: url }));
        expect(await session.oauthStart('KAKAO')).toBe(url);
        expect(String(fetcher.mock.calls[0][0])).toBe(
            '/api/v1/auth/oauth/KAKAO/authorize?purpose=LOGIN&returnRoute=HOME',
        );
        expect(fetcher.mock.calls[0][1]?.credentials).toBe('same-origin');
    });

    it.each([
        ['다른 제공자 호스트', 'https://nid.naver.com/oauth2/authorize'],
        ['http', 'http://kauth.kakao.com/oauth/authorize'],
        ['사용자 정보가 붙은 주소', 'https://account@example.invalid/oauth/authorize'],
        ['비슷한 호스트', 'https://kauth.kakao.com.evil.test/oauth/authorize'],
    ])('%s로는 이동하지 않는다', async (_label, url) => {
        const { fetcher, session } = setup();
        fetcher.mockResolvedValueOnce(json({ authorizationUrl: url }));
        await expect(session.oauthStart('KAKAO')).rejects.toThrow();
    });

    it('알 수 없는 필드가 붙은 응답과 Android는 거절한다', async () => {
        const web = setup();
        web.fetcher.mockResolvedValueOnce(
            json({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth', extra: 1 }),
        );
        await expect(web.session.oauthStart('GOOGLE')).rejects.toThrow();
        const app = setup('ANDROID');
        await expect(app.session.oauthStart('GOOGLE')).rejects.toThrow('앱에서는');
        expect(app.fetcher).not.toHaveBeenCalled();
    });
});

describe('소셜 로그인 티켓 교환', () => {
    it('티켓을 LOGIN으로 교환하면 이메일 로그인과 같은 방식으로 계정을 연다', async () => {
        const { fetcher, session } = setup();
        fetcher.mockResolvedValueOnce(json(login));
        await session.authenticate('/auth/oauth/exchange', { ticket: 't-1', action: 'LOGIN' });
        expect(session.snapshot().account?.id).toBe(id);
        const [url, init] = fetcher.mock.calls[0];
        expect(String(url)).toBe('/api/v1/auth/oauth/exchange');
        expect(init?.method).toBe('POST');
        expect(JSON.parse(String(init?.body))).toEqual({ ticket: 't-1', action: 'LOGIN' });
        expect(new Headers(init?.headers).get('X-SceneCrew-CSRF')).toBe('1');
    });

    it('교환이 401이면 계정을 열지 않는다', async () => {
        const { fetcher, session } = setup();
        fetcher.mockResolvedValueOnce(
            json({ code: 'UNAUTHENTICATED', message: 'x', requestId: 'r' }, 401),
        );
        await expect(
            session.authenticate('/auth/oauth/exchange', { ticket: 't-2', action: 'LOGIN' }),
        ).rejects.toThrow();
        expect(session.snapshot().account).toBeNull();
    });

    it('Cookie 세션이 없어 복구가 401·회전을 거치는 중에 교환해도 멈추지 않는다', async () => {
        // 복귀 화면의 교환이 복구(/me 401 → /auth/refresh 401)와 겹치는 순서
        const { fetcher, session } = setup();
        const unauthenticated = () =>
            json({ code: 'UNAUTHENTICATED', message: 'x', requestId: 'r' }, 401);
        fetcher.mockImplementation(async (input) => {
            const path = String(input);
            if (path.endsWith('/me') || path.endsWith('/auth/refresh')) return unauthenticated();
            return json(login);
        });
        const exchange = session.oauthLogin('t-boot');
        void session.bootstrap();
        const settled = await Promise.race([
            exchange,
            new Promise((resolve) => setTimeout(() => resolve('stuck'), 1000)),
        ]);
        expect(settled).toEqual({ kind: 'authenticated' });
        expect(session.snapshot().account?.id).toBe(id);
    });
});

describe('미연결 소셜 계정의 가입 계속(#379)', () => {
    const pending = {
        signupCompleted: false,
        signupTicket: 'signup-1',
        availableActions: ['LOGIN_EXISTING', 'COMPLETE_SIGNUP'],
    };
    const consent = { service: true, voice: false, push: true, externalAi: false };

    it('LOGIN 교환이 OAuthPending이면 세션을 열지 않고 가입 정보를 돌려준다', async () => {
        const { fetcher, session } = setup();
        fetcher.mockResolvedValueOnce(
            json({ ...pending, signupExpiresAt: '2026-11-10T05:05:00Z', provider: 'KAKAO' }),
        );
        const outcome = await session.oauthLogin('t-3');
        expect(outcome).toEqual({
            kind: 'signup-required',
            pending: {
                signupTicket: 'signup-1',
                availableActions: ['LOGIN_EXISTING', 'COMPLETE_SIGNUP'],
                signupExpiresAt: '2026-11-10T05:05:00Z',
                provider: 'KAKAO',
            },
        });
        expect(session.snapshot().account).toBeNull();
        expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual({
            ticket: 't-3',
            action: 'LOGIN',
        });
    });

    it('연결된 계정이면 LOGIN 교환으로 바로 로그인한다', async () => {
        const { fetcher, session } = setup();
        fetcher.mockResolvedValueOnce(json(login));
        expect(await session.oauthLogin('t-4')).toEqual({ kind: 'authenticated' });
        expect(session.snapshot().account?.id).toBe(id);
    });

    it.each([
        ['로그인 정보가 섞인 가입 계속', { ...pending, account: user }],
        ['signupCompleted가 true', { ...pending, signupCompleted: true }],
        ['빈 가입 티켓', { ...pending, signupTicket: '' }],
        ['선택지가 없음', { ...pending, availableActions: [] }],
        ['모르는 선택지', { ...pending, availableActions: ['MERGE_BY_EMAIL'] }],
    ])('%s는 계약 위반으로 거절한다', async (_label, body) => {
        const { fetcher, session } = setup();
        fetcher.mockResolvedValueOnce(json(body));
        await expect(session.oauthLogin('t-5')).rejects.toThrow();
        expect(session.snapshot().account).toBeNull();
    });

    it('가입 완료는 가입 티켓/이름/동의 4개를 보내고 재로그인 없이 계정을 연다', async () => {
        const { fetcher, session } = setup();
        fetcher.mockResolvedValueOnce(json(login));
        await session.completeSocialSignup('signup-1', '김현장', consent);
        expect(session.snapshot().account?.id).toBe(id);
        const [url, init] = fetcher.mock.calls[0];
        expect(String(url)).toBe('/api/v1/auth/oauth/exchange');
        expect(JSON.parse(String(init?.body))).toEqual({
            ticket: 'signup-1',
            action: 'COMPLETE_SIGNUP',
            displayName: '김현장',
            consent,
        });
        expect(new Headers(init?.headers).get('X-SceneCrew-CSRF')).toBe('1');
    });

    it('가입 완료 응답이 또 가입 계속이면 성공으로 보지 않는다', async () => {
        const { fetcher, session } = setup();
        fetcher.mockResolvedValueOnce(json(pending));
        await expect(session.completeSocialSignup('signup-1', '김현장', consent)).rejects.toThrow();
        expect(session.snapshot().account).toBeNull();
    });

    it('가입 완료가 400이면 계정을 열지 않는다(화면은 입력을 지킨 채 다시 보내게 한다)', async () => {
        const { fetcher, session } = setup();
        fetcher.mockResolvedValueOnce(
            json({ code: 'INVALID_REQUEST', message: 'x', requestId: 'r' }, 400),
        );
        await expect(session.completeSocialSignup('signup-1', ' ', consent)).rejects.toThrow();
        expect(session.snapshot().account).toBeNull();
    });
});

describe('소셜 가입 이름 검사', () => {
    it.each([
        ['김현장', null],
        ['  김현장  ', null],
        ['가'.repeat(20), null],
        ['😀'.repeat(20), null],
        ['', '이름을 입력해 주세요.'],
        ['   ', '이름을 입력해 주세요.'],
        ['가'.repeat(21), '이름은 20자까지 입력할 수 있습니다.'],
        ['김\n현장', '이름에 줄바꿈이나 제어 문자를 넣을 수 없습니다.'],
        ['김 현장', '이름에 줄바꿈이나 제어 문자를 넣을 수 없습니다.'],
    ])('%j → %s', (value, expected) => {
        expect(signupNameError(value)).toBe(expected);
    });
});

describe('소셜 로그인 뒤 갈 화면', () => {
    // 단위 시험은 Node 환경이라 브라우저 세션 저장소를 흉내 낸다.
    beforeEach(() => {
        const store = new Map<string, string>();
        vi.stubGlobal('sessionStorage', {
            getItem: (k: string) => store.get(k) ?? null,
            setItem: (k: string, v: string) => store.set(k, v),
            removeItem: (k: string) => store.delete(k),
        });
    });
    afterEach(() => vi.unstubAllGlobals());

    it('초대 링크만 기억하고, 한 번 꺼내면 지운다', () => {
        saveTarget('/invites/abc');
        expect(takeTarget()).toBe('/invites/abc');
        expect(takeTarget()).toBe('/account/events');
        expect(safeTarget('https://evil.test')).toBe('/account/events');
        expect(safeTarget('/invites/abc?x=1')).toBe('/account/events');
    });

    it('실패·취소 복귀는 읽기만 해서 진행 중인 다른 시도의 화면을 남긴다(#388)', () => {
        saveTarget('/invites/abc');
        expect(peekTarget()).toBe('/invites/abc');
        expect(peekTarget()).toBe('/invites/abc');
        expect(takeTarget()).toBe('/invites/abc');
        expect(peekTarget()).toBe('/account/events');
    });
});
