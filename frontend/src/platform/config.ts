export type AppPlatform = 'web' | 'android';

export function validateApiBase(base: string, platform: AppPlatform): string {
    if (platform === 'web' && base === '/api/v1') return base;

    let url: URL;
    try {
        url = new URL(base);
    } catch {
        throw new Error('API 주소를 설정하세요. Android는 HTTPS 절대 주소가 필요합니다.');
    }
    if (
        url.protocol !== 'https:' ||
        url.username ||
        url.password ||
        url.search ||
        url.hash ||
        url.pathname !== '/api/v1'
    ) {
        throw new Error('API 주소는 인증정보 없는 HTTPS /api/v1 주소여야 합니다.');
    }
    return base;
}
