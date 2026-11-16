import type { SocialProvider } from './session';

// 소셜 계정 연결(#227) 결과 안내. BE callback이 /account/profile?link=제공자&result=결과로 돌려보낸다.
export const PROVIDER_NAMES: Record<SocialProvider, string> = {
    GOOGLE: 'Google',
    KAKAO: '카카오',
    NAVER: '네이버',
};

export function linkResultText(provider: string | null, result: string | null) {
    if (!provider || !result || !Object.hasOwn(PROVIDER_NAMES, provider)) return '';
    const name = PROVIDER_NAMES[provider as SocialProvider];
    switch (result) {
        case 'linked':
            return `${name} 계정을 연결했습니다. 이제 ${name}로 로그인할 수 있습니다.`;
        case 'cancelled':
            return `${name} 연결을 취소했습니다.`;
        case 'provider_linked':
            return `이미 ${name} 계정이 연결되어 있습니다.`;
        case 'identity_linked':
            return `이 ${name} 계정은 이미 다른 SceneCrew 계정에 연결되어 있습니다.`;
        case 'reauth_required':
            return '본인 확인 시간(5분)이 지났습니다. 비밀번호를 다시 입력해 주세요.';
        case 'unauthenticated':
            return '로그인이 만료되었습니다. 다시 로그인한 뒤 연결해 주세요.';
        default:
            return `${name} 연결을 마치지 못했습니다. 다시 시도해 주세요.`;
    }
}
