import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApi } from '../../shared/api/api';
import { IntentKeyStore } from '../../shared/scope/intent-keys';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { IdentitySession } from '../identity/session';
import { PushRegistrar, pushTarget, type PushNative, type PushStatus } from './registrar';

const deviceId = '10000000-0000-4000-8000-000000000091';
const json = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });

function fakeNative(permission: string, firebase = true) {
    let token: ((t: string) => void) | null = null;
    let open: ((data: Record<string, unknown>) => void) | null = null;
    const native: PushNative & {
        emit(t: string): void;
        tap(data?: Record<string, unknown>): void;
        asked: number;
    } = {
        asked: 0,
        checkPermissions: () => Promise.resolve({ receive: permission }),
        requestPermissions: () => {
            native.asked++;
            permission = 'granted';
            return Promise.resolve({ receive: permission });
        },
        register: vi.fn(() => Promise.resolve()),
        onToken: (l) => ((token = l), Promise.resolve(() => {})),
        onError: () => Promise.resolve(() => {}),
        onOpen: (l) => ((open = l), Promise.resolve(() => {})),
        deviceSecret: () => Promise.resolve({ deviceId, deviceProof: 'p'.repeat(43) }),
        available: () => Promise.resolve(firebase),
        emit: (t) => token?.(t),
        tap: (data = {}) => open?.(data),
    };
    return native;
}

