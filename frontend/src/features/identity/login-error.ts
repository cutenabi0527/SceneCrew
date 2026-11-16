import { classifyFailure } from '../../shared/api/failure';

/** 이메일·비밀번호 불일치(401) 원인 문구. 로그인 화면이 이 원인일 때 "!비밀번호 오류 N회"로 바꿔 보인다 */
export const LOGIN_MISMATCH = '이메일 또는 비밀번호 불일치';

/**
 * 로그인 실패 원인을 한 줄에 들어가는 짧은 문구로 바꾼다. 화면에는 "!로그인 불가 - 원인"으로 보인다.
 * 취소된 요청은 오류로 보이지 않도록 빈 문자열을 돌려준다. 실패한 요청은 자동으로 다시 보내지 않는다.
 */
export function loginFailureCause(error: unknown, channel: 'email' | 'social' = 'email') {
    const failure = classifyFailure(error);
    switch (failure.kind) {
        case 'cancelled':
            return '';
        case 'unauthenticated':
            return LOGIN_MISMATCH;
        case 'access-denied':
            return '접근이 제한된 계정';
        case 'rate-limited':
            return '시도가 많아 잠시 뒤 다시';
        case 'unavailable':
            return channel === 'social' ? '지금은 소셜 로그인 사용 불가' : '서버 점검 중';
        case 'unknown-outcome':
        case 'result-expired':
            return '서버 응답 없음';
        default:
            return failure.intent === 'pending' ? '서버 응답 없음' : '요청을 처리하지 못함';
    }
}
