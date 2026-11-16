import type { Consents } from './contract';

// 가입 동의와 이름 규칙. 이메일 가입(RegisterScreen)·내 정보 동의·소셜 가입(#379)이 함께 쓴다.

export const emptyConsents: Consents = {
    service: false,
    voice: false,
    push: false,
    externalAi: false,
};
export const consentLabels: Record<keyof Consents, string> = {
    service: '서비스 이용 동의',
    voice: '음성 처리 동의',
    push: '푸시 수신 동의',
    externalAi: '외부 AI 처리 동의',
};
/** 가입에 꼭 필요한 동의. 서비스 이용 동의만 필수다(서버도 같은 규칙으로 거절한다) */
export const consentRequired: Record<keyof Consents, boolean> = {
    service: true,
    voice: false,
    push: false,
    externalAi: false,
};
/**
 * 선택이지만 동의하지 않으면 쓸 수 없는 기능. 가입 화면에 "…이용이 제한될 수 있습니다"로 보인다.
 * - 음성: 늦게 끝난 전사도 음성 동의를 다시 확인한다(V9-D28)
 * - 외부 AI: 접수 때 동의가 없으면 403 EXTERNAL_AI_CONSENT_REQUIRED(V9-D43 공통 AI 관문)
 * 푸시는 휴대폰 알림만 오지 않고 앱 안 알림함은 그대로라 표시하지 않는다.
 */
export const consentLimits: Partial<Record<keyof Consents, string>> = {
    voice: '음성 보고(말로 보고·받아쓰기)',
    externalAi: 'AI 계획 생성·문서 추출 등 AI 기능',
};

/** 앞뒤 ASCII 공백(U+0020)만 뺀다. 서버 저장 전 trim과 같은 범위다. */
export const trimSpaces = (value: string) => value.replace(/^ +| +$/g, '');

/** 이름에 넣을 수 없는 제어 문자·줄바꿈(U+0000~001F, U+007F~009F, U+2028·2029) */
function isControl(codePoint: number) {
    return (
        codePoint <= 0x1f ||
        (codePoint >= 0x7f && codePoint <= 0x9f) ||
        codePoint === 0x2028 ||
        codePoint === 0x2029
    );
}

/**
 * 이름 검사(v9 OAuthExchangeRequest.displayName). 앞뒤 공백을 뺀 1~20 코드포인트,
 * 제어 문자·줄바꿈 금지. 서버도 같은 규칙으로 다시 검사한다.
 */
export function signupNameError(value: string): string | null {
    const name = trimSpaces(value);
    if (!name) return '이름을 입력해 주세요.';
    const points = [...name].map((c) => c.codePointAt(0) ?? 0);
    if (points.length > 20) return '이름은 20자까지 입력할 수 있습니다.';
    if (points.some(isControl)) return '이름에 줄바꿈이나 제어 문자를 넣을 수 없습니다.';
    return null;
}
