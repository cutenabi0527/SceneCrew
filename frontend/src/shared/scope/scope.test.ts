import { describe, expect, it, vi } from 'vitest';
import {
    Lifetime,
    ScopeContractError,
    accountPrefix,
    eventPrefix,
    parseBigInt,
    requireId,
} from './scope';

describe('범위 규약', () => {
    it('BIGINT는 10진 문자열만 받고 BigInt로 돌려준다', () => {
        expect(parseBigInt('0', 'r')).toBe(0n);
        expect(parseBigInt('9223372036854775807', 'r')).toBe(9223372036854775807n);
        // 부호 있는 64비트(BIGINT)를 넘는 값은 계약 위반이다.
        for (const tooBig of ['9223372036854775808', '18446744073709551616', '9'.repeat(5000)]) {
            expect(() => parseBigInt(tooBig, 'r')).toThrow(ScopeContractError);
        }
        for (const bad of [0, 1.5, '00', '+1', '1e3', '0x10', ' 1', '1 ', '']) {
            expect(() => parseBigInt(bad, 'r')).toThrow(ScopeContractError);
        }
    });

    it('긴 숫자열은 BigInt로 변환하기 전에 길이로 거절한다', () => {
        const convert = vi.spyOn(globalThis, 'BigInt');
        try {
            expect(() => parseBigInt('9'.repeat(5000), 'r')).toThrow(ScopeContractError);
            expect(convert).not.toHaveBeenCalled();
        } finally {
            convert.mockRestore();
        }
    });

    it('식별자는 비어 있지 않은 문자열만 받는다', () => {
        expect(requireId('a', 'id')).toBe('a');
        for (const bad of ['', '  ', 0, null, undefined, {}]) {
            expect(() => requireId(bad, 'id')).toThrow(ScopeContractError);
        }
    });

    it('식별자에 구분 문자가 있어도 키가 서로 겹치지 않는다', () => {
        expect(accountPrefix('a:b')).not.toEqual(accountPrefix('a'));
        expect(eventPrefix('a', 'b:c')).not.toEqual(eventPrefix('a:b', 'c'));
        expect(eventPrefix('1', '2')).toEqual(['account', '1', 'event', '2']);
    });

    it('수명은 정리를 한 번만 실행하고 abort 사유를 남긴다', () => {
        const life = new Lifetime();
        let calls = 0;
        life.onDispose(() => calls++);
        expect(life.dispose()).toEqual([]);
        expect(life.dispose()).toEqual([]);
        expect(calls).toBe(1);
        expect(life.signal.aborted).toBe(true);
        expect(life.isCurrent()).toBe(false);
    });
});
