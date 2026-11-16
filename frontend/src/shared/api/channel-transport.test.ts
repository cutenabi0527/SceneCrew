import { describe, expect, it, vi } from 'vitest';
import { createChannelTransport } from './channel-transport';

function capture() {
    let effective!: Request;
    const transport = vi.fn<typeof fetch>(async (input, init) => {
        // 실제 fetch와 같은 Request 병합을 거쳐 최종 전송 값을 검증한다.
        effective = new Request(input, init);
        return new Response(null, { status: 204 });
    });
    return { send: createChannelTransport('WEB', transport), request: () => effective };
}

describe('채널 전송의 Request/RequestInit 병합', () => {
    it.each(['login', 'refresh', 'logout'])(
        '%s POST Request의 JSON/본문을 보존하고 CSRF를 추가한다',
        async (path) => {
            const input = new Request(`https://scenecrew.test/api/v1/auth/${path}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'original' },
                body: '{}',
            });
            const { send, request } = capture();
            await send(input);
            expect(request().method).toBe('POST');
            expect(request().headers.get('Content-Type')).toBe('application/json');
            expect(request().headers.get('X-Request-Id')).toBe('original');
            expect(request().headers.get('X-SceneCrew-CSRF')).toBe('1');
            expect(request().headers.get('X-SceneCrew-Client')).toBe('WEB');
            expect(request().credentials).toBe('same-origin');
            expect(await request().text()).toBe('{}');
            expect(input.headers.has('X-SceneCrew-CSRF')).toBe(false);
        },
    );

    // BE AuthTransport는 WEB 공개 인증 POST 전부와 본인 정보 PATCH에 CSRF를 요구한다(#258 로컬 BE 확인).
    it.each([
        ['POST', 'auth/register'],
        ['POST', 'auth/verify-email'],
        ['POST', 'auth/resend-verification'],
        ['POST', 'auth/password-reset/request'],
        ['POST', 'auth/password-reset/confirm'],
        ['PATCH', 'me'],
        ['PATCH', 'me/consents'],
        ['POST', 'me/reauthenticate'],
        ['POST', 'me/identities/GOOGLE'],
        ['POST', 'me/identities/NAVER'],
        ['DELETE', 'me/identities/KAKAO'],
        ['POST', 'me/deactivate'],
        ['POST', 'invites/tok_abc-123/join'],
    ])('%s %s에도 CSRF를 붙인다', async (method, path) => {
        const { send, request } = capture();
        await send(`https://scenecrew.test/api/v1/${path}`, { method, body: '{}' });
        expect(request().headers.get('X-SceneCrew-CSRF')).toBe('1');
    });

    it.each([
        ['GET', 'me'],
        ['GET', 'auth/register'],
        ['POST', 'auth/password-reset'],
        ['POST', 'events'],
        ['POST', 'me/identities/LOCAL'],
        ['DELETE', 'me/identities/LOCAL'],
        ['DELETE', 'me/reauthenticate'],
        ['GET', 'me/deactivate'],
        ['GET', 'me/consents'],
        ['GET', 'invites/tok_abc-123/options'],
        ['POST', 'invites/a/b/join'],
    ])('%s %s에는 CSRF를 붙이지 않는다', async (method, path) => {
        const { send, request } = capture();
        await send(`https://scenecrew.test/api/v1/${path}`, {
            method,
            ...(method !== 'GET' && { body: '{}' }),
        });
        expect(request().headers.has('X-SceneCrew-CSRF')).toBe(false);
    });

    it('Android에는 공개 인증 POST에도 CSRF를 붙이지 않는다', async () => {
        let effective!: Request;
        const send = createChannelTransport('ANDROID', async (input, init) => {
            effective = new Request(input, init);
            return new Response(null, { status: 204 });
        });
        await send('https://scenecrew.test/api/v1/auth/register', { method: 'POST', body: '{}' });
        expect(effective.headers.has('X-SceneCrew-CSRF')).toBe(false);
    });

    it('init.method가 Request의 POST를 GET으로 덮어쓰면 CSRF를 붙이지 않는다', async () => {
        const input = new Request('https://scenecrew.test/api/v1/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
        });
        const { send, request } = capture();
        await send(input, { method: 'GET' });
        expect(request().method).toBe('GET');
        expect(request().headers.get('Content-Type')).toBe('application/json');
        expect(request().headers.has('X-SceneCrew-CSRF')).toBe(false);
    });

    it('init의 헤더/메서드/본문은 Request 값 전체를 재정의한다', async () => {
        const input = new Request('https://scenecrew.test/api/v1/auth/login', {
            headers: { 'Content-Type': 'text/plain', 'X-Original': 'old' },
        });
        const { send, request } = capture();
        await send(input, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: '{"new":true}',
        });
        expect(request().method).toBe('POST');
        expect(request().headers.get('Content-Type')).toBe('application/json');
        expect(request().headers.has('X-Original')).toBe(false);
        expect(request().headers.get('X-SceneCrew-CSRF')).toBe('1');
        expect(await request().text()).toBe('{"new":true}');
    });

    it('명시적으로 빈 init.headers를 주면 Request 헤더를 상속하지 않는다', async () => {
        const input = new Request('https://scenecrew.test/api/v1/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Original': 'old' },
        });
        const { send, request } = capture();
        await send(input, { headers: {} });
        expect(request().headers.has('Content-Type')).toBe(false);
        expect(request().headers.has('X-Original')).toBe(false);
        expect(request().headers.get('X-SceneCrew-CSRF')).toBe('1');
    });

    it('Request에서 상속한 Authorization에도 채널 혼합 방지를 적용한다', () => {
        const input = new Request('https://scenecrew.test/api/v1/events', {
            headers: { Authorization: 'Bearer other-channel' },
        });
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValue(new Response(null, { status: 204 }));
        expect(() => createChannelTransport('WEB', transport)(input)).toThrow(TypeError);
        expect(transport).not.toHaveBeenCalled();
    });
});
