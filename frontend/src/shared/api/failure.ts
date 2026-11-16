import { ScopeDisposedError } from '../scope/scope';
import { ApiError, ApiResponseError, InvalidApiPathError } from './client';
import {
    ContractViolationError,
    parseErrorBody,
    readDetailFields,
    readEventIncompleteFields,
} from './contract';

// 실패를 "사용자에게 무엇을 보여 주고 키를 어떻게 다룰지"로 분류한다(BE08 오류와 다음 행동).
// 분류만 하고 아무것도 다시 보내지 않는다. 재전송은 항상 호출자가 같은 의도로 명시한다.

export type FailureKind =
    // 완료 응답의 복구 창 만료. 키를 보존하고 상태 조회만 한다.
    | 'result-expired'
    // 종료 요약은 생성됐지만 본문은 보관하지 않는다. 같은 키로 재생성하지 않는다.
    | 'text-not-retained'
    // 호출자나 범위(계정·행사 전환)가 취소했다. 화면에 오류로 보이지 않는다.
    | 'cancelled'
    // 요청을 보내지 않았다(잘못된 경로). 서버는 아무것도 받지 못했다.
    | 'not-sent'
    // 전송 단절·2xx 본문 손상·타임아웃·계약과 다른 응답·5xx. 처리됐는지 알 수 없다.
    | 'unknown-outcome'
    // 401: 재로그인, 같은 의도의 키 유지.
    | 'unauthenticated'
    // 403/404: 숨겨진 자원 상세 없이 안내하고 현재 권한을 다시 조회한다.
    | 'access-denied'
    // 409 VERSION_CONFLICT: 입력 보존, 최신 비교, 명시 재확인.
    | 'version-conflict'
    // 409 REQUEST_IN_PROGRESS: 같은 요청이 진행 중. 기존 상태를 확인하고 새 키를 만들지 않는다.
    | 'request-in-progress'
    // 409 IDEMPOTENCY_MISMATCH: 같은 키에 다른 본문. 실제 새 의도일 때만 새 키.
    | 'idempotency-mismatch'
    // 429: 한도. 기존 유효 상태를 유지하고 기다린다.
    | 'rate-limited'
    // 503: 장애. 저장 여부를 확인하고 수동 대체를 안내한다.
    | 'unavailable'
    // 그 밖의 4xx(400, 409 INVALID_STATE·EVENT_FULL, 422 등). 서버가 거절했고 code로 안내한다.
    | 'rejected';

export interface Failure {
    kind: FailureKind;
    status?: number;
    // 서버 오류 코드. 모르는 코드도 그대로 보존한다(분류는 상태 코드와 알려진 코드만 쓴다).
    code?: string;
    // 문의·로그 대조용. 화면에 상세와 함께 노출하지 않는다.
    requestId?: string;
    // 서버가 준 사용자 안내 문구. 모양이 계약과 다르면 없다.
    message?: string;
    // 409 VERSION_CONFLICT의 details.target(event·changeSet·reservation·equipment 등). 없으면 비운다.
    target?: string;
    // 409 INVALID_STATE의 details.reason(예: 안전 인력 지원의 SUPPORT_*, #361). 없으면 비운다.
    reason?: string;
    // 422 EVENT_INCOMPLETE·400 INVALID_REQUEST의 details.fields(위반 칸과 사유, #286·#387). 형식이 다른 항목은 버린다.
    fields?: { field: string; reason: string }[];
    // 이 의도의 결과.
    //  finished: 서버가 확정해서 거절했다. 키를 버리고, 다시 하려면 새 의도로 시작한다.
    //  pending: 복구/조회에 필요한 키를 유지한다. 서버 불명·만료는 재전송을 막는다.
    intent: 'finished' | 'pending';
}

const pending = (failure: Omit<Failure, 'intent'>): Failure => ({ ...failure, intent: 'pending' });
const finished = (failure: Omit<Failure, 'intent'>): Failure => ({
    ...failure,
    intent: 'finished',
});

