import { describe, expect, it } from 'vitest';
import { SUPPORTED_KEYWORDS } from '../schema';
import { FE_PARSERS_BY_SCHEMA, HANDLED_ELSEWHERE, feParserFor } from './fe-parsers';
import schemas from './schemas.json';
import { parseV9, type V9SchemaName } from './index';
import { v9Examples, v9Operations } from './testing';

// v9 계약(#327) 상설 계약 시험. 계약 예시·fixture가 바뀌면 v9_bundle.py로 묶음을 다시 만들고 이 시험을 돌린다.

const accepts = (fn: () => unknown) => {
    try {
        fn();
        return true;
    } catch {
        return false;
    }
};

describe('v9 계약 묶음', () => {
    it('검사기가 해석하지 못하는 스키마 키워드가 없다', () => {
        const supported = new Set<string>(SUPPORTED_KEYWORDS);
        const unknown = new Set<string>();
        const walk = (node: unknown, names = false): void => {
            if (Array.isArray(node)) return node.forEach((n) => walk(n));
            if (!node || typeof node !== 'object') return;
            for (const [k, v] of Object.entries(node)) {
                if (!names && !supported.has(k)) unknown.add(k);
                walk(v, !names && k === 'properties');
            }
        };
        for (const [name, schema] of Object.entries(schemas)) if (name !== '$source') walk(schema);
        expect([...unknown]).toEqual([]);
    });

    it('스키마 검사기의 판정이 계약 예시/fixture의 정상/실패 판정과 같다', () => {
        const wrong = v9Examples
            .filter((e) => e.schema && e.contentType === 'application/json')
            .filter((e) => accepts(() => parseV9(e.schema as V9SchemaName, e.value)) !== e.valid)
            .map((e) => `${e.source} ${e.op ?? ''} ${e.name}`);
        expect(wrong).toEqual([]);
    });
});

describe('FE 검사기와 v9 계약', () => {
    const connected = Object.entries(v9Operations).filter(([, o]) => o.status === 'connected');

    it('연결한 연산마다 FE 검사기가 있거나, 다른 곳에서 검사하는 이유가 적혀 있다', () => {
        const missing = connected
            .map(([op]) => op)
            .filter((op) => !HANDLED_ELSEWHERE[op] && !feParserFor(op, null));
        expect(missing).toEqual([]);
    });

    it('계약 2xx 예시를 FE 검사기가 모두 받는다', () => {
        const rejected: string[] = [];
        let checked = 0;
        for (const e of v9Examples) {
            if (e.source !== 'openapi' || !e.op) continue;
            const parser = feParserFor(e.op, e.value);
            if (!parser) continue;
            checked++;
            if (!accepts(() => parser(structuredClone(e.value) as never)))
                rejected.push(`${e.op} ${e.code} ${e.name}`);
        }
        expect(rejected).toEqual([]);
        expect(checked).toBeGreaterThanOrEqual(39);
    });

    // fixture의 feValid는 fixture를 만들 당시 FE의 동작 기록이다. 지금 FE는 계약 판정(valid)과 같아야 한다.
    it('계약 fixture를 FE 검사기가 계약 판정(valid)대로 받거나 거절한다', () => {
        const wrong: string[] = [];
        let checked = 0;
        for (const e of v9Examples) {
            if (e.source === 'openapi' || !e.schema) continue;
            const parser = FE_PARSERS_BY_SCHEMA[e.schema];
            if (!parser) continue;
            checked++;
            const expected = e.valid;
            if (accepts(() => parser(structuredClone(e.value) as never)) !== expected)
                wrong.push(`${e.source} ${e.name} 기대 ${expected}`);
        }
        expect(wrong).toEqual([]);
        // 교차 계약 변경안 44건, 장비 10건, 복구 포인터
        expect(checked).toBeGreaterThanOrEqual(54);
    });
});
