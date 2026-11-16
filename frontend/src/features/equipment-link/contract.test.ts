import { describe, expect, it } from 'vitest';
import { parseLinkCode, parseLinkReceipt, parseRelease } from './contract';

const sha = 'cff477817f84321295f6f6acdd9d98eaaa725817b54e1fec35a8fd1ecd91c005';

describe('장비 연결 응답(#398)', () => {
    it('연결 코드와 연결 영수증을 v9 형식으로 읽는다', () => {
        expect(parseLinkCode({ code: 'PRTT-3D6G', expiresAt: '2026-11-13T01:12:18Z' }).code).toBe(
            'PRTT-3D6G',
        );
        expect(() =>
            parseLinkCode({ code: 'prtt3d6g', expiresAt: '2026-11-13T01:12:18Z' }),
        ).toThrow();
        expect(
            parseLinkReceipt({ id: '72476661-f10b-4cca-8cbc-c5a04281c4e2', revision: '2' })
                .revision,
        ).toBe('2');
    });

    it('내려받기 정보는 실행 파일 이름과 SHA-256 형식이 맞을 때만 쓴다', () => {
        expect(
            parseRelease({
                version: '0.1.0',
                file: 'scenecrew-equipment-0.1.0.exe',
                sha256: sha,
                bytes: 10774979,
            }),
        ).toEqual({
            version: '0.1.0',
            file: 'scenecrew-equipment-0.1.0.exe',
            sha256: sha,
            bytes: 10774979,
        });
        // 다른 경로·다른 형식의 파일로 링크를 만들지 않는다
        for (const file of ['../x.exe', 'https://x/y.exe', 'a.apk', 'a b.exe'])
            expect(parseRelease({ version: '1', file, sha256: sha })).toBeNull();
        expect(parseRelease({ version: '1', file: 'a.exe', sha256: 'abc' })).toBeNull();
        expect(parseRelease(null)).toBeNull();
    });
});
