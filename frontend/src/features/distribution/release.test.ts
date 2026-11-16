import { describe, expect, it } from 'vitest';
import { isOutdated, parseRelease, releaseOrigin, sizeText } from './release';

const valid = {
    versionName: '0.4.0',
    versionCode: 6,
    file: 'scenecrew-0.4.0-v6.apk',
    sha256: '086034eec19a4015b4e7c8fd5c2fa25df6443a6ace1da4ee0aa30c00f1cfa672',
    size: 16006378,
    releasedAt: '2026-11-05T08:10:00Z',
};

describe('공개 APK 배포 기록(#333)', () => {
    it('올바른 기록을 읽는다', () => {
        expect(parseRelease(valid)).toEqual(valid);
        expect(sizeText(valid.size)).toBe('15.3MB');
    });
    it.each([
        ['다른 경로의 파일', { file: '../secret.apk' }],
        ['숨김 파일', { file: '.hidden.apk' }],
        ['주소 파일', { file: 'https://evil.invalid/x.apk' }],
        ['apk가 아닌 파일', { file: 'scenecrew.exe' }],
        ['대문자/짧은 해시', { sha256: 'ABC' }],
        ['버전 형식', { versionName: 'latest' }],
        ['versionCode 0', { versionCode: 0 }],
        ['크기 없음', { size: 0 }],
        ['날짜 아님', { releasedAt: 'yesterday' }],
    ])('%s는 거절한다', (_, patch) => {
        expect(parseRelease({ ...valid, ...patch })).toBeNull();
    });
    it.each([null, [], 'x', 1])('객체가 아니면 거절한다(%s)', (value) => {
        expect(parseRelease(value)).toBeNull();
    });
});

describe('앱 최소 버전(#386)', () => {
    it('최소 버전은 선택 항목이며 있으면 함께 읽는다', () => {
        expect(parseRelease({ ...valid, minVersionCode: 6 })).toEqual({
            ...valid,
            minVersionCode: 6,
        });
        expect(parseRelease(valid)).not.toHaveProperty('minVersionCode');
    });
    it.each([
        ['배포 버전보다 높음', 7],
        ['0', 0],
        ['소수', 5.5],
        ['문자열', '6'],
        ['null', null],
    ])('최소 버전이 %s이면 기록을 거절한다', (_, minVersionCode) => {
        expect(parseRelease({ ...valid, minVersionCode })).toBeNull();
    });
    it('설치 버전이 최소 버전보다 낮을 때만 막는다', () => {
        const release = parseRelease({ ...valid, versionCode: 7, minVersionCode: 7 });
        expect(isOutdated(6, release)).toBe(true);
        expect(isOutdated(7, release)).toBe(false);
        expect(isOutdated(8, release)).toBe(false);
    });
    it('최소 버전이 없거나 기록을 읽지 못하면 막지 않는다', () => {
        expect(isOutdated(1, parseRelease(valid))).toBe(false);
        expect(isOutdated(1, null)).toBe(false);
    });
    it('업무 서버 주소에서 배포 기록 호스트를 얻는다', () => {
        expect(releaseOrigin('https://service.p.example.test/api/v1')).toBe(
            'https://service.p.example.test',
        );
    });
});
