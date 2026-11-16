package com.ssafy.scenecrew.global.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 공통 오류 코드. 코드 이름이 응답 본문의 code 값이 된다.
 * 업무 오류(VERSION_CONFLICT 등, BE08)는 해당 기능을 구현할 때 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해 주세요."),
    INVALID_AUTH_CHANNEL(HttpStatus.BAD_REQUEST, "인증 채널이 올바르지 않습니다."),
    MALFORMED_COOKIE_HEADER(HttpStatus.BAD_REQUEST, "Cookie 형식이 올바르지 않습니다."),
    DUPLICATE_AUTH_COOKIE(HttpStatus.BAD_REQUEST, "인증 Cookie가 중복되었습니다."),
    MIXED_AUTH_CREDENTIALS(HttpStatus.BAD_REQUEST, "인증 전달 방식을 혼합할 수 없습니다."),
    AUTH_CHANNEL_MISMATCH(HttpStatus.FORBIDDEN, "인증 채널이 일치하지 않습니다."),
    CSRF_REJECTED(HttpStatus.FORBIDDEN, "요청 출처를 확인할 수 없습니다."),
    AUTH_CONTEXT_CONFLICT(HttpStatus.CONFLICT, "로그인 상태가 변경되었습니다. 현재 상태를 확인해 주세요."),
    REFRESH_ATTEMPT_MISMATCH(HttpStatus.CONFLICT, "이미 다른 갱신 시도로 처리된 자격입니다."),
    REFRESH_RECOVERY_UNAVAILABLE(HttpStatus.CONFLICT, "갱신 결과를 안전하게 복구할 수 없습니다. 다시 로그인해 주세요."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 로그인해 주세요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "지금 상태에서는 할 수 없는 요청입니다."),
    /** 기대한 revision이 현재와 다름. details.target에 대상(EVENT 등)을 넣는다(BE08). */
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 변경이 먼저 반영되었습니다. 현재 상태를 다시 확인해 주세요."),
    /** 요청이 가리킨 구역·그룹·업무 등이 이 행사에 없거나 쓸 수 없음(BE08) */
    INVALID_REFERENCE(HttpStatus.UNPROCESSABLE_CONTENT, "요청한 대상 중 사용할 수 없는 것이 있습니다."),
    /** 같은 담당자의 업무 시간이 겹침(BE08). details.memberId·taskId */
    TIME_OVERLAP(HttpStatus.UNPROCESSABLE_CONTENT, "시간이 겹치는 업무가 있습니다."),
    /** 복구 계산 결과가 만료됐다(완료 7일 뒤). 다른 후보로 바꾸지 않고 다시 계산해야 한다. */
    RECOVERY_RESULT_EXPIRED(HttpStatus.GONE, "복구 계산 결과가 만료되었습니다. 다시 계산해 주세요."),
    /** 공개·계획 생성 전 기본정보 누락·순서·범위 위반(be/04 완결성 검사). details.fields[{field, reason}] */
    EVENT_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, "행사 기본정보를 모두 올바르게 채워 주세요."),
    EVENT_FULL(HttpStatus.CONFLICT, "행사 정원이 가득 찼습니다."),
    /** 018 탈퇴: 종료 전 공연의 총괄이다(V9-D39). details.eventIds */
    CHIEF_TRANSFER_REQUIRED(HttpStatus.CONFLICT, "종료되지 않은 공연의 총괄은 다른 사람에게 인계한 뒤 탈퇴할 수 있습니다."),
    /** 018 탈퇴: 진행 중인 업무가 있다(V9-D39). details.eventIds */
    TASK_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 업무가 있어 탈퇴할 수 없습니다. 업무를 마친 뒤 다시 시도해 주세요."),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다. 잠시 후 결과를 확인해 주세요."),
    IDEMPOTENCY_MISMATCH(HttpStatus.CONFLICT, "같은 요청 키로 다른 요청을 보낼 수 없습니다."),
    IDEMPOTENCY_RESULT_EXPIRED(HttpStatus.CONFLICT, "요청 결과를 다시 받을 수 있는 기간이 지났습니다. 현재 상태를 확인해 주세요."),
    /** 080 같은 키 재요청: 이전 요청의 처리 결과(성공·실패)를 알 수 없다. 새 키로 다시 요청한다(#269). */
    IDEMPOTENCY_OUTCOME_UNKNOWN(HttpStatus.CONFLICT, "이전 요청이 처리됐는지 확인할 수 없습니다. 새로 요청해 주세요."),
    /** 080 같은 키 재요청: 요약은 만들어졌지만 서버가 보관하지 않아 다시 줄 수 없다(POLICIES P07, #269). */
    TEXT_NOT_RETAINED(HttpStatus.GONE, "요약은 이미 만들어졌지만 서버에 보관하지 않아 다시 받을 수 없습니다. 새로 요청해 주세요."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "요청 크기가 허용 범위를 넘었습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다."),
    // #227 소셜 계정 연결(014·015, BE08). 재확인은 최근 5분 비밀번호 확인이다.
    REAUTHENTICATION_REQUIRED(HttpStatus.FORBIDDEN, "본인 확인이 필요합니다. 비밀번호를 다시 입력해 주세요."),
    PROVIDER_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 이 제공자의 계정이 연결되어 있습니다."),
    IDENTITY_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 다른 계정에 연결된 소셜 계정입니다."),
    LAST_IDENTITY(HttpStatus.CONFLICT, "마지막 로그인 수단은 해제할 수 없습니다."),
    // 장비 수집기 내부 경로 전용(#330, internal-collector-proposed.yaml). 공개 API에는 쓰지 않는다.
    INVALID_COLLECTOR_REQUEST(HttpStatus.BAD_REQUEST, "수집 묶음 형식이 올바르지 않습니다."),
    COLLECTOR_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "수집기 인증에 실패했습니다."),
    COLLECTOR_SCOPE_FORBIDDEN(HttpStatus.FORBIDDEN, "이 수집기가 보낼 수 없는 기록입니다."),
    COLLECTOR_SEQUENCE_CONFLICT(HttpStatus.CONFLICT, "같은 번호로 다른 기록이 이미 저장되어 있습니다."),
    COLLECTOR_RUN_NOT_ACTIVE(HttpStatus.CONFLICT, "새 기록을 받을 수 없는 run입니다."),
    COLLECTOR_EVENT_CLOSED(HttpStatus.CONFLICT, "수집을 받을 수 없는 행사 상태입니다."),
    COLLECTOR_BATCH_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "한 번에 보낼 수 있는 기록 수를 넘었습니다."),
    COLLECTOR_SOURCE_UNMAPPED(HttpStatus.UNPROCESSABLE_CONTENT, "장비에 연결되지 않은 수집 대상입니다."),
    COLLECTOR_RECORD_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "수집 기록 형식이 올바르지 않습니다."),
    // 카메라 수집기 내부 경로 전용(#349). 인증·형식·행사 상태는 위 수집기 코드를 같이 쓴다.
    /** 이 행사에 그 연결 식별자(sourceBindingRef)로 등록된 카메라가 없거나 둘 이상이다. details.reason NOT_FOUND·DUPLICATE */
    CAMERA_SOURCE_UNMAPPED(HttpStatus.UNPROCESSABLE_CONTENT, "등록된 카메라가 없는 연결 식별자입니다."),
    CAMERA_DISABLED(HttpStatus.CONFLICT, "사용 중지된 카메라입니다."),
    /** 이미 받은 프레임보다 앞선 프레임. details.reason SEQUENCE·CAPTURED_AT·TOO_OLD */
    CAMERA_FRAME_OUTDATED(HttpStatus.CONFLICT, "이미 받은 프레임보다 앞선 프레임입니다."),
    // 카메라 웹 연결(#393, V9-D58·D60)
    /** 연결을 시작하거나 유지할 자격이 없다(담당 업무 아님·업무 상태·담당 해제·참여 종료·행사 종료). */
    CAMERA_LINK_FORBIDDEN(HttpStatus.FORBIDDEN, "이 카메라를 연결할 수 있는 담당자가 아닙니다."),
    /** 다른 브라우저가 보내는 중이거나(연결 시작), 다른 브라우저가 넘겨받았다(프레임). */
    CAMERA_LINK_TAKEN(HttpStatus.CONFLICT, "다른 브라우저가 이 카메라를 연결했습니다."),
    /** 웹 연결이 해제된 카메라 */
    CAMERA_UNLINKED(HttpStatus.CONFLICT, "연결이 해제된 카메라입니다."),
    // 장비 웹 연결(#394, V9-D59·D60)
    /** 연결 코드·수신기 연결을 시작하거나 수집을 유지할 자격이 없다(담당 업무 아님·업무 상태·담당 해제·참여 종료·행사 종료). */
    COLLECTOR_LINK_FORBIDDEN(HttpStatus.FORBIDDEN, "이 장비를 연결할 수 있는 담당자가 아닙니다."),
    /** 다른 사람이 연결해 수집 중인 장비이거나, 그 수신기가 다른 장비 칸에 연결돼 있다. details.reason ASSET_IN_USE·RECEIVER_IN_USE */
    EQUIPMENT_LINK_TAKEN(HttpStatus.CONFLICT, "이미 다른 연결이 있는 장비입니다."),
    /** 웹 연결이 없는 장비 */
    EQUIPMENT_UNLINKED(HttpStatus.CONFLICT, "연결되지 않은 장비입니다."),
    /** 웹으로 연결된 카메라에 서버 설정 경로 입력이 왔다 */
    INPUT_LINKED_ELSEWHERE(HttpStatus.CONFLICT, "웹으로 연결된 카메라·장비라 이 경로의 입력을 받지 않습니다."),
    /** 카메라 현재 프레임(Q2)이 없다. details.reason NO_FRAME·OFFLINE */
    SNAPSHOT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금 보여 줄 수 있는 카메라 화면이 없습니다.");

    private final HttpStatus status;
    private final String message;

    /**
     * 프레임워크가 정한 HTTP 상태에 맞는 공통 코드를 고른다.
     * 표에 없는 4xx는 INVALID_REQUEST, 5xx는 INTERNAL_ERROR로 둔다(HTTP 상태는 그대로 유지).
     */
    public static ErrorCode fromStatus(HttpStatusCode status) {
        // Status alone cannot select a channel/CSRF-specific business error.
        if (status.value() == HttpStatus.FORBIDDEN.value()) return FORBIDDEN;
        for (ErrorCode code : values()) {
            // 수집기 전용 코드는 프레임워크 오류에 쓰지 않는다
            if (code.name().contains("COLLECTOR") || code == VERSION_CONFLICT || code == INVALID_REFERENCE) continue;
            if (code.status.value() == status.value()) {
                return code;
            }
        }
        return status.is4xxClientError() ? INVALID_REQUEST : INTERNAL_ERROR;
    }
}
