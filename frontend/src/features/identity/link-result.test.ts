import { describe, expect, it } from 'vitest';
import { linkResultText } from './link-result';

describe('소셜 계정 연결 결과 안내(#227)', () => {
    it.each([
        ['GOOGLE', 'linked', 'Google 계정을 연결했습니다. 이제 Google로 로그인할 수 있습니다.'],
        ['KAKAO', 'cancelled', '카카오 연결을 취소했습니다.'],
        ['NAVER', 'provider_linked', '이미 네이버 계정이 연결되어 있습니다.'],
        [
            'GOOGLE',
            'identity_linked',
            '이 Google 계정은 이미 다른 SceneCrew 계정에 연결되어 있습니다.',
        ],
        [
            'GOOGLE',
            'reauth_required',
            '본인 확인 시간(5분)이 지났습니다. 비밀번호를 다시 입력해 주세요.',
        ],
        ['GOOGLE', 'unauthenticated', '로그인이 만료되었습니다. 다시 로그인한 뒤 연결해 주세요.'],
        ['KAKAO', 'failed', '카카오 연결을 마치지 못했습니다. 다시 시도해 주세요.'],
        ['KAKAO', 'unknown', '카카오 연결을 마치지 못했습니다. 다시 시도해 주세요.'],
    ])('%s %s', (provider, result, text) => {
        expect(linkResultText(provider, result)).toBe(text);
    });

    it.each([
        [null, 'linked'],
        ['GOOGLE', null],
        ['LOCAL', 'linked'],
        ['<script>', 'linked'],
        ['toString', 'linked'],
    ])('알 수 없는 제공자/결과는 안내하지 않는다(%s, %s)', (provider, result) => {
        expect(linkResultText(provider, result)).toBe('');
    });
});
