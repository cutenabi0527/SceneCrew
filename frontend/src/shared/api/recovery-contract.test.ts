import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { IntentConflictError, IntentKeyStore } from '../scope/intent-keys';
import { ScopeManager } from '../scope/scope-manager';
import { createApi } from './api';
import { createAuthReceipts } from './auth-receipts';
import { createChannelTransport } from './channel-transport';
import { ContractViolationError } from './contract';

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const receipt = {
    requestId: 'dcc057ad-9a1d-5e78-b3c6-c6ea375c0452',
    expiresAt: '2026-11-18T12:00:00.000Z',
};
const spec = {
    operation: 'plan',
    intentId: 'form1',
    method: 'POST',
    path: '/events/e1/plan-generations',
    body: { expectedEventRevision: '1' },
    recovery: 'same-key',
} as const;

describe('candidate.3 요청 복구', () => {
    it.each([
        [409, 'IDEMPOTENCY_OUTCOME_UNKNOWN', 'unknown-outcome', 'reconcile'],
        [409, 'IDEMPOTENCY_RESULT_EXPIRED', 'result-expired', 'expired'],
        [410, 'TEXT_NOT_RETAINED', 'text-not-retained', 'reconcile'],
    ])('%s %s는 키를 보존하고 다음 호출을 조회로 전환한다', async (status, code, kind, next) => {
        const createKey = vi.fn(() => 'same-key');
        const scopes = new ScopeManager(new QueryClient(), new IntentKeyStore({ createKey }));
        const scope = scopes.setAccount('A')!;
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValue(
                json(status as number, { code, message: '확인', requestId: 'req1' }),
            );
        try {
            const build = () => createApi({ base: '/api/v1', scopes, transport });
            expect(await build().command(scope, spec)).toMatchObject({
                status: 'failed',
                failure: { kind, intent: 'pending', code },
            });
            expect(scopes.intentKeys.pending(scope)).toHaveLength(1);
            // 어댑터를 다시 만들어도 서버 복구 상태는 키 저장소에 남는다.
            expect(await build().command(scope, spec)).toEqual({ status: next });
            expect(createKey).toHaveBeenCalledTimes(1);
            expect(transport).toHaveBeenCalledTimes(1);
            if (next === 'expired') expect(scopes.intentKeys.pending(scope)[0].expired).toBe(true);
            if (next === 'reconcile')
                await expect(
                    build().command(scope, { ...spec, body: { expectedEventRevision: '2' } }),
                ).rejects.toBeInstanceOf(IntentConflictError);
        } finally {
            scopes.dispose();
        }
    });

    it('비대상 명령에는 키를 보내지 않고 same-key 설정에도 재전송하지 않는다', async () => {
        const scopes = new ScopeManager(new QueryClient());
        const scope = scopes.setAccount('A')!;
        const transport = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('연결 끊김'));
        try {
            const api = createApi({ base: '/api/v1', scopes, transport });
            await api.command(scope, { ...spec, idempotency: 'none' });
            expect(new Headers(transport.mock.calls[0][1]?.headers).has('Idempotency-Key')).toBe(
                false,
            );
            expect(await api.command(scope, { ...spec, idempotency: 'none' })).toEqual({
                status: 'reconcile',
            });
            expect(transport).toHaveBeenCalledTimes(1);
        } finally {
            scopes.dispose();
        }
    });

    it.each([
        undefined,
        '/other',
        'https://other.test/api/v1/ai-jobs/11111111-1111-4111-8111-111111111111',
    ])('잘못된 statusUrl %s의 키를 유지한다', async (statusUrl) => {
        const scopes = new ScopeManager(new QueryClient());
        const scope = scopes.setAccount('A')!;
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValue(
                json(202, { jobId: '11111111-1111-4111-8111-111111111111', statusUrl }),
            );
        try {
            expect(
                await createApi({ base: '/api/v1', scopes, transport }).command(scope, spec),
            ).toMatchObject({ status: 'failed', failure: { kind: 'unknown-outcome' } });
            expect(scopes.intentKeys.pending(scope)).toHaveLength(1);
        } finally {
            scopes.dispose();
        }
    });
});

describe('로그인 전 일반 인증202', () => {
    it.each(['register', 'resendVerification', 'requestPasswordReset'] as const)(
        '%s는 Job 없이 접수하고 키를 보내지 않는다',
        async (method) => {
            const expected =
                method === 'requestPasswordReset' ? { message: '접수했습니다.' } : receipt;
            const transport = vi.fn<typeof fetch>().mockResolvedValue(json(202, expected));
            const api = createAuthReceipts('/api/v1', transport);
            expect(await api[method]({ email: 'synthetic@example.test' })).toEqual(expected);
            expect(new Headers(transport.mock.calls[0][1]?.headers).has('Idempotency-Key')).toBe(
                false,
            );
            expect(transport).toHaveBeenCalledTimes(1);
        },
    );
    it.each([
        json(202, {}),
        json(200, receipt),
        json(202, { ...receipt, expiresAt: '2026-02-30T00:00:00Z' }),
    ])('잘못된 일반 접수는 계약 오류로 알린다', async (response) => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(response);
        await expect(createAuthReceipts('/api/v1', transport).register({})).rejects.toBeInstanceOf(
            ContractViolationError,
        );
        expect(transport).toHaveBeenCalledTimes(1);
    });
    it('전송 단절은 자동 재시도하지 않는다', async () => {
        const transport = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('연결 끊김'));
        await expect(
            createAuthReceipts('/api/v1', transport).requestPasswordReset({}),
        ).rejects.toThrow();
        expect(transport).toHaveBeenCalledTimes(1);
    });
});

describe('WEB/Android 전송 분리', () => {
    it('WEB은 동일 Origin 쿠키와 인증 POST의 CSRF를 사용한다', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(json(200, {}));
        const send = createChannelTransport('WEB', transport, () => 'android-token');
        for (const path of ['login', 'refresh', 'logout'])
            await send(`/api/v1/auth/${path}`, {
                method: 'POST',
                body: '{}',
                headers: { 'Content-Type': 'application/json' },
            });
        for (const [, init] of transport.mock.calls) {
            const h = new Headers(init?.headers);
            expect(init?.credentials).toBe('same-origin');
            expect(h.get('X-SceneCrew-CSRF')).toBe('1');
            expect(h.get('X-SceneCrew-Client')).toBe('WEB');
            expect(h.has('Authorization')).toBe(false);
        }
    });
    it('Android는 쿠키 없이 업무 Bearer를 보내고 갱신/로그아웃은 본문 증명을 사용한다', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(json(200, {}));
        const send = createChannelTransport('ANDROID', transport, () => 'access-token');
        await send('/api/v1/events', { headers: { 'X-SceneCrew-CSRF': '1' } });
        expect(new Headers(transport.mock.calls[0][1]?.headers).get('Authorization')).toBe(
            'Bearer access-token',
        );
        for (const path of ['login', 'refresh', 'logout'])
            await send(`/api/v1/auth/${path}`, { method: 'POST', body: '{}' });
        for (const [, init] of transport.mock.calls.slice(1))
            expect(new Headers(init?.headers).has('Authorization')).toBe(false);
        for (const [, init] of transport.mock.calls) {
            expect(init?.credentials).toBe('omit');
            expect(new Headers(init?.headers).get('X-SceneCrew-Client')).toBe('ANDROID');
            expect(new Headers(init?.headers).has('X-SceneCrew-CSRF')).toBe(false);
        }
    });
});
