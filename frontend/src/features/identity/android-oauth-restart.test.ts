import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { OAuthProofStore } from './android-oauth';
import { deviceVault, type CrashPoint } from './device-vault.testing';
import { RefreshNotAppliedError, type StoredRefresh } from './refresh-store';
import { IdentitySession, OAuthAttemptReplacedError } from './session';

// #388 앱 재시작 복구. 로그인 도중 어느 지점에서 프로세스가 종료돼도
// 다음 실행은 채택(정식 칸 확정)된 자격만 복원한다.

const base = 'https://scenecrew.test/api/v1';
const C = '00000000-0000-4000-8000-00000000000c';
const A = '00000000-0000-4000-8000-00000000000a';
const user = (id: string) => ({
    id,
    displayName: '계정',
    primaryEmail: null,
    primaryEmailVerified: false,
    status: 'ACTIVE',
});
const login = (id: string, refreshToken: string) => ({
    account: user(id),
    channel: 'ANDROID',
    accessExpiresAt: '2026-11-05T10:00:00Z',
    refreshExpiresAt: '2026-11-06T10:00:00Z',
    accessToken: `access-${refreshToken}`,
    refreshToken,
});
const json = (value: unknown, status = 200) =>
    new Response(JSON.stringify(value), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const startUrl = () => json({ authorizationUrl: 'https://kauth.kakao.com/oauth/authorize' });
const existingC: StoredRefresh = { accountId: C, refreshToken: 'refresh-c' };

type Device = ReturnType<typeof deviceVault>;

function proofStore(): OAuthProofStore {
    return {
        begin: async () => 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
        read: async () => 'test-verifier',
        clear: async () => undefined,
    };
}

function open(device: Device, fetcher = vi.fn<typeof fetch>()) {
    const session = new IdentitySession(
        'ANDROID',
        base,
        new ScopeManager(new QueryClient()),
        fetcher,
        device.store,
        proofStore(),
    );
    return { session, fetcher };
}

/** 앱 재시작: 같은 기기 저장소로 새 세션을 만들어 복원한다. 복원 요청은 보낸 Refresh의 계정으로 답한다. */
async function restart(device: Device) {
    device.restart();
    const refreshed: string[] = [];
    const fetcher = vi.fn<typeof fetch>(async (url, init) => {
        const path = new URL(String(url)).pathname;
        if (path.endsWith('/auth/refresh')) {
            const { refreshToken } = JSON.parse(String(init?.body)) as { refreshToken: string };
            refreshed.push(refreshToken);
            return json(login(refreshToken === 'refresh-c' ? C : A, `${refreshToken}-rotated`));
        }
        if (path.endsWith('/me')) return json(user(refreshed.at(-1) === 'refresh-c' ? C : A));
        throw new Error(`unexpected ${path}`);
    });
    const { session } = open(device, fetcher);
    await session.bootstrap();
    return { account: session.snapshot().account?.id ?? null, refreshed };
}

/** 기존 정상 세션 C로 로그인된 기기와 그 세션 */
async function deviceWithC() {
    const device = deviceVault();
    const opened = open(device);
    opened.fetcher.mockResolvedValueOnce(json(login(C, 'refresh-c')));
    await opened.session.oauthLogin('ticket-c');
    expect(device.main).toEqual(existingC);
    return { device, ...opened };
}

describe('BE 재현: A의 Refresh가 기기에 쓰인 직후 프로세스 종료(#388)', () => {
    it('기존 세션이 없으면 재시작은 로그인 안 된 상태로 시작하고 A의 Refresh를 서버에 보내지 않는다', async () => {
        const device = deviceVault();
        const { session, fetcher } = open(device);
        device.crashAt({ op: 'firstWrite', when: 'after' });
        fetcher.mockResolvedValueOnce(json(login(A, 'refresh-a')));
        void session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(device.calls.length).toBeGreaterThan(0));
        expect(await restart(device)).toEqual({ account: null, refreshed: [] });
    });

    it('기존 정상 세션 C가 있으면 재시작은 C로 복원한다', async () => {
        const { device, session, fetcher } = await deviceWithC();
        const before = device.calls.length;
        device.crashAt({ op: 'firstWrite', when: 'after' });
        fetcher.mockResolvedValueOnce(json(login(A, 'refresh-a')));
        void session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(device.calls.length).toBeGreaterThan(before));
        expect(await restart(device)).toEqual({ account: C, refreshed: ['refresh-c'] });
    });
});

describe('로그인 도중 모든 저장 지점에서 종료(#388)', () => {
    // A가 지금 시도인 경우. 정식 칸 확정(commit)이 끝나야 채택이다.
    it.each<[CrashPoint, string]>([
        [{ op: 'stage', when: 'before' }, C],
        [{ op: 'stage', when: 'after' }, C],
        [{ op: 'commit', when: 'before' }, C],
        [{ op: 'commit', when: 'after' }, A],
    ])('%j에서 종료하면 재시작 계정은 %s', async (point, expected) => {
        const { device, session, fetcher } = await deviceWithC();
        const before = device.calls.filter((op) => op === point.op).length;
        device.crashAt(point);
        fetcher.mockResolvedValueOnce(json(login(A, 'refresh-a')));
        void session.oauthLogin('ticket-a');
        await vi.waitFor(() =>
            expect(device.calls.filter((op) => op === point.op).length).toBe(before + 1),
        );
        expect((await restart(device)).account).toBe(expected);
    });

    it.each<CrashPoint>([
        { op: 'discard', when: 'before' },
        { op: 'discard', when: 'after' },
    ])('A 임시 저장 중 B를 시작한 뒤 %j에서 종료해도 재시작은 C로 복원한다', async (point) => {
        const { device, session, fetcher } = await deviceWithC();
        const release = device.pause('stage');
        fetcher.mockResolvedValueOnce(json(login(A, 'refresh-a')));
        void session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(device.isPaused('stage')).toBe(true));
        fetcher.mockResolvedValueOnce(startUrl());
        await session.oauthStart('KAKAO');
        device.crashAt(point);
        release();
        await vi.waitFor(() => expect(device.calls).toContain('discard'));
        expect((await restart(device)).account).toBe(C);
    });
});

