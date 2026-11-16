import { describe, expect, it } from 'vitest';
import { ScopeDisposedError } from '../scope/scope';
import { ApiError, ApiResponseError, InvalidApiPathError } from './client';
import { ContractViolationError, parseErrorBody, readEventIncompleteFields } from './contract';
import { type FailureKind, classifyFailure } from './failure';

const body = (code: string) => ({ code, message: '안내 문구', requestId: 'req-1' });

// BE08 오류 코드표의 "복구" 열을 그대로 옮긴 표. [상태, 코드, 기대 분류, 의도]
const TABLE: [number, string | null, FailureKind, 'finished' | 'pending'][] = [
    [409, 'IDEMPOTENCY_OUTCOME_UNKNOWN', 'unknown-outcome', 'pending'],
    [409, 'IDEMPOTENCY_RESULT_EXPIRED', 'result-expired', 'pending'],
    [410, 'TEXT_NOT_RETAINED', 'text-not-retained', 'pending'],
    [400, 'INVALID_REQUEST', 'rejected', 'finished'],
    [401, 'UNAUTHENTICATED', 'unauthenticated', 'pending'],
    [403, 'FORBIDDEN', 'access-denied', 'finished'],
    [404, 'NOT_FOUND', 'access-denied', 'finished'],
    [409, 'VERSION_CONFLICT', 'version-conflict', 'finished'],
    [409, 'INVALID_STATE', 'rejected', 'finished'],
    [409, 'EVENT_FULL', 'rejected', 'finished'],
    [409, 'REQUEST_IN_PROGRESS', 'request-in-progress', 'pending'],
    [409, 'IDEMPOTENCY_MISMATCH', 'idempotency-mismatch', 'pending'],
    [409, 'EMAIL_ALREADY_REGISTERED', 'rejected', 'finished'],
    [405, 'METHOD_NOT_ALLOWED', 'rejected', 'finished'],
    [413, 'PAYLOAD_TOO_LARGE', 'rejected', 'finished'],
    [415, 'UNSUPPORTED_MEDIA_TYPE', 'rejected', 'finished'],
    [422, 'TIME_OVERLAP', 'rejected', 'finished'],
    [422, 'EVENT_INCOMPLETE', 'rejected', 'finished'],
    [429, 'RATE_LIMITED', 'rate-limited', 'pending'],
    [503, 'AI_UNAVAILABLE', 'unavailable', 'pending'],
    [503, 'SERVICE_UNAVAILABLE', 'unavailable', 'pending'],
    // 코드표에 없는 5xx는 서버가 어디까지 처리했는지 모른다.
    [500, 'INTERNAL_ERROR', 'unknown-outcome', 'pending'],
    [502, null, 'unknown-outcome', 'pending'],
    [504, null, 'unknown-outcome', 'pending'],
];

describe('BE08 오류 코드표 분류', () => {
    it.each(TABLE)('HTTP %i %s → %s (%s)', (status, code, kind, intent) => {
        const failure = classifyFailure(new ApiError(status, code ? body(code) : undefined));
        expect(failure).toMatchObject({ kind, intent, status });
        if (code) expect(failure).toMatchObject({ code, requestId: 'req-1', message: '안내 문구' });
    });

    it('낯선 코드도 상태 코드로 분류하고 코드는 그대로 보존한다', () => {
        expect(classifyFailure(new ApiError(409, body('SOMETHING_NEW')))).toMatchObject({
            kind: 'rejected',
            code: 'SOMETHING_NEW',
        });
        expect(classifyFailure(new ApiError(418, body('TEAPOT')))).toMatchObject({
            kind: 'rejected',
            code: 'TEAPOT',
        });
    });

    it('409 INVALID_STATE의 details.reason을 보존한다(#361)', () => {
        const withReason = (details: unknown) =>
            classifyFailure(new ApiError(409, { ...body('INVALID_STATE'), details }));
        expect(withReason({ reason: 'SUPPORT_MINIMUM_CREW' })).toMatchObject({
            kind: 'rejected',
            intent: 'finished',
            code: 'INVALID_STATE',
            reason: 'SUPPORT_MINIMUM_CREW',
        });
        for (const bad of [undefined, { reason: 3 }, { reason: '' }, ['x']])
            expect(withReason(bad).reason).toBeUndefined();
    });

    it('같은 409라도 코드가 다르면 다르게 분류한다', () => {
        const kinds = [
            'VERSION_CONFLICT',
            'REQUEST_IN_PROGRESS',
            'IDEMPOTENCY_MISMATCH',
            'INVALID_STATE',
        ].map((code) => classifyFailure(new ApiError(409, body(code))).kind);
        expect(new Set(kinds).size).toBe(4);
    });

    it('본문이 계약과 다르거나 깨져도 상태 코드로 분류한다', () => {
        for (const payload of [undefined, '<html>', { error: 'Forbidden' }, null]) {
            expect(classifyFailure(new ApiError(403, payload))).toMatchObject({
                kind: 'access-denied',
                status: 403,
            });
            expect(classifyFailure(new ApiError(403, payload)).code).toBeUndefined();
        }
        // 코드가 없는 409는 어떤 충돌인지 알 수 없으므로 서버가 거절한 것으로만 본다.
        expect(classifyFailure(new ApiError(409, undefined)).kind).toBe('rejected');
    });
});

