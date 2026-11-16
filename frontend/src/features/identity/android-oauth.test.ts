import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { ScopeManager } from '../../shared/scope/scope-manager';
import { appLinkRoute, type OAuthProofStore } from './android-oauth';
import type { RefreshStore } from './refresh-store';
import { deviceVault } from './device-vault.testing';
import { IdentitySession, OAuthAttemptReplacedError, OAuthProofMissingError } from './session';

const id = '00000000-0000-4000-8000-000000000001';
const user = {
    id,
    displayName: '소셜 계정',
    primaryEmail: null,
    primaryEmailVerified: false,
    status: 'ACTIVE',
};
const androidLogin = {
    account: user,
    channel: 'ANDROID',
    accessExpiresAt: '2026-11-05T10:00:00Z',
    refreshExpiresAt: '2026-11-06T10:00:00Z',
    accessToken: 'test-access',
    refreshToken: 'test-refresh',
};
const pending = {
    signupCompleted: false,
    signupTicket: 'signup-1',
    availableActions: ['COMPLETE_SIGNUP'],
};
const consent = { service: true, voice: false, push: false, externalAi: false };
const challenge = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM';
const json = (value: unknown, status = 200) =>
    new Response(JSON.stringify(value), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const failure = (status: number, code: string) =>
    json({ code, message: '시험 오류', requestId: 'r' }, status);

function setup(verifier: string | null = 'test-verifier') {
    const fetcher = vi.fn<typeof fetch>();
    const proof = {
        begin: vi.fn(async () => challenge),
        read: vi.fn(async () => verifier),
        clear: vi.fn(async () => undefined),
    } satisfies OAuthProofStore;
    // saved: 정식 칸에 확정된 로그인 자격 목록
    const saved: unknown[] = [];
    let staged: unknown = null;
    const refresh: RefreshStore = {
        take: async () => null,
        save: async (value) => {
            saved.push(value);
        },
        stage: async (value) => {
            staged = value;
        },
        commit: async () => {
            saved.push(staged);
            staged = null;
        },
        discard: async () => {
            staged = null;
        },
        clear: async () => undefined,
    };
    const session = new IdentitySession(
        'ANDROID',
        'https://scenecrew.test/api/v1',
        new ScopeManager(new QueryClient()),
        fetcher,
        refresh,
        proof,
    );
    const sentBody = (call = 0) => JSON.parse(String(fetcher.mock.calls[call][1]?.body));
    return { fetcher, proof, session, saved, sentBody };
}

describe('Android 소셜 로그인 시작 순서(#388)', () => {
    it('A의 시작 응답을 기다리는 동안 B를 시작하면 A는 주소를 돌려주지 않는다(브라우저·이동 경로 저장 없음)', async () => {
        const { fetcher, session } = setup();
        let answerA!: (r: Response) => void;
        fetcher.mockReturnValueOnce(
            new Promise<Response>((resolve) => {
                answerA = resolve;
            }),
        );
        const a = session.oauthStart('GOOGLE');
        await vi.waitFor(() => expect(fetcher).toHaveBeenCalledOnce());
        const urlB = 'https://kauth.kakao.com/oauth/authorize?state=b';
        fetcher.mockResolvedValueOnce(json({ authorizationUrl: urlB }));
        expect(await session.oauthStart('KAKAO')).toBe(urlB);
        answerA(json({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?state=a' }));
        await expect(a).rejects.toBeInstanceOf(OAuthAttemptReplacedError);
    });
});

describe('Android 소셜 로그인 시작(#388)', () => {
    it('새 증명을 만들고 S256 challenge만 보낸다', async () => {
        const { fetcher, proof, session } = setup();
        const url = 'https://accounts.google.com/o/oauth2/v2/auth?state=s';
        fetcher.mockResolvedValueOnce(json({ authorizationUrl: url }));
        expect(await session.oauthStart('GOOGLE')).toBe(url);
        expect(proof.begin).toHaveBeenCalledOnce();
        const sent = new URL(String(fetcher.mock.calls[0][0]));
        expect(sent.pathname).toBe('/api/v1/auth/oauth/GOOGLE/authorize');
        expect(Object.fromEntries(sent.searchParams)).toEqual({
            purpose: 'LOGIN',
            returnRoute: 'HOME',
            codeChallenge: challenge,
            codeChallengeMethod: 'S256',
        });
        expect(sent.search).not.toContain('test-verifier');
        const headers = new Headers(fetcher.mock.calls[0][1]?.headers);
        expect(headers.get('X-SceneCrew-Client')).toBe('ANDROID');
        expect(headers.has('X-SceneCrew-CSRF')).toBe(false);
    });

    it('증명 저장소가 없는 앱 빌드는 시작하지 않는다', async () => {
        const fetcher = vi.fn<typeof fetch>();
        const session = new IdentitySession(
            'ANDROID',
            'https://scenecrew.test/api/v1',
            new ScopeManager(new QueryClient()),
            fetcher,
        );
        expect(session.socialSupported).toBe(false);
        await expect(session.oauthStart('KAKAO')).rejects.toThrow('앱에서는');
        expect(fetcher).not.toHaveBeenCalled();
    });
});

describe('Android 소셜 티켓 교환(#388)', () => {
    it('증명을 더해 교환하고, 로그인되면 Refresh를 저장한 뒤 증명을 지운다', async () => {
        const { fetcher, proof, session, saved, sentBody } = setup();
        fetcher.mockResolvedValueOnce(json(androidLogin));
        expect(await session.oauthLogin('t-1')).toEqual({ kind: 'authenticated' });
        expect(sentBody()).toEqual({
            ticket: 't-1',
            action: 'LOGIN',
            codeVerifier: 'test-verifier',
        });
        expect(saved).toEqual([{ accountId: id, refreshToken: 'test-refresh' }]);
        expect(session.liveAccessToken()).toBe('test-access');
        expect(proof.clear).toHaveBeenCalledOnce();
    });

    it('증명이 없으면 서버에 보내지 않고 다시 시작하게 한다', async () => {
        const { fetcher, session } = setup(null);
        await expect(session.oauthLogin('t-2')).rejects.toBeInstanceOf(OAuthProofMissingError);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it('교환이 실패해도 증명은 지우지 않고(늦은 이전 시도의 실패일 수 있다) 계정도 열지 않는다', async () => {
        const { fetcher, proof, session, saved } = setup();
        fetcher.mockResolvedValueOnce(failure(401, 'UNAUTHENTICATED'));
        await expect(session.oauthLogin('t-3')).rejects.toThrow();
        expect(proof.clear).not.toHaveBeenCalled();
        expect(saved).toEqual([]);
        expect(session.snapshot().account).toBeNull();
    });

    it('가입 계속이면 가입 완료에 쓰도록 증명을 남기고, 가입하면 지운다', async () => {
        const { fetcher, proof, session, sentBody } = setup();
        fetcher.mockResolvedValueOnce(json(pending));
        expect((await session.oauthLogin('t-4')).kind).toBe('signup-required');
        expect(proof.clear).not.toHaveBeenCalled();
        fetcher.mockResolvedValueOnce(json(androidLogin));
        await session.completeSocialSignup('signup-1', '김현장', consent);
        expect(sentBody(1)).toEqual({
            ticket: 'signup-1',
            action: 'COMPLETE_SIGNUP',
            displayName: '김현장',
            consent,
            codeVerifier: 'test-verifier',
        });
        expect(proof.clear).toHaveBeenCalledOnce();
        expect(session.snapshot().account?.id).toBe(id);
    });

    it('가입 입력 오류(400 INVALID_REQUEST) 뒤 고쳐 다시 보내면 같은 증명으로 가입한다', async () => {
        const { fetcher, proof, session, sentBody } = setup();
        fetcher.mockResolvedValueOnce(failure(400, 'INVALID_REQUEST'));
        await expect(session.completeSocialSignup('signup-1', ' ', consent)).rejects.toThrow();
        expect(proof.clear).not.toHaveBeenCalled();
        fetcher.mockResolvedValueOnce(json(androidLogin));
        await session.completeSocialSignup('signup-1', '김현장', consent);
        expect(sentBody(1)).toMatchObject({ displayName: '김현장', codeVerifier: 'test-verifier' });
        expect(proof.clear).toHaveBeenCalledOnce();
        expect(session.snapshot().account?.id).toBe(id);
    });
});

describe('마지막 시도만 유효(#388)', () => {
    const later = () => {
        let resolve!: (r: Response) => void;
        const promise = new Promise<Response>((r) => {
            resolve = r;
        });
        return { promise, resolve };
    };
    const startUrl = () => json({ authorizationUrl: 'https://kauth.kakao.com/oauth/authorize' });

    it('A 교환 응답 전에 B를 시작하면 A의 성공은 Refresh를 저장하지 않고 B의 증명도 두고 간다', async () => {
        const { fetcher, proof, session, saved } = setup();
        const a = later();
        fetcher.mockReturnValueOnce(a.promise);
        const exchange = session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(fetcher).toHaveBeenCalledOnce());
        fetcher.mockResolvedValueOnce(startUrl());
        await session.oauthStart('KAKAO');
        a.resolve(json(androidLogin));
        await expect(exchange).rejects.toBeInstanceOf(OAuthAttemptReplacedError);
        expect(saved).toEqual([]);
        expect(session.snapshot().account).toBeNull();
        expect(session.liveAccessToken()).toBeNull();
        expect(proof.clear).not.toHaveBeenCalled();
    });

    it('A 교환 응답 전에 B를 시작하면 A의 가입 계속도 넘기지 않는다', async () => {
        const { fetcher, session } = setup();
        const a = later();
        fetcher.mockReturnValueOnce(a.promise);
        const exchange = session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(fetcher).toHaveBeenCalledOnce());
        fetcher.mockResolvedValueOnce(startUrl());
        await session.oauthStart('KAKAO');
        a.resolve(json(pending));
        await expect(exchange).rejects.toBeInstanceOf(OAuthAttemptReplacedError);
    });

    it('B를 시작한 뒤 도착한 A의 실패는 B의 증명을 지우지 않고, B는 그대로 로그인한다', async () => {
        const { fetcher, proof, session, saved } = setup();
        fetcher.mockResolvedValueOnce(startUrl());
        await session.oauthStart('KAKAO');
        // A의 늦은 티켓은 B의 증명과 맞지 않아 BE가 거절한다.
        fetcher.mockResolvedValueOnce(failure(401, 'UNAUTHENTICATED'));
        await expect(session.oauthLogin('ticket-a')).rejects.toThrow();
        expect(proof.clear).not.toHaveBeenCalled();
        fetcher.mockResolvedValueOnce(json(androidLogin));
        expect(await session.oauthLogin('ticket-b')).toEqual({ kind: 'authenticated' });
        expect(saved).toEqual([{ accountId: id, refreshToken: 'test-refresh' }]);
        expect(proof.clear).toHaveBeenCalledOnce();
    });

    it('로그인 실패는 기존 세션과 저장된 Refresh를 지우지 않는다', async () => {
        const { fetcher, session, saved } = setup();
        fetcher.mockResolvedValueOnce(json(androidLogin));
        await session.oauthLogin('ticket-1');
        fetcher.mockResolvedValueOnce(failure(401, 'UNAUTHENTICATED'));
        await expect(session.oauthLogin('ticket-late')).rejects.toThrow();
        expect(session.snapshot().account?.id).toBe(id);
        expect(session.liveAccessToken()).toBe('test-access');
        expect(saved).toHaveLength(1);
    });
});

describe('Refresh 저장 중 새 시도 시작(#388)', () => {
    const other = '00000000-0000-4000-8000-000000000009';
    const accountB = { ...user, id: other, displayName: 'B 계정' };
    const loginA = { ...androidLogin, accessToken: 'access-a', refreshToken: 'refresh-a' };
    const loginB = {
        ...androidLogin,
        account: accountB,
        accessToken: 'access-b',
        refreshToken: 'refresh-b',
    };
    const startUrl = () => json({ authorizationUrl: 'https://kauth.kakao.com/oauth/authorize' });

    /** 기기 저장소 흉내. 로그인의 다음 임시 저장 한 번을 멈춰 둘 수 있다. */
    function slowVault() {
        const device = deviceVault();
        return {
            store: device.store,
            peek: () => device.main,
            saving: () => device.isPaused('stage'),
            holdNextSave: () => device.pause('stage'),
        };
    }

    function setupSlow() {
        const fetcher = vi.fn<typeof fetch>();
        const vault = slowVault();
        const proof = {
            begin: vi.fn(async () => challenge),
            read: vi.fn(async () => 'test-verifier'),
            clear: vi.fn(async () => undefined),
        } satisfies OAuthProofStore;
        const session = new IdentitySession(
            'ANDROID',
            'https://scenecrew.test/api/v1',
            new ScopeManager(new QueryClient()),
            fetcher,
            vault.store,
            proof,
        );
        return { fetcher, vault, proof, session };
    }

    it('A의 Refresh 저장 중 B를 시작하면 A의 Refresh·Access·계정이 남지 않는다', async () => {
        const { fetcher, vault, proof, session } = setupSlow();
        const release = vault.holdNextSave();
        fetcher.mockResolvedValueOnce(json(loginA));
        const exchange = session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(vault.saving()).toBe(true));
        fetcher.mockResolvedValueOnce(startUrl());
        await session.oauthStart('KAKAO');
        release();
        await expect(exchange).rejects.toBeInstanceOf(OAuthAttemptReplacedError);
        expect(vault.peek()).toBeNull();
        expect(session.snapshot().account).toBeNull();
        expect(session.liveAccessToken()).toBeNull();
        expect(proof.clear).not.toHaveBeenCalled();
    });

    it('기존 정상 세션이 있으면 A를 되돌린 뒤 기존 세션의 Refresh·Access를 그대로 둔다', async () => {
        const { fetcher, vault, session } = setupSlow();
        fetcher.mockResolvedValueOnce(json(loginB));
        await session.oauthLogin('ticket-old');
        expect(vault.peek()).toEqual({ accountId: other, refreshToken: 'refresh-b' });

        const release = vault.holdNextSave();
        fetcher.mockResolvedValueOnce(json(loginA));
        const exchange = session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(vault.saving()).toBe(true));
        fetcher.mockResolvedValueOnce(startUrl());
        await session.oauthStart('KAKAO');
        release();
        await expect(exchange).rejects.toBeInstanceOf(OAuthAttemptReplacedError);
        expect(vault.peek()).toEqual({ accountId: other, refreshToken: 'refresh-b' });
        expect(session.snapshot().account?.id).toBe(other);
        expect(session.liveAccessToken()).toBe('access-b');
    });

    it('A를 되돌리는 동안 도착한 B의 교환은 A가 끝난 뒤 보내 B만 남는다', async () => {
        const { fetcher, vault, session } = setupSlow();
        const release = vault.holdNextSave();
        fetcher.mockResolvedValueOnce(json(loginA));
        const exchangeA = session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(vault.saving()).toBe(true));
        fetcher.mockResolvedValueOnce(startUrl());
        await session.oauthStart('KAKAO');
        fetcher.mockResolvedValueOnce(json(loginB));
        const exchangeB = session.oauthLogin('ticket-b');
        release();
        await expect(exchangeA).rejects.toBeInstanceOf(OAuthAttemptReplacedError);
        expect(await exchangeB).toEqual({ kind: 'authenticated' });
        expect(vault.peek()).toEqual({ accountId: other, refreshToken: 'refresh-b' });
        expect(session.snapshot().account?.id).toBe(other);
        expect(session.liveAccessToken()).toBe('access-b');
    });
});

describe('App Link 경로(#388)', () => {
    const host = 'service.p.example.test';
    it.each([
        ['https://service.p.example.test/app/oauth?ticket=abc', '/app/oauth?ticket=abc'],
        [
            'https://service.p.example.test/app/oauth?error=access_denied',
            '/app/oauth?error=access_denied',
        ],
        ['https://service.p.example.test/app/oauth?ticket=abc&next=/x', '/app/oauth?ticket=abc'],
        ['https://service.p.example.test/app/oauth', '/app/oauth'],
        ['https://service.p.example.test/invites/tok-1', '/invites/tok-1'],
        ['https://service.p.example.test/invites/tok-1?group=g-1', '/invites/tok-1?group=g-1'],
        ['https://service.p.example.test/invites/tok-1?group=g-1&next=/x', '/invites/tok-1?group=g-1'],
    ])('%s → %s', (url, route) => {
        expect(appLinkRoute(url, host)).toBe(route);
    });

    it.each([
        'https://evil.test/app/oauth?ticket=abc',
        'http://service.p.example.test/app/oauth?ticket=abc',
        'https://user@service.p.example.test/app/oauth?ticket=abc',
        'https://service.p.example.test/login/oauth?ticket=abc',
        'https://service.p.example.test/invites/a/b',
        'https://service.p.example.test/account/events',
        'not a url',
    ])('%s는 열지 않는다', (url) => {
        expect(appLinkRoute(url, host)).toBeNull();
    });
});