describe('푸시 기기 등록', () => {
    let scopes: ScopeManager;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    const session = () =>
        ({
            api: createApi({ base: '/api/v1', transport, scopes }),
            scopes,
        }) as unknown as IdentitySession;
    const flush = () => new Promise((r) => setTimeout(r, 0));

    beforeEach(() => {
        transport = vi.fn<typeof fetch>();
        scopes = new ScopeManager(new QueryClient(), new IntentKeyStore());
        scopes.setAccount('A');
    });
    afterEach(() => scopes.dispose());

    it('다시 열 때는 권한을 묻지 않고, 허용 전이면 등록하지 않는다', async () => {
        const native = fakeNative('prompt');
        const statuses: PushStatus[] = [];
        const r = new PushRegistrar(session(), native, () => {});
        r.onStatus((s) => statuses.push(s));
        await r.resume();
        expect(native.asked).toBe(0);
        expect(native.register).not.toHaveBeenCalled();
        expect(statuses.at(-1)).toBe('needs-permission');
    });

    it('동의하면 권한을 묻고, 받은 토큰과 기기 비밀값을 키 없이 한 번만 보낸다', async () => {
        const native = fakeNative('prompt');
        const statuses: PushStatus[] = [];
        const r = new PushRegistrar(session(), native, () => {});
        r.onStatus((s) => statuses.push(s));
        await r.enable();
        expect(native.asked).toBe(1);
        expect(native.register).toHaveBeenCalledTimes(1);
        transport.mockResolvedValue(json(200, { deviceId, registered: true }));
        native.emit('fcm-token-1');
        await flush();
        await flush();
        const [url, init] = transport.mock.calls[0];
        expect(String(url)).toBe(`/api/v1/me/devices/${deviceId}`);
        expect(init!.method).toBe('PUT');
        expect(new Headers(init!.headers).get('Idempotency-Key')).toBeNull();
        expect(JSON.parse(String(init!.body))).toEqual({
            token: 'fcm-token-1',
            deviceProof: 'p'.repeat(43),
        });
        expect(String(url)).not.toContain('p'.repeat(43));
        expect(statuses.at(-1)).toBe('registered');
        // 같은 토큰이면 다시 보내지 않는다
        native.emit('fcm-token-1');
        await flush();
        expect(transport).toHaveBeenCalledTimes(1);
    });

    it('권한을 거절하면 등록하지 않는다', async () => {
        const native = fakeNative('denied');
        const r = new PushRegistrar(session(), native, () => {});
        let last: PushStatus = 'off';
        r.onStatus((s) => (last = s));
        await r.enable();
        expect(native.register).not.toHaveBeenCalled();
        expect(last).toBe('denied');
    });

    it('다른 기기의 등록 응답은 성공으로 보지 않고, 알림을 누르면 내 알림 화면을 연다', async () => {
        const native = fakeNative('granted');
        const open = vi.fn();
        const r = new PushRegistrar(session(), native, open);
        let last: PushStatus = 'off';
        r.onStatus((s) => (last = s));
        await r.resume();
        transport.mockResolvedValue(
            json(200, { deviceId: '10000000-0000-4000-8000-0000000000ff', registered: true }),
        );
        native.emit('fcm-token-2');
        await flush();
        await flush();
        expect(last).toBe('failed');
        native.tap();
        expect(open).toHaveBeenCalledWith(null);
    });

    it('알림을 누르면 data의 공연/알림 id를 넘기고, 형식이 다르면 버린다(#307)', async () => {
        const native = fakeNative('granted');
        const open = vi.fn();
        const r = new PushRegistrar(session(), native, open);
        await r.resume();
        const target = {
            eventId: '10000000-0000-4000-8000-000000000002',
            notificationId: '10000000-0000-4000-8000-000000000051',
        };
        native.tap(target);
        expect(open).toHaveBeenLastCalledWith(target);
        native.tap({ ...target, notificationId: '../../account/profile' });
        expect(open).toHaveBeenLastCalledWith(null);
        expect(pushTarget({ eventId: 1, notificationId: target.notificationId })).toBeNull();
    });

    it('Firebase 설정이 없는 빌드는 등록하지 않는다(네이티브 종료 방지, #307)', async () => {
        const native = fakeNative('prompt', false);
        const r = new PushRegistrar(session(), native, () => {});
        let last: PushStatus = 'off';
        r.onStatus((s) => (last = s));
        await r.resume();
        await r.enable();
        expect(native.register).not.toHaveBeenCalled();
        // 설정이 없는 빌드면 알림 권한도 묻지 않는다
        expect(native.asked).toBe(0);
        expect(last).toBe('unavailable');
    });

    it('로그아웃 직전 해제는 기기 증명을 헤더로 보내고, 그 뒤 같은 토큰도 다시 등록한다(#241)', async () => {
        const native = fakeNative('granted');
        const r = new PushRegistrar(session(), native, () => {});
        let last: PushStatus = 'off';
        r.onStatus((s) => (last = s));
        await r.resume();
        transport.mockResolvedValue(json(200, { deviceId, registered: true }));
        native.emit('fcm-token-3');
        await flush();
        await flush();
        expect(transport).toHaveBeenCalledTimes(1);

        transport.mockResolvedValueOnce(new Response(null, { status: 204 }));
        await r.unregister();
        const [url, init] = transport.mock.calls[1];
        expect(String(url)).toBe(`/api/v1/me/devices/${deviceId}`);
        expect(init!.method).toBe('DELETE');
        const headers = new Headers(init!.headers);
        expect(headers.get('X-Device-Proof')).toBe('p'.repeat(43));
        expect(headers.get('Idempotency-Key')).toBeNull();
        expect(init!.body).toBeUndefined();
        expect(String(url)).not.toContain('p'.repeat(43));
        expect(last).toBe('off');

        // 다시 로그인하면 같은 토큰이어도 PUT을 다시 보낸다
        transport.mockResolvedValue(json(200, { deviceId, registered: true }));
        await r.resume();
        native.emit('fcm-token-3');
        await flush();
        await flush();
        expect(transport).toHaveBeenCalledTimes(3);
        expect(transport.mock.calls[2][1]!.method).toBe('PUT');
    });

    it('해제가 실패해도 예외를 내지 않고, 재참여/재동의 때는 같은 토큰도 다시 등록한다', async () => {
        const native = fakeNative('granted');
        const r = new PushRegistrar(session(), native, () => {});
        await r.resume();
        transport.mockResolvedValue(json(200, { deviceId, registered: true }));
        native.emit('fcm-token-4');
        await flush();
        await flush();
        transport.mockRejectedValueOnce(new TypeError('network'));
        await expect(r.unregister()).resolves.toBeUndefined();

        await r.refresh();
        native.emit('fcm-token-4');
        await flush();
        await flush();
        await r.enable();
        native.emit('fcm-token-4');
        await flush();
        await flush();
        const methods = transport.mock.calls.map(([, init]) => init!.method);
        expect(methods).toEqual(['PUT', 'DELETE', 'PUT', 'PUT']);
    });

    it('계정 범위가 없으면 해제 요청을 보내지 않는다', async () => {
        const native = fakeNative('granted');
        const r = new PushRegistrar(session(), native, () => {});
        scopes.setAccount(null);
        await r.unregister();
        expect(transport).not.toHaveBeenCalled();
    });

    it('토큰이 바뀌면 새 토큰으로 다시 등록하고, 계정을 잊으면 같은 토큰도 다시 보낸다', async () => {
        const native = fakeNative('granted');
        const r = new PushRegistrar(session(), native, () => {});
        await r.resume();
        transport.mockResolvedValue(json(200, { deviceId, registered: true }));
        native.emit('fcm-token-5');
        await flush();
        await flush();
        native.emit('fcm-token-6');
        await flush();
        await flush();
        r.forget();
        native.emit('fcm-token-6');
        await flush();
        await flush();
        const tokens = transport.mock.calls.map(([, init]) => JSON.parse(String(init!.body)).token);
        expect(tokens).toEqual(['fcm-token-5', 'fcm-token-6', 'fcm-token-6']);
    });
});