describe('상태 코드가 없는 실패', () => {
    it('전송 단절은 처리 여부 불명이고 키를 유지한다', () => {
        expect(classifyFailure(new TypeError('Failed to fetch'))).toEqual({
            kind: 'unknown-outcome',
            intent: 'pending',
        });
    });

    it('2xx 본문 손상은 실패나 미처리로 추정하지 않는다', () => {
        expect(classifyFailure(new ApiResponseError(200))).toMatchObject({
            kind: 'unknown-outcome',
            status: 200,
            intent: 'pending',
        });
        expect(classifyFailure(new ApiResponseError(202)).kind).toBe('unknown-outcome');
    });

    it('2xx인데 계약과 다른 응답은 처리 여부 불명이다', () => {
        expect(classifyFailure(new ContractViolationError('rev', 'x'))).toMatchObject({
            kind: 'unknown-outcome',
            intent: 'pending',
        });
    });

    it('취소는 오류로 보이지 않는 분류다', () => {
        expect(classifyFailure(new DOMException('cancelled', 'AbortError')).kind).toBe('cancelled');
        expect(classifyFailure(new ScopeDisposedError()).kind).toBe('cancelled');
    });

    it('보내지 않은 요청은 서버가 받지 못했다고 구별한다', () => {
        expect(classifyFailure(new InvalidApiPathError())).toMatchObject({
            kind: 'not-sent',
            intent: 'finished',
        });
    });

    it('알 수 없는 예외도 처리 불명으로 보수적으로 다룬다', () => {
        expect(classifyFailure('문자열 예외').kind).toBe('unknown-outcome');
        expect(classifyFailure(undefined).kind).toBe('unknown-outcome');
    });
});

// BE !80(공통 오류 형식)이 실제로 내는 본문. 2026-10-29 실서버 GET의 응답을 그대로 옮겼다.
// 요청 ID만 로그 대조를 위해 고정 값으로 바꿨다. 코드명과 문구는 BE ErrorCode와 같다.
const SERVER_401 = {
    code: 'UNAUTHENTICATED',
    message: '로그인이 필요합니다.',
    requestId: '9141b534-4aa3-4162-9a69-46073a8d62e6',
};
// handleMethodArgumentNotValid가 만드는 모양(BE 소스 기준). 실서버에는 해당 API가 아직 없어 실측하지 않았다.
const SERVER_400_VALID = {
    code: 'INVALID_REQUEST',
    message: '요청 형식이 올바르지 않습니다.',
    requestId: '0f0c5a3e-2a43-4b43-9b0a-7a6f1f1f0c11',
    details: { fields: [{ field: 'name', reason: 'NotBlank' }] },
};

describe('공개 거절의 위반 목록(#286)', () => {
    it('422 EVENT_INCOMPLETE는 details.fields를 Failure.fields로 보존하고 모양이 다른 항목은 버린다', () => {
        const failure = classifyFailure(
            new ApiError(422, {
                ...body('EVENT_INCOMPLETE'),
                details: {
                    fields: [
                        { field: 'date', reason: 'MISSING' },
                        { field: 'tasks', reason: 'MISSING' },
                        { field: 1, reason: 'RANGE' },
                    ],
                },
            }),
        );
        expect(failure).toMatchObject({ kind: 'rejected', intent: 'finished' });
        expect(failure.fields).toEqual([
            { field: 'date', reason: 'MISSING' },
            { field: 'tasks', reason: 'MISSING' },
        ]);
    });
    it('400 INVALID_REQUEST도 details.fields를 보존한다(초안 저장 거절 사유, #387)', () => {
        const failure = classifyFailure(
            new ApiError(400, {
                ...body('INVALID_REQUEST'),
                details: { fields: [{ field: 'tasks[1].programId', reason: 'PROGRAM_CONTEXT' }] },
            }),
        );
        expect(failure).toMatchObject({ kind: 'rejected', intent: 'finished' });
        expect(failure.fields).toEqual([
            { field: 'tasks[1].programId', reason: 'PROGRAM_CONTEXT' },
        ]);
        // 상세가 없으면 fields를 두지 않는다
        expect(classifyFailure(new ApiError(400, body('INVALID_REQUEST')))).not.toHaveProperty(
            'fields',
        );
    });
});

describe('BE 공통 오류 응답 fixture', () => {
    it('401 UNAUTHENTICATED: 본문이 계약과 맞고 재로그인 + 키 유지로 분류한다', () => {
        expect(parseErrorBody(SERVER_401)).toEqual(SERVER_401);
        expect(classifyFailure(new ApiError(401, SERVER_401))).toEqual({
            kind: 'unauthenticated',
            status: 401,
            code: 'UNAUTHENTICATED',
            requestId: SERVER_401.requestId,
            message: SERVER_401.message,
            intent: 'pending',
        });
    });

    it('401이어도 본문이 없으면 상태 코드만으로 같은 분류가 된다', () => {
        expect(classifyFailure(new ApiError(401, undefined))).toMatchObject({
            kind: 'unauthenticated',
            intent: 'pending',
        });
    });

    it('400 @Valid 실패의 details.fields는 계약이 아니므로 읽지 않는다(V7-D19)', () => {
        const failure = classifyFailure(new ApiError(400, SERVER_400_VALID));
        expect(failure).toMatchObject({
            kind: 'rejected',
            intent: 'finished',
            code: 'INVALID_REQUEST',
        });
        // details 원문은 Failure에 싣지 않는다. EVENT_INCOMPLETE가 아니므로 필드 목록도 비어 있다.
        expect(failure).not.toHaveProperty('details');
        const body = parseErrorBody(SERVER_400_VALID);
        expect(body && readEventIncompleteFields(body)).toEqual([]);
    });
});
