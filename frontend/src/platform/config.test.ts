import { describe, expect, it } from 'vitest';
import { validateApiBase } from './config';

describe('플랫폼별 API 배포 경계', () => {
    it('WEB 동일 origin과 Android HTTPS 업무 서버만 허용한다', () => {
        expect(validateApiBase('/api/v1', 'web')).toBe('/api/v1');
        expect(validateApiBase('https://example.com/api/v1', 'android')).toBe(
            'https://example.com/api/v1',
        );
    });
    it.each([
        '/api/v1',
        'http://example.com/api/v1',
        'https://u:p@example.com/api/v1',
        'https://example.com/internal',
        'https://example.com/api/v1?token=x',
    ])('잘못된 Android 주소 %s를 빌드 전에 거부한다', (base) => {
        expect(() => validateApiBase(base, 'android')).toThrow();
    });
});