export function classifyFailure(error: unknown): Failure {
    if (error instanceof ScopeDisposedError) return pending({ kind: 'cancelled' });
    if (error instanceof DOMException && error.name === 'AbortError') {
        return pending({ kind: 'cancelled' });
    }
    if (error instanceof InvalidApiPathError) return finished({ kind: 'not-sent' });

    if (error instanceof ApiError) {
        const body = parseErrorBody(error.payload);
        const base = {
            status: error.status,
            ...(body && { code: body.code, requestId: body.requestId, message: body.message }),
        };
        const code = body?.code;
        const status = error.status;
        if (status === 409 && code === 'IDEMPOTENCY_OUTCOME_UNKNOWN') {
            return pending({ ...base, kind: 'unknown-outcome' });
        }
        if (status === 409 && code === 'IDEMPOTENCY_RESULT_EXPIRED') {
            return pending({ ...base, kind: 'result-expired' });
        }
        if (status === 410 && code === 'TEXT_NOT_RETAINED') {
            return pending({ ...base, kind: 'text-not-retained' });
        }
        if (status === 401) return pending({ ...base, kind: 'unauthenticated' });
        if (status === 403 || status === 404) return finished({ ...base, kind: 'access-denied' });
        if (status === 409 && code === 'VERSION_CONFLICT') {
            const details = body?.details;
            const target =
                details && typeof details === 'object' && !Array.isArray(details)
                    ? (details as { target?: unknown }).target
                    : undefined;
            return finished({
                ...base,
                kind: 'version-conflict',
                ...(typeof target === 'string' && target !== '' && { target }),
            });
        }
        if (status === 409 && code === 'INVALID_STATE') {
            const details = body?.details;
            const reason =
                details && typeof details === 'object' && !Array.isArray(details)
                    ? (details as { reason?: unknown }).reason
                    : undefined;
            return finished({
                ...base,
                kind: 'rejected',
                ...(typeof reason === 'string' && reason !== '' && { reason }),
            });
        }
        if (status === 422 && code === 'EVENT_INCOMPLETE' && body) {
            return finished({ ...base, kind: 'rejected', fields: readEventIncompleteFields(body) });
        }
        if (status === 409 && code === 'REQUEST_IN_PROGRESS') {
            return pending({ ...base, kind: 'request-in-progress' });
        }
        if (status === 409 && code === 'IDEMPOTENCY_MISMATCH') {
            return pending({ ...base, kind: 'idempotency-mismatch' });
        }
        if (status === 429) return pending({ ...base, kind: 'rate-limited' });
        if (status === 503) return pending({ ...base, kind: 'unavailable' });
        // 그 밖의 5xx는 서버가 어디까지 처리했는지 알 수 없다.
        if (status >= 500) return pending({ ...base, kind: 'unknown-outcome' });
        if (status === 400 && code === 'INVALID_REQUEST' && body) {
            const fields = readDetailFields(body);
            return finished({ ...base, kind: 'rejected', ...(fields.length > 0 && { fields }) });
        }
        // 나머지 4xx는 서버가 요청을 읽고 거절했다. 코드가 낯설어도 상태 코드로 판단한다.
        return finished({ ...base, kind: 'rejected' });
    }

    // 2xx를 받았지만 본문을 읽지 못했다. 실패나 미처리로 추정하지 않는다.
    if (error instanceof ApiResponseError) {
        return pending({ kind: 'unknown-outcome', status: error.status });
    }
    // 2xx 응답이 계약과 달랐다. 서버는 처리했을 수 있다.
    if (error instanceof ContractViolationError) return pending({ kind: 'unknown-outcome' });
    // fetch 자체가 실패했다(네트워크 단절, 리다이렉트 거절, 타임아웃).
    return pending({ kind: 'unknown-outcome' });
}
