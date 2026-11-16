import { describe, expect, it, vi } from 'vitest';
import { ApiError, createApiClient } from './client';

describe('공통 API의 복구/실패 경계', () => {
    it('본문을 읽는 중 취소한 요청은 응답 오류로 바꾸지 않는다', async () => {
        const response = new Response('{}', { headers: { 'Content-Type': 'application/json' } });
        const aborted = new DOMException('cancelled', 'AbortError');
        vi.spyOn(response, 'json').mockRejectedValue(aborted);
        const transport = vi.fn<typeof fetch>().mockResolvedValue(response);
        await expect(createApiClient('/api/v1', transport)('/events')).rejects.toBe(aborted);
    });
    it('2xx HTML 응답은 성공 데이터나 명령 실패로 추정하지 않는다', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(
            new Response('<html>proxy</html>', {
                status: 200,
                headers: { 'Content-Type': 'text/html' },
            }),
        );
        await expect(
            createApiClient('/api/v1', transport)('/issues', { method: 'POST' }),
        ).rejects.toMatchObject({ name: 'ApiResponseError', status: 200 });
        expect(transport).toHaveBeenCalledTimes(1);
    });
    it.each([401, 409, 503])('JSON 본문이 깨져도 HTTP %s 복구 분류를 보존한다', async (status) => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(
            new Response('{broken', {
                status,
                headers: { 'Content-Type': 'application/json' },
            }),
        );
        await expect(createApiClient('/api/v1', transport)('/issues')).rejects.toMatchObject({
            name: 'ApiError',
            status,
        });
        expect(transport).toHaveBeenCalledTimes(1);
    });
    it('성공 응답의 본문 유실은 HTTP 응답 수신과 처리 결과 불명을 구분한다', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(
            new Response('{broken', {
                status: 202,
                headers: { 'Content-Type': 'application/json' },
            }),
        );
        await expect(
            createApiClient('/api/v1', transport)('/issues', { method: 'POST' }),
        ).rejects.toMatchObject({ name: 'ApiResponseError', status: 202 });
        expect(transport).toHaveBeenCalledTimes(1);
    });
    it('URL 정규화로 공개 API 밖으로 이동하는 제어문자 경로를 전송하지 않는다', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValue(new Response(null, { status: 204 }));
        await expect(
            createApiClient('https://api.example/api/v1', transport)('/a/\t../\t../internal'),
        ).rejects.toThrow();
        expect(transport).not.toHaveBeenCalled();
    });
    it('정상적인 인코딩된 검색 값과 cursor는 경로 탈출로 거절하지 않는다', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValue(
                new Response('[]', { headers: { 'Content-Type': 'application/json' } }),
            );
        await expect(
            createApiClient('/api/v1', transport)('/events?cursor=a%2Fb&query=%2E'),
        ).resolves.toEqual([]);
    });
    it('응답 유실된 명령을 자동 재전송하지 않는다', async () => {
        const transport = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('network'));
        await expect(
            createApiClient('/api/v1', transport)('/issues', {
                method: 'POST',
                headers: { 'Idempotency-Key': 'same-intent' },
            }),
        ).rejects.toThrow('network');
        expect(transport).toHaveBeenCalledTimes(1);
    });
    it('서버 오류의 상태/내용을 유지하고 새 인증이나 재시도를 만들지 않는다', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(
            new Response(JSON.stringify({ code: 'VERSION_CONFLICT' }), {
                status: 409,
                headers: { 'Content-Type': 'application/json' },
            }),
        );
        await expect(createApiClient('/api/v1', transport)('/tasks/1')).rejects.toMatchObject({
            status: 409,
            payload: { code: 'VERSION_CONFLICT' },
        } satisfies Partial<ApiError>);
        expect(transport).toHaveBeenCalledTimes(1);
    });
    it.each([
        '//outside.test/x',
        '/../../internal',
        '/%2e%2e/internal',
        '/a\\b',
        'https://outside.test',
    ])('업무 API 범위를 벗어나는 %s는 보내지 않는다', async (path) => {
        const transport = vi.fn<typeof fetch>();
        await expect(createApiClient('/api/v1', transport)(path)).rejects.toThrow();
        expect(transport).not.toHaveBeenCalled();
    });
    it('취소 신호와 같은 의도의 키를 그대로 전달한다', async () => {
        const controller = new AbortController();
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValue(new Response(null, { status: 204 }));
        await createApiClient('/api/v1', transport)('/issues', {
            method: 'POST',
            signal: controller.signal,
            headers: { 'Idempotency-Key': 'same-intent' },
        });
        const options = transport.mock.calls[0][1]!;
        expect(options.signal).toBe(controller.signal);
        expect(new Headers(options.headers).get('Idempotency-Key')).toBe('same-intent');
    });
});
