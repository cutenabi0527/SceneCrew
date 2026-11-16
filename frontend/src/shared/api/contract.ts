import { ScopeContractError, parseBigInt } from '../scope/scope';

// 서버 응답을 FE가 읽는 지점의 검증. 계약에 정해진 규약만 검사하고, 정해지지 않은 필드 이름이나
// 형식은 가정하지 않는다(호출자가 parse 함수에서 필요한 필드만 읽는다).
//  - ID는 UUID(앱 생성, BE03). 소문자 표준 문자열 형태만 받는다.
//  - BIGINT(revision·sequence·participationRevision 등)는 10진 문자열. 숫자로 오면 계약 위반이다.
//  - 시간은 오프셋이 있는 ISO 8601 문자열(V7-D16). 소수초 자릿수는 고정하지 않는다.
//    오프셋이 없는 문자열은 UTC인지 알 수 없어 거절한다.

export class ContractViolationError extends Error {
    constructor(
        public readonly path: string,
        reason: string,
    ) {
        super(`응답이 계약과 다릅니다: ${path} ${reason}`);
        this.name = 'ContractViolationError';
    }
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const UTC_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/;

export function asRecord(value: unknown, path: string): Record<string, unknown> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
        throw new ContractViolationError(path, '객체여야 합니다.');
    }
    return value as Record<string, unknown>;
}

export function asArray(value: unknown, path: string): unknown[] {
    if (!Array.isArray(value)) throw new ContractViolationError(path, '배열이어야 합니다.');
    return value;
}

export function asString(value: unknown, path: string): string {
    if (typeof value !== 'string') throw new ContractViolationError(path, '문자열이어야 합니다.');
    return value;
}

export function asUuid(value: unknown, path: string): string {
    const text = asString(value, path);
    if (!UUID_PATTERN.test(text)) throw new ContractViolationError(path, 'UUID여야 합니다.');
    return text;
}

// 반환값은 문자열 그대로다. 비교가 필요할 때 BigInt(...)로 바꾼다.
export function asBigIntString(value: unknown, path: string): string {
    try {
        parseBigInt(value, path);
    } catch (error) {
        if (error instanceof ScopeContractError) {
            throw new ContractViolationError(path, 'BIGINT는 10진 문자열이어야 합니다(숫자 금지).');
        }
        throw error;
    }
    return value as string;
}

export function asUtcTimestamp(value: unknown, path: string): string {
    const text = asString(value, path);
    // Date.parse는 2월 30일 등을 다음 달로 보정한다. 표기된 날짜 자체를 따로 검증한다.
    // 오프셋 적용 후 UTC 날짜와 비교하면 정상적인 자정 근처 시각도 거절하게 된다.
    const calendarDate = new Date(`${text.slice(0, 10)}T00:00:00Z`);
    if (
        !UTC_PATTERN.test(text) ||
        Number.isNaN(Date.parse(text)) ||
        calendarDate.getUTCDate() !== Number(text.slice(8, 10))
    ) {
        throw new ContractViolationError(path, '오프셋이 있는 ISO 8601 시간이어야 합니다.');
    }
    return text;
}

// 오류 본문 {code, message, requestId, details?} (박하민 답변, 2026-10-27).
export interface ApiErrorBody {
    code: string;
    message: string;
    requestId: string;
    // 코드별로 정의된 키만 읽는다. 여기서는 해석하지 않는다.
    details?: unknown;
}

// 모양이 다르면 null이다. 오류 처리 중에 또 던지지 않기 위해 예외를 쓰지 않는다.
export function parseErrorBody(payload: unknown): ApiErrorBody | null {
    if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) return null;
    const body = payload as Record<string, unknown>;
    const { code, message, requestId } = body;
    if (typeof code !== 'string' || code === '') return null;
    if (typeof message !== 'string' || typeof requestId !== 'string') return null;
    return 'details' in body
        ? { code, message, requestId, details: body.details }
        : { code, message, requestId };
}

// EVENT_INCOMPLETE의 details.fields[{field, reason}]만 정의되어 있다(BE08).
// 모양이 다르면 빈 목록이다. details 원문을 화면에 그대로 내보내지 않는다.
export function readEventIncompleteFields(body: ApiErrorBody): { field: string; reason: string }[] {
    if (body.code !== 'EVENT_INCOMPLETE') return [];
    return readDetailFields(body);
}

/** details.fields의 {field, reason} 목록(422 EVENT_INCOMPLETE, 400 INVALID_REQUEST). 형식이 다른 항목은 버린다. */
export function readDetailFields(body: ApiErrorBody): { field: string; reason: string }[] {
    if (typeof body.details !== 'object' || body.details === null) return [];
    const fields = (body.details as { fields?: unknown }).fields;
    if (!Array.isArray(fields)) return [];
    const result: { field: string; reason: string }[] = [];
    for (const item of fields) {
        if (typeof item !== 'object' || item === null) continue;
        const { field, reason } = item as Record<string, unknown>;
        if (typeof field === 'string' && typeof reason === 'string') result.push({ field, reason });
    }
    return result;
}
