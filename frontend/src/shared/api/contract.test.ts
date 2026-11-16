import { describe, expect, it } from 'vitest';
import {
    ContractViolationError,
    asBigIntString,
    asRecord,
    asUtcTimestamp,
    asUuid,
    parseErrorBody,
    readEventIncompleteFields,
} from './contract';

describe('응답 규약 읽기', () => {
    it('UUID는 표준 문자열만 받는다', () => {
        const id = '0b1c2d3e-4f50-4a61-8b72-93a4b5c6d7e8';
        expect(asUuid(id, 'id')).toBe(id);
        expect(asUuid(id.toUpperCase(), 'id')).toBe(id.toUpperCase());
        for (const bad of ['', 'abc', 123, null, undefined, id.replaceAll('-', ''), `${id}0`]) {
            expect(() => asUuid(bad, 'id')).toThrow(ContractViolationError);
        }
    });

    it('BIGINT는 10진 문자열만 받고 숫자는 계약 위반이다', () => {
        expect(asBigIntString('0', 'revision')).toBe('0');
        expect(asBigIntString('9223372036854775807', 'revision')).toBe('9223372036854775807');
        for (const bad of [12, 12n, 1.5, '012', '-1', '1e3', ' 1', '', '9223372036854775808']) {
            expect(() => asBigIntString(bad, 'revision'), String(bad)).toThrow(
                ContractViolationError,
            );
        }
    });

    it('큰 revision이 숫자로 오면 정밀도 손실 전에 계약 위반으로 막는다', () => {
        // JSON.parse는 이 값을 9007199254740992로 읽어 끝자리를 잃는다.
        const parsed = JSON.parse('{"revision": 9007199254740993}') as { revision: unknown };
        expect(typeof parsed.revision).toBe('number');
        expect(() => asBigIntString(parsed.revision, 'revision')).toThrow(ContractViolationError);
    });

    it('시간은 오프셋이 있는 ISO 8601만 받고, 오프셋 없는 문자열은 UTC인지 알 수 없어 거절한다', () => {
        for (const good of [
            '2026-10-27T08:01:32Z',
            '2026-10-27T08:01:32.123Z',
            '2026-10-27T17:01:32+09:00',
        ]) {
            expect(asUtcTimestamp(good, 'at')).toBe(good);
        }
        for (const bad of [
            '2026-10-27T08:01:32',
            '2026-10-27',
            '2026-13-27T08:01:32Z',
            '2026-10-27 08:01:32Z',
            1793088092000,
            null,
        ]) {
            expect(() => asUtcTimestamp(bad, 'at'), String(bad)).toThrow(ContractViolationError);
        }
    });

    it.each([
        '2024-02-29T23:59:59Z',
        '2000-02-29T00:00:00Z',
        '2026-01-01T00:30:00+09:00',
        '2026-12-31T23:30:00-09:00',
        '2026-10-28T03:00:00.1Z',
        '2026-10-28T03:00:00.123456789Z',
    ])('유효한 달력 날짜와 오프셋/소수초를 유지한다: %s', (value) => {
        expect(asUtcTimestamp(value, 'at')).toBe(value);
    });

    it.each([
        '2026-02-29T00:00:00Z',
        '2026-02-30T00:00:00Z',
        '1900-02-29T00:00:00Z',
        '2026-04-31T00:00:00+09:00',
        '2026-11-31T00:00:00-09:00',
        '2026-01-00T00:00:00Z',
    ])('실제로 존재하지 않는 달력 날짜는 거절한다: %s', (value) => {
        expect(() => asUtcTimestamp(value, 'event.at')).toThrow(ContractViolationError);
    });

    it('위반 오류는 어느 필드인지 알려 준다', () => {
        try {
            asBigIntString(7, 'event.revision');
            expect.unreachable();
        } catch (error) {
            expect(error).toBeInstanceOf(ContractViolationError);
            expect((error as ContractViolationError).path).toBe('event.revision');
        }
    });

    it('객체가 아닌 응답은 거절한다', () => {
        for (const bad of [null, [], 'x', 1]) {
            expect(() => asRecord(bad, '응답')).toThrow(ContractViolationError);
        }
    });
});

describe('오류 본문 {code, message, requestId, details?}', () => {
    it('계약대로면 그대로 읽는다', () => {
        expect(
            parseErrorBody({
                code: 'VERSION_CONFLICT',
                message: '최신 내용을 확인해주세요.',
                requestId: 'r-1',
            }),
        ).toEqual({
            code: 'VERSION_CONFLICT',
            message: '최신 내용을 확인해주세요.',
            requestId: 'r-1',
        });
        expect(
            parseErrorBody({ code: 'X', message: 'm', requestId: 'r', details: { a: 1 } }),
        ).toEqual({
            code: 'X',
            message: 'm',
            requestId: 'r',
            details: { a: 1 },
        });
    });

    it('모양이 다르면 던지지 않고 null이다(오류 처리 중 또 실패하지 않는다)', () => {
        const cases: unknown[] = [
            undefined,
            null,
            'error',
            [],
            {},
            { code: 'X' },
            { code: '', message: 'm', requestId: 'r' },
            { code: 'X', message: 1, requestId: 'r' },
            // requestId가 없거나 문자열이 아니면 문의 대조가 안 되므로 계약 본문으로 보지 않는다.
            { code: 'X', message: 'm' },
            { code: 'X', message: 'm', requestId: 7 },
            // Spring 기본 오류 형태는 계약과 다르다.
            {
                timestamp: '2026-10-27T08:00:00Z',
                status: 403,
                error: 'Forbidden',
                path: '/api/v1/x',
            },
        ];
        for (const payload of cases) expect(parseErrorBody(payload)).toBeNull();
    });

    it('EVENT_INCOMPLETE의 details.fields만 읽고 나머지 키와 낯선 코드는 무시한다', () => {
        const body = parseErrorBody({
            code: 'EVENT_INCOMPLETE',
            message: 'm',
            requestId: 'r',
            details: {
                fields: [
                    { field: 'openAt', reason: 'MISSING' },
                    { field: 1 },
                    null,
                    { field: 'x', reason: 'ORDER' },
                ],
                internal: '노출 금지',
            },
        })!;
        expect(readEventIncompleteFields(body)).toEqual([
            { field: 'openAt', reason: 'MISSING' },
            { field: 'x', reason: 'ORDER' },
        ]);
        const other = parseErrorBody({
            code: 'VERSION_CONFLICT',
            message: 'm',
            requestId: 'r',
            details: { fields: [{ field: 'a', reason: 'b' }] },
        })!;
        expect(readEventIncompleteFields(other)).toEqual([]);
    });

    it('details가 없거나 모양이 틀려도 빈 목록이다', () => {
        for (const details of [undefined, null, 'x', { fields: 'x' }, { fields: {} }]) {
            const body = parseErrorBody({
                code: 'EVENT_INCOMPLETE',
                message: 'm',
                requestId: 'r',
                details,
            })!;
            expect(readEventIncompleteFields(body)).toEqual([]);
        }
    });
});
