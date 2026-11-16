import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApi } from '../api/api';
import { IntentKeyStore } from '../scope/intent-keys';
import type { Scope } from '../scope/scope';
import { ScopeManager } from '../scope/scope-manager';
import type { Failure } from '../api/failure';
import { parseInvalidation } from './invalidation';
import { SseParser } from './sse';

const EVENT = '10000000-0000-4000-8000-000000000002';
const scope = { eventId: EVENT, participationRevision: '1' };

describe('SSE 해석', () => {
    it('조각으로 나뉜 이벤트와 \\r\\n 줄 끝, 주석/여러 줄 data를 읽는다', () => {
        const p = new SseParser();
        expect(p.push(': ping\r\nevent: invalidate\r\nda')).toEqual([]);
        expect(p.push('ta: {"a":1}\r')).toEqual([]);
        const out = p.push('\ndata: next\r\n\r\n');
        expect(out).toEqual([{ event: 'invalidate', data: '{"a":1}\nnext', id: null }]);
        expect(p.push('data:x\n\n')).toEqual([{ event: 'message', data: 'x', id: null }]);
    });
    it('data가 없는 빈 이벤트는 내보내지 않는다', () => {
        expect(new SseParser().push('event: invalidate\n\n')).toEqual([]);
    });
});

describe('무효화 알림 검사', () => {
    const body = (v: unknown) => JSON.stringify(v);
    it('현재 공연/참여 회차의 계약 자원만 받는다', () => {
        expect(
            parseInvalidation(body({ ...scope, resources: ['TASKS', 'MY_CHANGES'] }), scope),
        ).toEqual(new Set(['TASKS', 'MY_CHANGES']));
        expect(
            parseInvalidation(
                body({ ...scope, participationRevision: '2', resources: ['TASKS'] }),
                scope,
            ),
        ).toBeNull();
        expect(
            parseInvalidation(body({ ...scope, eventId: 'x', resources: ['TASKS'] }), scope),
        ).toBeNull();
        expect(parseInvalidation(body({ ...scope, resources: ['SECRET'] }), scope)).toBeNull();
        expect(parseInvalidation(body({ ...scope, resources: [] }), scope)).toBeNull();
        expect(
            parseInvalidation(body({ ...scope, resources: ['TASKS'], text: '원문' }), scope),
        ).toBeNull();
        expect(parseInvalidation('{broken', scope)).toBeNull();
    });
});

describe('이벤트 스트림 요청(openStream)', () => {
    let scopes: ScopeManager;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    let onAccessDenied: ReturnType<typeof vi.fn<(scope: Scope, failure: Failure) => void>>;
    beforeEach(() => {
        transport = vi.fn<typeof fetch>();
        onAccessDenied = vi.fn<(scope: Scope, failure: Failure) => void>();
        scopes = new ScopeManager(new QueryClient(), new IntentKeyStore());
        scopes.setAccount('A');
    });
    afterEach(() => scopes.dispose());
    const event = () =>
        scopes.setParticipation({ eventId: EVENT, memberId: 'm', participationRevision: '1' })
            .scope!;
    const api = () => createApi({ base: '/api/v1', transport, scopes, onAccessDenied });

    it('event-stream 본문을 돌려주고 Accept에 JSON 오류 형식을 함께 적는다', async () => {
        transport.mockResolvedValue(
            new Response('event: invalidate\ndata: {}\n\n', {
                headers: { 'Content-Type': 'text/event-stream' },
            }),
        );
        const { body, release } = await api().openStream(event(), `/events/${EVENT}/stream`);
        expect(await new Response(body).text()).toContain('invalidate');
        release();
        expect(new Headers(transport.mock.calls[0][1]!.headers).get('Accept')).toBe(
            'text/event-stream, application/json',
        );
    });
    it('2xx라도 event-stream이 아니면 쓰지 않고, 403은 접근 거절로 알린다', async () => {
        transport.mockResolvedValueOnce(
            new Response('<html>', { headers: { 'Content-Type': 'text/html' } }),
        );
        await expect(api().openStream(event(), `/events/${EVENT}/stream`)).rejects.toMatchObject({
            name: 'ApiResponseError',
        });
        transport.mockResolvedValueOnce(
            new Response(JSON.stringify({ code: 'FORBIDDEN', message: 'x', requestId: 'r' }), {
                status: 403,
                headers: { 'Content-Type': 'application/json' },
            }),
        );
        await expect(api().openStream(event(), `/events/${EVENT}/stream`)).rejects.toMatchObject({
            status: 403,
        });
        expect(onAccessDenied).toHaveBeenCalledTimes(1);
    });
    it('범위가 끝나면 읽는 중인 스트림도 끊긴다', async () => {
        let aborted = false;
        transport.mockImplementation((_url, init) => {
            init!.signal!.addEventListener('abort', () => (aborted = true));
            return Promise.resolve(
                new Response(new ReadableStream(), {
                    headers: { 'Content-Type': 'text/event-stream' },
                }),
            );
        });
        await api().openStream(event(), `/events/${EVENT}/stream`);
        scopes.setAccount('B');
        expect(aborted).toBe(true);
    });
});
