export class ApiError extends Error {
    constructor(
        public readonly status: number,
        public readonly payload: unknown,
    ) {
        super('요청을 처리하지 못했습니다. 다시 확인해주세요.');
        this.name = 'ApiError';
    }
}

export class ApiResponseError extends Error {
    constructor(public readonly status: number) {
        super('서버 응답을 확인하지 못했습니다. 처리 결과를 확인해주세요.');
        this.name = 'ApiResponseError';
    }
}

// 요청을 보내기 전에 거절한 잘못된 경로. 서버에는 아무것도 전달되지 않았다(처리 여부 불명이 아니다).
export class InvalidApiPathError extends Error {
    constructor() {
        super('API 경로는 업무 서버 내부의 절대 경로여야 합니다.');
        this.name = 'InvalidApiPathError';
    }
}

export interface ApiResult<T> {
    // 2xx 상태 코드. 200/201과 202(접수)를 구별해야 하는 호출자가 쓴다.
    status: number;
    data: T | undefined;
}

// 인증 transport는 BE 계약 후 주입한다. 토큰 저장·자동 refresh·명령 retry는 하지 않는다.
function assertApiPath(path: string) {
    const pathname = path.split('?')[0];
    const segments = pathname.split('/');
    if (
        !/^\/[a-zA-Z0-9]/.test(path) ||
        pathname.includes('\\') ||
        /%2e|%2f|%5c/i.test(pathname) ||
        [...path].some((char) => char.charCodeAt(0) <= 31 || char.charCodeAt(0) === 127) ||
        segments.some((part) => part === '.' || part === '..') ||
        path.includes('#')
    ) {
        throw new InvalidApiPathError();
    }
}

export function createApiTransport(base: string, transport: typeof fetch = fetch) {
    return async function send<T>(path: string, init: RequestInit = {}): Promise<ApiResult<T>> {
        assertApiPath(path);
        const headers = new Headers(init.headers);
        headers.set('Accept', 'application/json');
        const response = await transport(`${base}${path}`, {
            ...init,
            headers,
            credentials: init.credentials ?? 'omit',
            redirect: 'error',
        });
        if (response.status === 204) return { status: 204, data: undefined };
        const isJson = response.headers.get('content-type')?.includes('json');
        let payload: unknown;
        let parsed = false;
        if (isJson) {
            try {
                payload = await response.json();
                parsed = true;
            } catch (error) {
                if (error instanceof Error && error.name === 'AbortError') throw error;
                // HTTP 실패는 본문 손상과 무관하게 상태를 보존한다.
                // 2xx 본문 손상은 명령 실패/미처리를 뜻하지 않는다.
                if (response.ok) throw new ApiResponseError(response.status);
            }
        }
        if (!response.ok) throw new ApiError(response.status, payload);
        if (!parsed) throw new ApiResponseError(response.status);
        return { status: response.status, data: payload as T };
    };
}

export interface BinaryResult {
    status: number;
    blob: Blob;
    contentType: string;
    headers: Headers;
}

/**
 * 이미지 같은 바이너리 GET. 경로 검사·인증 주입·오류 본문(JSON) 처리는 JSON 요청과 같다.
 * 기대한 형식(accept)이 아니면 받은 바이트를 쓰지 않는다.
 */
export function createBinaryTransport(base: string, transport: typeof fetch = fetch) {
    return async function sendBinary(
        path: string,
        accept: string,
        init: RequestInit = {},
    ): Promise<BinaryResult> {
        assertApiPath(path);
        const headers = new Headers(init.headers);
        headers.set('Accept', `${accept}, application/json`);
        const response = await transport(`${base}${path}`, {
            ...init,
            headers,
            credentials: init.credentials ?? 'omit',
            redirect: 'error',
        });
        const contentType = response.headers.get('content-type') ?? '';
        if (!response.ok) {
            let payload: unknown;
            if (contentType.includes('json'))
                payload = await response.json().catch(() => undefined);
            throw new ApiError(response.status, payload);
        }
        if (!contentType.toLowerCase().startsWith(accept))
            throw new ApiResponseError(response.status);
        return {
            status: response.status,
            blob: await response.blob(),
            contentType,
            headers: response.headers,
        };
    };
}

/**
 * text/event-stream GET. 헤더까지만 기다리고 본문 스트림을 그대로 돌려준다.
 * 실패 응답의 JSON 오류 본문은 ApiError로, 2xx인데 event-stream이 아니면 ApiResponseError로 낸다.
 */
export function createStreamTransport(base: string, transport: typeof fetch = fetch) {
    return async function sendStream(
        path: string,
        init: RequestInit = {},
    ): Promise<ReadableStream<Uint8Array>> {
        assertApiPath(path);
        const headers = new Headers(init.headers);
        headers.set('Accept', 'text/event-stream, application/json');
        const response = await transport(`${base}${path}`, {
            ...init,
            headers,
            credentials: init.credentials ?? 'omit',
            redirect: 'error',
            cache: 'no-store',
        });
        const contentType = response.headers.get('content-type') ?? '';
        if (!response.ok) {
            let payload: unknown;
            if (contentType.includes('json'))
                payload = await response.json().catch(() => undefined);
            throw new ApiError(response.status, payload);
        }
        if (!contentType.toLowerCase().startsWith('text/event-stream') || !response.body)
            throw new ApiResponseError(response.status);
        return response.body;
    };
}

// 본문만 필요한 호출자용. 동작은 이전과 같다.
export function createApiClient(base: string, transport: typeof fetch = fetch) {
    const send = createApiTransport(base, transport);
    return async function request<T>(path: string, init: RequestInit = {}): Promise<T | undefined> {
        return (await send<T>(path, init)).data;
    };
}
