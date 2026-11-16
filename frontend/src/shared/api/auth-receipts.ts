import { createApiTransport } from './client';
import { ContractViolationError, asRecord, asString, asUtcTimestamp, asUuid } from './contract';

export interface RegistrationReceipt {
    requestId: string;
    expiresAt: string;
}

// 로그인 전 일반 접수다. Account/Member 범위나 업무용 멱등 키·Job 조회를 만들지 않는다.
export function createAuthReceipts(base: string, transport?: typeof fetch) {
    const send = createApiTransport(base, transport);
    async function accept<T>(path: string, body: unknown, parse: (data: unknown) => T): Promise<T> {
        const result = await send<unknown>(path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        if (result.status !== 202) {
            throw new ContractViolationError('응답.status', '202 접수여야 합니다.');
        }
        return parse(result.data);
    }
    function registration(data: unknown): RegistrationReceipt {
        const body = asRecord(data, '응답');
        return {
            requestId: asUuid(body['requestId'], '응답.requestId'),
            expiresAt: asUtcTimestamp(body['expiresAt'], '응답.expiresAt'),
        };
    }
    return {
        register: (body: unknown) => accept('/auth/register', body, registration),
        resendVerification: (body: unknown) =>
            accept('/auth/resend-verification', body, registration),
        requestPasswordReset: (body: unknown) =>
            accept('/auth/password-reset/request', body, (data) => {
                const result = asRecord(data, '응답');
                return { message: asString(result['message'], '응답.message') };
            }),
    };
}
