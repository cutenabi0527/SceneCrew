import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IntentKeyStore, IntentConflictError } from '../scope/intent-keys';
import { type Scope, ScopeDisposedError } from '../scope/scope';
import { ScopeManager } from '../scope/scope-manager';
import { createApi } from './api';
import { ContractViolationError, asBigIntString, asRecord } from './contract';
import type { Failure } from './failure';
import { SessionGate } from './session-gate';

const json = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
const failure = (status: number, code: string) =>
    json(status, { code, message: '안내', requestId: 'req-1' });
const text = (status: number, payload: string, type = 'application/json') =>
    new Response(payload, { status, headers: { 'Content-Type': type } });

function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((res) => (resolve = res));
    return { promise, resolve };
}

const HOUR = 3_600_000;

describe('공통 API 소비 경계', () => {
    let now = 1_000_000;
    let counter = 0;
    let scopes: ScopeManager;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    let onAccessDenied: ReturnType<typeof vi.fn<(scope: Scope, failure: Failure) => void>>;
    let session: SessionGate | undefined;
    const refresh = vi.fn();

    const build = () => createApi({ base: '/api/v1', transport, scopes, session, onAccessDenied });
    const header = (call: number, name: string) =>
        new Headers(transport.mock.calls[call][1]!.headers).get(name);

    beforeEach(() => {
        now = 1_000_000;
        counter = 0;
        refresh.mockReset();
        session = undefined;
        transport = vi.fn<typeof fetch>();
        onAccessDenied = vi.fn<(scope: Scope, failure: Failure) => void>();
        scopes = new ScopeManager(
            new QueryClient(),
            new IntentKeyStore({ now: () => now, createKey: () => `key-${++counter}` }),
        );
    });
    afterEach(() => scopes.dispose());

    describe('조회(GET)', () => {
        it('본문을 계약 규약으로 읽는다', async () => {
            const account = scopes.setAccount('A')!;
            transport.mockResolvedValue(json(200, { revision: '12' }));
            const revision = await build().get(account, '/events/e1', (data) =>
                asBigIntString(asRecord(data, '응답')['revision'], 'revision'),
            );
            expect(revision).toBe('12');
            expect(transport).toHaveBeenCalledWith(
                '/api/v1/events/e1',
                expect.objectContaining({ method: 'GET' }),
            );
        });

        it('revision이 숫자로 오면 계약 위반으로 막는다', async () => {
            const account = scopes.setAccount('A')!;
            transport.mockResolvedValue(json(200, { revision: 12 }));
            await expect(
                build().get(account, '/events/e1', (data) =>
                    asBigIntString(asRecord(data, '응답')['revision'], 'revision'),
                ),
            ).rejects.toBeInstanceOf(ContractViolationError);
        });

        it('범위가 끝나면 진행 중인 요청을 취소한다', async () => {
            const account = scopes.setAccount('A')!;
            let signal: AbortSignal | undefined;
            transport.mockImplementation((_url, init) => {
                signal = init?.signal ?? undefined;
                return new Promise<Response>((_resolve, reject) => {
                    signal?.addEventListener('abort', () => reject(signal!.reason));
                });
            });
            const pending = build().get(account, '/events', (data) => data);
            await Promise.resolve();
            scopes.setAccount('B');
            await expect(pending).rejects.toBeInstanceOf(ScopeDisposedError);
            expect(signal?.aborted).toBe(true);
        });

        it('계정 전환 뒤에 도착한 응답은 호출자에게 전달하지 않는다', async () => {
            const account = scopes.setAccount('A')!;
            const late = deferred<Response>();
            transport.mockReturnValue(late.promise);
            const parse = vi.fn((data: unknown) => data);
            const pending = build().get(account, '/me', parse);
            scopes.setAccount('B');
            late.resolve(json(200, { name: 'A의 데이터' }));
            await expect(pending).rejects.toBeInstanceOf(ScopeDisposedError);
        });

        it('호출자의 취소 signal도 요청을 취소한다', async () => {
            const account = scopes.setAccount('A')!;
            const controller = new AbortController();
            transport.mockImplementation(
                (_url, init) =>
                    new Promise<Response>((_resolve, reject) => {
                        init?.signal?.addEventListener('abort', () => reject(init.signal!.reason));
                    }),
            );
            const pending = build().get(account, '/events', (data) => data, {
                signal: controller.signal,
            });
            controller.abort(new DOMException('cancelled', 'AbortError'));
            await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
        });

        it('요청이 끝나면 범위 signal에 리스너를 남기지 않는다', async () => {
            const account = scopes.setAccount('A')!;
            const add = vi.spyOn(account.signal, 'addEventListener');
            const remove = vi.spyOn(account.signal, 'removeEventListener');
            transport.mockImplementation(async () => json(200, {}));
            for (let i = 0; i < 20; i++) await build().get(account, '/events', (data) => data);
            transport.mockImplementation(async () => failure(500, 'INTERNAL_ERROR'));
            for (let i = 0; i < 5; i++) {
                await build()
                    .get(account, '/events', (data) => data)
                    .catch(() => undefined);
            }
            expect(add.mock.calls.length).toBeGreaterThan(0);
            expect(remove.mock.calls.length).toBe(add.mock.calls.length);
        });

        it('403/404는 알림만 보내고 자동으로 행사를 떠나지 않는다', async () => {
            scopes.setAccount('A');
            const scope = scopes.setParticipation({
                eventId: 'e1',
                memberId: 'm',
                participationRevision: '1',
            }).scope!;
            transport.mockResolvedValue(failure(404, 'NOT_FOUND'));
            await expect(
                build().get(scope, '/events/e1/tasks/x', (data) => data),
            ).rejects.toMatchObject({ status: 404 });
            expect(onAccessDenied).toHaveBeenCalledTimes(1);
            expect(onAccessDenied.mock.calls[0][1]).toMatchObject({
                kind: 'access-denied',
                requestId: 'req-1',
            });
            expect(scopes.participation('e1')).toBe(scope);
            expect(scope.isCurrent()).toBe(true);
        });

        it('취소는 접근 거절로 알리지 않는다', async () => {
            const account = scopes.setAccount('A')!;
            // 실제 fetch처럼 abort되면 reject한다.
            transport.mockImplementation(
                (_url, init) =>
                    new Promise<Response>((_resolve, reject) => {
                        init?.signal?.addEventListener('abort', () => reject(init.signal!.reason));
                    }),
            );
            const pending = build().get(account, '/events', (data) => data);
            scopes.setAccount('B');
            await expect(pending).rejects.toBeInstanceOf(ScopeDisposedError);
            expect(onAccessDenied).not.toHaveBeenCalled();
        });
    });

    describe('조회의 401 복구', () => {
        it('복구에 성공하면 GET을 한 번만 다시 보낸다', async () => {
            const account = scopes.setAccount('A')!;
            refresh.mockResolvedValue('refreshed');
            session = new SessionGate(refresh);
            transport
                .mockResolvedValueOnce(failure(401, 'UNAUTHENTICATED'))
                .mockResolvedValueOnce(json(200, { ok: true }));
            await expect(build().get(account, '/me', (data) => data)).resolves.toEqual({
                ok: true,
            });
            expect(transport).toHaveBeenCalledTimes(2);
            expect(refresh).toHaveBeenCalledTimes(1);
        });

        it('다시 보낸 GET이 또 401이면 반복하지 않고 실패시킨다', async () => {
            const account = scopes.setAccount('A')!;
            refresh.mockResolvedValue('refreshed');
            session = new SessionGate(refresh);
            transport.mockResolvedValue(failure(401, 'UNAUTHENTICATED'));
            await expect(build().get(account, '/me', (data) => data)).rejects.toMatchObject({
                status: 401,
            });
            expect(transport).toHaveBeenCalledTimes(2);
            expect(refresh).toHaveBeenCalledTimes(1);
        });

        it('같은 시점의 여러 401은 refresh를 한 번만 보낸다', async () => {
            const account = scopes.setAccount('A')!;
            const refreshing = deferred<'refreshed'>();
            refresh.mockReturnValue(refreshing.promise);
            session = new SessionGate(refresh);
            let phase = 0;
            transport.mockImplementation(async () =>
                phase === 0 ? failure(401, 'UNAUTHENTICATED') : json(200, { ok: true }),
            );
            const api = build();
            const all = [1, 2, 3].map(() => api.get(account, '/me', (data) => data));
            await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
            phase = 1;
            refreshing.resolve('refreshed');
            await Promise.all(all);
            expect(refresh).toHaveBeenCalledTimes(1);
            expect(transport).toHaveBeenCalledTimes(6);
        });

        it('refresh 결과를 알 수 없으면 다시 보내지 않고 401을 그대로 낸다', async () => {
            const account = scopes.setAccount('A')!;
            refresh.mockRejectedValue(new TypeError('network'));
            session = new SessionGate(refresh);
            transport.mockResolvedValue(failure(401, 'UNAUTHENTICATED'));
            const api = build();
            await expect(api.get(account, '/me', (data) => data)).rejects.toMatchObject({
                status: 401,
            });
            await expect(api.get(account, '/me', (data) => data)).rejects.toMatchObject({
                status: 401,
            });
            expect(refresh).toHaveBeenCalledTimes(1);
            expect(transport).toHaveBeenCalledTimes(2);
        });

        it('복구하는 사이 계정이 바뀌면 다시 보낸 결과를 전달하지 않는다', async () => {
            const account = scopes.setAccount('A')!;
            const refreshing = deferred<'refreshed'>();
            refresh.mockReturnValue(refreshing.promise);
            session = new SessionGate(refresh);
            transport
                .mockResolvedValueOnce(failure(401, 'UNAUTHENTICATED'))
                .mockResolvedValueOnce(json(200, { name: 'A' }));
            const pending = build().get(account, '/me', (data) => data);
            await vi.waitFor(() => expect(refresh).toHaveBeenCalled());
            scopes.setAccount('B');
            refreshing.resolve('refreshed');
            await expect(pending).rejects.toBeInstanceOf(ScopeDisposedError);
        });

        it('세션 게이트가 없으면 401을 그대로 낸다', async () => {
            const account = scopes.setAccount('A')!;
            transport.mockResolvedValue(failure(401, 'UNAUTHENTICATED'));
            await expect(build().get(account, '/me', (data) => data)).rejects.toMatchObject({
                status: 401,
            });
            expect(transport).toHaveBeenCalledTimes(1);
        });
    });

    describe('바이너리 조회(getBinary)', () => {
        const jpeg = (headers: Record<string, string> = {}) =>
            new Response(new Uint8Array([0xff, 0xd8, 0xff]), {
                status: 200,
                headers: { 'Content-Type': 'image/jpeg', ...headers },
            });
        const event = () =>
            scopes.setParticipation({ eventId: 'e1', memberId: 'm', participationRevision: '1' })
                .scope!;
        beforeEach(() => scopes.setAccount('A'));

        it('기대한 형식의 바이트와 헤더를 돌려주고 Accept에 JSON 오류 형식을 함께 적는다', async () => {
            transport.mockResolvedValue(jpeg({ 'X-Frame-Freshness': 'FRESH' }));
            const result = await build().getBinary(
                event(),
                '/safety-cameras/c1/snapshot',
                'image/jpeg',
            );
            expect(result.headers.get('X-Frame-Freshness')).toBe('FRESH');
            expect(new Uint8Array(await result.blob.arrayBuffer())).toEqual(
                new Uint8Array([0xff, 0xd8, 0xff]),
            );
            expect(header(0, 'Accept')).toBe('image/jpeg, application/json');
        });
        it('2xx라도 기대한 형식이 아니면 바이트를 쓰지 않는다', async () => {
            transport.mockResolvedValue(text(200, '<html>proxy</html>', 'text/html'));
            await expect(
                build().getBinary(event(), '/safety-cameras/c1/snapshot', 'image/jpeg'),
            ).rejects.toMatchObject({ name: 'ApiResponseError', status: 200 });
        });
        it('JSON 오류 본문은 코드까지 읽고 403은 접근 거절로 알린다', async () => {
            transport.mockResolvedValue(failure(403, 'FORBIDDEN'));
            await expect(
                build().getBinary(event(), '/safety-cameras/c1/snapshot', 'image/jpeg'),
            ).rejects.toMatchObject({ status: 403, payload: { code: 'FORBIDDEN' } });
            expect(onAccessDenied).toHaveBeenCalledTimes(1);
        });
        it('401이면 한 번 복구하고 다시 받는다', async () => {
            refresh.mockResolvedValue('refreshed');
            session = new SessionGate(refresh);
            transport
                .mockResolvedValueOnce(failure(401, 'UNAUTHENTICATED'))
                .mockResolvedValueOnce(jpeg());
            await expect(
                build().getBinary(event(), '/safety-cameras/c1/snapshot', 'image/jpeg'),
            ).resolves.toMatchObject({ status: 200 });
            expect(transport).toHaveBeenCalledTimes(2);
        });
        it('범위가 끝나면 받는 중인 요청을 취소한다', async () => {
            const scope = event();
            transport.mockImplementation(
                (_url, init) =>
                    new Promise((_, reject) =>
                        init!.signal!.addEventListener('abort', () =>
                            reject(new DOMException('cancelled', 'AbortError')),
                        ),
                    ),
            );
            const pending = build().getBinary(scope, '/safety-cameras/c1/snapshot', 'image/jpeg');
            scopes.setAccount('B');
            await expect(pending).rejects.toBeDefined();
            expect(onAccessDenied).not.toHaveBeenCalled();
        });
    });

    describe('바이너리 보내기(postBlob, #396 카메라 사진)', () => {
        const event = () =>
            scopes.setParticipation({ eventId: 'e1', memberId: 'm', participationRevision: '1' })
                .scope!;
        const jpeg = () => new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' });
        const link = { 'X-SceneCrew-Link': 'l1', 'X-SceneCrew-Sequence': '1' };
        beforeEach(() => scopes.setAccount('A'));

        it('본문 형식을 Content-Type으로 적고 추가 헤더와 함께 바이트를 그대로 보낸다', async () => {
            transport.mockResolvedValue(json(202, { receivedAt: 't', sequence: '1' }));
            const body = jpeg();
            const ack = await build().postBlob(
                event(),
                '/safety-cameras/c1/frames',
                body,
                link,
                (d) => d as { sequence: string },
            );
            expect(ack.sequence).toBe('1');
            expect(transport.mock.calls[0][1]!.method).toBe('POST');
            expect(transport.mock.calls[0][1]!.body).toBe(body);
            expect(header(0, 'Content-Type')).toBe('image/jpeg');
            expect(header(0, 'X-SceneCrew-Link')).toBe('l1');
            expect(header(0, 'X-SceneCrew-Sequence')).toBe('1');
        });
        it('409 오류는 코드까지 읽어 그대로 던진다(보내기 모듈이 멈출지 정한다)', async () => {
            transport.mockResolvedValue(failure(409, 'CAMERA_LINK_TAKEN'));
            await expect(
                build().postBlob(event(), '/safety-cameras/c1/frames', jpeg(), link, (d) => d),
            ).rejects.toMatchObject({ status: 409, payload: { code: 'CAMERA_LINK_TAKEN' } });
            expect(transport).toHaveBeenCalledTimes(1);
        });
        it('401이면 한 번 복구하고 같은 사진을 다시 보낸다', async () => {
            refresh.mockResolvedValue('refreshed');
            session = new SessionGate(refresh);
            transport
                .mockResolvedValueOnce(failure(401, 'UNAUTHENTICATED'))
                .mockResolvedValueOnce(json(202, { receivedAt: 't', sequence: '1' }));
            const body = jpeg();
            await build().postBlob(event(), '/safety-cameras/c1/frames', body, link, (d) => d);
            expect(transport).toHaveBeenCalledTimes(2);
            expect(transport.mock.calls[1][1]!.body).toBe(body);
        });
    });

    describe('명령: 같은 의도와 키', () => {
        const spec = {
            operation: 'create-issue',
            recovery: 'same-key',
            intentId: 'form-1',
            method: 'POST',
            path: '/events/e1/issues',
            body: { title: '문제', expectedRevision: '12' },
        } as const;
        const event = () =>
            scopes.setParticipation({ eventId: 'e1', memberId: 'm', participationRevision: '1' })
                .scope!;

        beforeEach(() => scopes.setAccount('A'));

        it('Idempotency-Key와 JSON 본문을 보내고, 인증 정보나 서명을 임의로 붙이지 않는다', async () => {
            transport.mockResolvedValue(json(201, { id: 'x' }));
            const outcome = await build().command(event(), spec);
            expect(outcome).toMatchObject({
                status: 'succeeded',
                data: { id: 'x' },
                resumed: false,
            });
            expect(header(0, 'Idempotency-Key')).toBe('key-1');
            expect(header(0, 'Content-Type')).toBe('application/json');
            expect(header(0, 'Authorization')).toBeNull();
            const init = transport.mock.calls[0][1]!;
            expect(init.method).toBe('POST');
            expect(init.body).toBe(JSON.stringify(spec.body));
            expect(init.credentials).toBe('omit');
            expect(init.signal).toBeUndefined();
        });

        it('본문이 없는 명령은 Content-Type을 붙이지 않는다', async () => {
            transport.mockResolvedValue(new Response(null, { status: 204 }));
            const outcome = await build().command(event(), {
                ...spec,
                method: 'DELETE',
                body: undefined,
            });
            expect(outcome).toMatchObject({ status: 'succeeded', data: undefined });
            expect(header(0, 'Content-Type')).toBeNull();
        });

        it('확정 성공 뒤에는 키를 정리한다', async () => {
            transport.mockResolvedValue(json(201, { id: 'x' }));
            const scope = event();
            await build().command(scope, spec);
            expect(scopes.intentKeys.pending(scope)).toEqual([]);
        });

        it('전송이 끊기면 자동으로 다시 보내지 않고, 같은 의도의 명시 재전송은 같은 키/같은 본문이다', async () => {
            const scope = event();
            transport.mockRejectedValueOnce(new TypeError('network'));
            const first = await build().command(scope, spec);
            expect(first).toMatchObject({
                status: 'failed',
                failure: { kind: 'unknown-outcome', intent: 'pending' },
                resumed: false,
            });
            // 자동 재전송은 없다.
            await new Promise((resolve) => setTimeout(resolve, 20));
            expect(transport).toHaveBeenCalledTimes(1);

            transport.mockResolvedValueOnce(json(201, { id: 'x' }));
            const second = await build().command(scope, spec);
            expect(second).toMatchObject({ status: 'succeeded', resumed: true });
            expect(header(1, 'Idempotency-Key')).toBe(header(0, 'Idempotency-Key'));
            expect(transport.mock.calls[1][1]!.body).toBe(transport.mock.calls[0][1]!.body);
        });

        it('2xx인데 본문이 깨지면 실패로 단정하지 않고 같은 키로 확인하게 한다', async () => {
            const scope = event();
            transport.mockResolvedValueOnce(text(201, '{broken'));
            const first = await build().command(scope, spec);
            expect(first).toMatchObject({
                status: 'failed',
                failure: { kind: 'unknown-outcome', status: 201, intent: 'pending' },
            });
            transport.mockResolvedValueOnce(json(200, { id: 'x' }));
            const second = await build().command(scope, spec);
            expect(second).toMatchObject({ status: 'succeeded', resumed: true });
            expect(header(1, 'Idempotency-Key')).toBe('key-1');
        });

        it('2xx 응답이 계약(BIGINT 문자열)과 다르면 처리 불명으로 두고 키를 유지한다', async () => {
            const scope = event();
            transport.mockResolvedValueOnce(json(200, { revision: 13 }));
            const outcome = await build().command(scope, spec, (data) =>
                asBigIntString(asRecord(data, '응답')['revision'], 'revision'),
            );
            expect(outcome).toMatchObject({
                status: 'failed',
                failure: { kind: 'unknown-outcome', intent: 'pending' },
            });
            expect(scopes.intentKeys.pending(scope)).toHaveLength(1);
        });

        it('401은 키를 유지하고 자동으로 복구해 다시 보내지 않는다', async () => {
            const scope = event();
            refresh.mockResolvedValue('refreshed');
            session = new SessionGate(refresh);
            transport.mockResolvedValueOnce(failure(401, 'UNAUTHENTICATED'));
            const outcome = await build().command(scope, spec);
            expect(outcome).toMatchObject({
                status: 'failed',
                failure: { kind: 'unauthenticated', intent: 'pending' },
            });
            expect(refresh).not.toHaveBeenCalled();
            expect(transport).toHaveBeenCalledTimes(1);
            expect(scopes.intentKeys.pending(scope)).toHaveLength(1);
        });

        it('409 REQUEST_IN_PROGRESS는 새 키를 만들지 않고 같은 의도를 유지한다', async () => {
            const scope = event();
            transport.mockResolvedValueOnce(failure(409, 'REQUEST_IN_PROGRESS'));
            const outcome = await build().command(scope, spec);
            expect(outcome).toMatchObject({
                status: 'failed',
                failure: { kind: 'request-in-progress', intent: 'pending' },
            });
            transport.mockResolvedValueOnce(json(200, { id: 'x' }));
            await build().command(scope, spec);
            expect(header(1, 'Idempotency-Key')).toBe('key-1');
            expect(counter).toBe(1);
        });

        it('409 VERSION_CONFLICT는 확정 거절이라 키를 정리하고, 다시 하려면 새 의도로 시작한다', async () => {
            const scope = event();
            transport.mockResolvedValueOnce(failure(409, 'VERSION_CONFLICT'));
            const outcome = await build().command(scope, spec);
            expect(outcome).toMatchObject({
                status: 'failed',
                failure: { kind: 'version-conflict', intent: 'finished', requestId: 'req-1' },
            });
            expect(scopes.intentKeys.pending(scope)).toEqual([]);
            // 사용자가 최신 내용을 확인하고 명시적으로 새 의도를 시작한다.
            transport.mockResolvedValueOnce(json(201, { id: 'x' }));
            await build().command(scope, {
                ...spec,
                intentId: 'form-2',
                body: { ...spec.body, expectedRevision: '13' },
            });
            expect(header(1, 'Idempotency-Key')).toBe('key-2');
        });

        it('503은 저장 여부를 확인해야 하므로 키를 유지한다', async () => {
            const scope = event();
            transport.mockResolvedValueOnce(failure(503, 'SERVICE_UNAVAILABLE'));
            const outcome = await build().command(scope, spec);
            expect(outcome).toMatchObject({
                status: 'failed',
                failure: { kind: 'unavailable', intent: 'pending' },
            });
            expect(scopes.intentKeys.pending(scope)).toHaveLength(1);
        });

        it('같은 의도에서 요청 내용을 바꾸면 보내지 않고 거절한다(새 키 자동 발급 금지)', async () => {
            const scope = event();
            transport.mockRejectedValueOnce(new TypeError('network'));
            await build().command(scope, spec);
            await expect(
                build().command(scope, { ...spec, body: { ...spec.body, title: '다른 제목' } }),
            ).rejects.toBeInstanceOf(IntentConflictError);
            expect(transport).toHaveBeenCalledTimes(1);
            expect(counter).toBe(1);
        });

        it('24시간이 지나면 보내지 않고 새 키도 만들지 않으며 만료로 알린다', async () => {
            const scope = event();
            transport.mockRejectedValueOnce(new TypeError('network'));
            await build().command(scope, spec);
            now += 24 * HOUR;
            const outcome = await build().command(scope, spec);
            expect(outcome).toEqual({ status: 'expired' });
            expect(transport).toHaveBeenCalledTimes(1);
            expect(counter).toBe(1);
            // 현재 상태로 결과를 확인한 뒤에야 정리하고, 그 뒤 시작한 새 의도만 새 키를 받는다.
            scopes.intentKeys.release(scope, spec.operation, spec.intentId);
            transport.mockResolvedValueOnce(json(201, { id: 'x' }));
            await build().command(scope, spec);
            expect(header(1, 'Idempotency-Key')).toBe('key-2');
        });

        it('오프라인 보류 명령을 연결 복구 때 자동으로 실행하지 않는다', async () => {
            const scope = event();
            transport.mockRejectedValue(new TypeError('offline'));
            await build().command(scope, spec);
            const { onlineManager } = await import('@tanstack/react-query');
            onlineManager.setOnline(false);
            onlineManager.setOnline(true);
            await new Promise((resolve) => setTimeout(resolve, 20));
            expect(transport).toHaveBeenCalledTimes(1);
        });

        it('보내기 전에 거절한 경로는 서버가 받지 않았다고 구별한다', async () => {
            const scope = event();
            const outcome = await build().command(scope, { ...spec, path: '//outside.test/x' });
            expect(outcome).toMatchObject({
                status: 'failed',
                failure: { kind: 'not-sent', intent: 'finished' },
            });
            expect(transport).not.toHaveBeenCalled();
            expect(scopes.intentKeys.pending(scope)).toEqual([]);
        });
    });

    describe('명령: Job 접수', () => {
        const jobId = '11111111-1111-4111-8111-111111111111';
        const spec = {
            operation: 'start-job',
            recovery: 'same-key',
            intentId: 'job-1',
            method: 'POST',
            path: '/events/e1/ai-jobs',
            body: { kind: 'GENERATE_PLAN' },
        } as const;
        const event = () =>
            scopes.setParticipation({ eventId: 'e1', memberId: 'm', participationRevision: '1' })
                .scope!;
        beforeEach(() => scopes.setAccount('A'));

        it('202 + jobId는 접수로 알리고 완료로 세지 않는다', async () => {
            transport.mockResolvedValue(
                json(202, { jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }),
            );
            const outcome = await build().command(event(), spec);
            expect(outcome).toMatchObject({ status: 'accepted', jobId });
            expect(scopes.intentKeys.pending(event())).toEqual([]);
            if (outcome.status === 'accepted') {
                // 공개 statusUrl과 접수 결과를 함께 보존한다.
                expect(outcome.data).toEqual({ jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` });
            }
        });

        it('202인데 jobId가 없으면 접수 여부를 믿지 않고 같은 키로 확인하게 한다', async () => {
            const scope = event();
            transport.mockResolvedValue(json(202, { id: 'job-9' }));
            const outcome = await build().command(scope, spec);
            expect(outcome).toMatchObject({
                status: 'failed',
                failure: { kind: 'unknown-outcome', intent: 'pending' },
            });
            expect(scopes.intentKeys.pending(scope)).toHaveLength(1);
        });

        it('접수 뒤 AI 실패(AI_TIMEOUT)는 접수 실패로 만들지 않는다', async () => {
            // 접수는 202로 끝났다. 이후 실패는 Job 상태 조회에서 오며 명령 결과가 아니다.
            transport.mockResolvedValue(
                json(202, { jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }),
            );
            const outcome = await build().command(event(), spec);
            expect(outcome.status).toBe('accepted');
        });

        it.each(['', 'not-a-uuid', 123, null, undefined])(
            '202의 잘못된 jobId(%s)는 처리 미확인으로 남기고 같은 키를 유지한다',
            async (invalidJobId) => {
                const scope = event();
                transport.mockResolvedValueOnce(json(202, { jobId: invalidJobId }));
                const api = build();
                expect(await api.command(scope, spec)).toMatchObject({
                    status: 'failed',
                    failure: { kind: 'unknown-outcome', intent: 'pending' },
                });
                expect(scopes.intentKeys.pending(scope)).toHaveLength(1);
                expect(transport).toHaveBeenCalledTimes(1);

                transport.mockResolvedValueOnce(
                    json(202, { jobId, statusUrl: `/api/v1/ai-jobs/${jobId}` }),
                );
                expect(await api.command(scope, spec)).toMatchObject({
                    status: 'accepted',
                    jobId,
                    resumed: true,
                });
                expect(header(1, 'Idempotency-Key')).toBe(header(0, 'Idempotency-Key'));
                expect(scopes.intentKeys.pending(scope)).toEqual([]);
            },
        );
    });

    describe('비멱등/미확정 명령의 복구', () => {
        it.each([undefined, 'state-query'] as const)(
            '복구 방식 %s는 결과 불명 뒤 재전송하지 않고 상태 조회를 요구한다',
            async (recovery) => {
                const scope = scopes.setAccount('A')!;
                const spec = {
                    operation: 'change-status',
                    intentId: 'status-1',
                    method: 'PATCH',
                    path: '/tasks/t1',
                    body: { status: 'DONE' },
                    recovery,
                } as const;
                const api = build();
                transport.mockRejectedValueOnce(new TypeError('network'));
                expect(await api.command(scope, spec)).toMatchObject({
                    status: 'failed',
                    failure: { intent: 'pending' },
                });
                expect(await api.command(scope, spec)).toEqual({ status: 'reconcile' });
                expect(transport).toHaveBeenCalledTimes(1);
                expect(scopes.intentKeys.pending(scope)).toHaveLength(1);
                // 결과 불명 뒤 same-key로 바꿔서 보호를 우회할 수 없다.
                await expect(
                    api.command(scope, { ...spec, recovery: 'same-key' }),
                ).rejects.toBeInstanceOf(IntentConflictError);

                transport.mockResolvedValueOnce(json(200, { status: 'DONE' }));
                expect(await api.get(scope, '/tasks/t1', (data) => data)).toEqual({
                    status: 'DONE',
                });
                // 호출자는 조회 결과로 의도가 완료됐음을 확인한 뒤 명시적으로 정리한다.
                scopes.intentKeys.release(scope, spec.operation, spec.intentId);
                expect(scopes.intentKeys.pending(scope)).toEqual([]);
            },
        );
    });

    describe('범위와 명령', () => {
        const spec = {
            operation: 'create-issue',
            recovery: 'same-key',
            intentId: 'form-1',
            method: 'POST',
            path: '/events/e1/issues',
            body: { title: '문제' },
        } as const;

        it('전송한 명령은 계정이 바뀌어도 끊지 않고 결과만 새 화면으로 넘기지 않는다', async () => {
            const account = scopes.setAccount('A')!;
            const late = deferred<Response>();
            transport.mockReturnValue(late.promise);
            const pending = build().command(account, spec);
            await Promise.resolve();
            scopes.setAccount('B');
            late.resolve(json(201, { id: 'x' }));
            await expect(pending).rejects.toBeInstanceOf(ScopeDisposedError);
            expect(transport).toHaveBeenCalledTimes(1);
            expect(transport.mock.calls[0][1]!.signal).toBeUndefined();
        });

        it('계정 전환 중에 끝난 명령의 키는 그 계정에 남아 돌아오면 이어서 확인할 수 있다', async () => {
            const account = scopes.setAccount('A')!;
            transport.mockReturnValue(new Promise(() => undefined));
            void build()
                .command(account, spec)
                .catch(() => undefined);
            await Promise.resolve();
            scopes.setAccount('B');
            const again = scopes.setAccount('A')!;
            expect(scopes.intentKeys.pending(again)).toMatchObject([
                { operation: 'create-issue', intentId: 'form-1' },
            ]);
            transport.mockResolvedValueOnce(json(200, { id: 'x' }));
            const outcome = await build().command(again, spec);
            expect(outcome).toMatchObject({ status: 'succeeded', resumed: true });
            expect(header(1, 'Idempotency-Key')).toBe('key-1');
        });

        it('종료된 범위의 옛 화면은 명령을 보낼 수 없다', async () => {
            const account = scopes.setAccount('A')!;
            scopes.setAccount('B');
            await expect(build().command(account, spec)).rejects.toBeInstanceOf(ScopeDisposedError);
            expect(transport).not.toHaveBeenCalled();
        });

        it('다른 계정의 같은 의도는 키가 섞이지 않는다', async () => {
            const a = scopes.setAccount('A')!;
            transport.mockRejectedValueOnce(new TypeError('network'));
            await build().command(a, spec);
            const b = scopes.setAccount('B')!;
            transport.mockResolvedValueOnce(json(201, { id: 'x' }));
            await build().command(b, spec);
            expect(header(0, 'Idempotency-Key')).toBe('key-1');
            expect(header(1, 'Idempotency-Key')).toBe('key-2');
        });

        it('접근 거절(403)은 의도를 끝내고 알림을 보낸다', async () => {
            scopes.setAccount('A');
            const scope = scopes.setParticipation({
                eventId: 'e1',
                memberId: 'm',
                participationRevision: '1',
            }).scope!;
            transport.mockResolvedValueOnce(failure(403, 'FORBIDDEN'));
            const outcome = await build().command(scope, spec);
            expect(outcome).toMatchObject({
                status: 'failed',
                failure: { kind: 'access-denied', intent: 'finished' },
            });
            expect(onAccessDenied).toHaveBeenCalledTimes(1);
            expect(scope.isCurrent()).toBe(true);
        });
    });
});