describe('실행 중 순서(#388)', () => {
    it('A 임시 저장 중 B를 시작하면 A는 버려지고 C의 정식 칸·Access·계정이 그대로다', async () => {
        const { device, session, fetcher } = await deviceWithC();
        const release = device.pause('stage');
        fetcher.mockResolvedValueOnce(json(login(A, 'refresh-a')));
        const exchange = session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(device.isPaused('stage')).toBe(true));
        fetcher.mockResolvedValueOnce(startUrl());
        await session.oauthStart('KAKAO');
        release();
        await expect(exchange).rejects.toBeInstanceOf(OAuthAttemptReplacedError);
        expect(device.main).toEqual(existingC);
        expect(device.pending).toBeNull();
        expect(session.snapshot().account?.id).toBe(C);
        expect(session.liveAccessToken()).toBe('access-refresh-c');
    });

    it('A가 정식 칸을 확정하는 중 B를 시작하면 B는 확정이 끝난 뒤 시작한다(A는 이미 채택됨)', async () => {
        const { device, session, fetcher } = await deviceWithC();
        const release = device.pause('commit');
        fetcher.mockResolvedValueOnce(json(login(A, 'refresh-a')));
        const exchange = session.oauthLogin('ticket-a');
        await vi.waitFor(() => expect(device.isPaused('commit')).toBe(true));
        const calls = fetcher.mock.calls.length;
        fetcher.mockResolvedValueOnce(startUrl());
        const start = session.oauthStart('KAKAO');
        await new Promise((resolve) => setTimeout(resolve, 10));
        expect(fetcher.mock.calls.length).toBe(calls);
        release();
        expect(await exchange).toEqual({ kind: 'authenticated' });
        await start;
        expect(fetcher.mock.calls.length).toBe(calls + 1);
        expect(device.main).toEqual({ accountId: A, refreshToken: 'refresh-a' });
        expect(session.snapshot().account?.id).toBe(A);
    });

    it('진행 중인 교환 뒤에 기다리던 교환 여러 개는 거절되지 않고 차례로 처리된다', async () => {
        const device = deviceVault();
        const { session, fetcher } = open(device);
        const release = device.pause('stage');
        fetcher
            .mockResolvedValueOnce(json(login(C, 'refresh-1')))
            .mockResolvedValueOnce(json(login(C, 'refresh-2')))
            .mockResolvedValueOnce(json(login(C, 'refresh-3')));
        const first = session.oauthLogin('ticket-1');
        await vi.waitFor(() => expect(device.isPaused('stage')).toBe(true));
        const second = session.oauthLogin('ticket-2');
        const third = session.oauthLogin('ticket-3');
        await new Promise((resolve) => setTimeout(resolve, 10));
        release();
        const results = await Promise.allSettled([first, second, third]);
        expect(results.map((r) => r.status)).toEqual(['fulfilled', 'fulfilled', 'fulfilled']);
        expect(device.main).toEqual({ accountId: C, refreshToken: 'refresh-3' });
    });

    it('임시 저장이 실패하면 기존 정상 세션 C와 정식 칸을 유지한다', async () => {
        const { device, session, fetcher } = await deviceWithC();
        device.store.stage = async () => {
            throw new Error('disk full');
        };
        fetcher.mockResolvedValueOnce(json(login(A, 'refresh-a')));
        await expect(session.oauthLogin('ticket-a')).rejects.toThrow('저장');
        expect(device.main).toEqual(existingC);
        expect(session.snapshot().account?.id).toBe(C);
        expect(session.liveAccessToken()).toBe('access-refresh-c');
    });

    it('정식 칸을 건드리기 전에 확정이 실패하면(NOT_APPLIED) 기존 정상 세션 C를 유지한다', async () => {
        const { device, session, fetcher } = await deviceWithC();
        device.store.commit = async () => {
            throw new RefreshNotAppliedError();
        };
        fetcher.mockResolvedValueOnce(json(login(A, 'refresh-a')));
        await expect(session.oauthLogin('ticket-a')).rejects.toThrow('저장');
        expect(device.main).toEqual(existingC);
        expect(device.pending).toBeNull();
        expect(session.snapshot().account?.id).toBe(C);
        expect(session.liveAccessToken()).toBe('access-refresh-c');
    });

    it('정식 칸 쓰기 중 확정이 실패하면 결과를 알 수 없으므로 기기 로그인 정보를 모두 지운다', async () => {
        const { device, session, fetcher } = await deviceWithC();
        device.store.commit = async () => {
            throw new Error('disk full');
        };
        fetcher.mockResolvedValueOnce(json(login(A, 'refresh-a')));
        await expect(session.oauthLogin('ticket-a')).rejects.toThrow('저장');
        expect(device.main).toBeNull();
        expect(session.snapshot().account).toBeNull();
        expect(session.liveAccessToken()).toBeNull();
    });
});
