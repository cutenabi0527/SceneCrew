import { describe, expect, it } from 'vitest';
import { FE_PARSERS, HAND_WRITTEN } from './fe-parsers';
import { parseV9, type V9SchemaName } from './index';
import { responseSchema, v9Examples } from './testing';
import { mutations } from './mutations';

// 손으로 짠 검사기(스키마를 옮기지 않은 것)가 v9 계약과 같게 판정하는지 본다.
// 계약 2xx 예시를 필수 필드 삭제·모르는 필드 추가·타입 변경·모르는 enum·비허용 null 등으로 망가뜨려
// 스키마 검사기와 손 검사기에 함께 넣는다.
//  - 손 검사기가 계약보다 느슨한 경우는 "모르는 필드 허용(extra)"만 인정한다. BE가 먼저 필드를 더해도
//    앱이 깨지지 않게 하려는 의도이며(필요한 필드만 읽는다), 그 밖의 느슨함은 결함이다.
//  - 손 검사기가 계약보다 엄격한 경우는 계약 문장 규칙(채널별 토큰 등) 때문일 때만 아래에 이유를 적어 인정한다.

const STRICTER_BY_RULE: Record<string, string> = {};

const accepts = (fn: () => unknown) => {
    try {
        fn();
        return true;
    } catch {
        return false;
    }
};

describe('손 검사기와 v9 스키마 판정', () => {
    const diffs: { loose: boolean; key: string; kind: string; path: string }[] = [];
    let compared = 0;
    for (const e of v9Examples) {
        if (!e.op || e.source !== 'openapi') continue;
        const key =
            e.op === 'GET /ai-jobs/{j}'
                ? `${e.op}#${String((e.value as { kind?: string }).kind)}`
                : e.op;
        if (!HAND_WRITTEN.has(key)) continue;
        const parser = FE_PARSERS[key];
        const schema = responseSchema(e.op, e.code!) as V9SchemaName;
        for (const m of mutations(e.value)) {
            compared++;
            const s = accepts(() => parseV9(schema, structuredClone(m.value)));
            const f = accepts(() => parser(structuredClone(m.value) as never));
            if (s !== f) diffs.push({ loose: f, key, kind: m.kind, path: m.path });
        }
    }

    it('충분한 변형을 비교했다', () => {
        expect(compared).toBeGreaterThan(400);
    });

    it('손 검사기는 모르는 필드 말고는 계약이 거절하는 응답을 받지 않는다', () => {
        expect(
            diffs
                .filter((d) => d.loose && d.kind !== 'extra')
                .map((d) => `${d.key} ${d.kind} ${d.path}`),
        ).toEqual([]);
    });

    it('손 검사기가 계약보다 엄격하면 계약 문장 규칙이라는 이유가 있다', () => {
        expect(
            diffs
                .filter((d) => !d.loose && !STRICTER_BY_RULE[`${d.key} ${d.kind} ${d.path}`])
                .map((d) => `${d.key} ${d.kind} ${d.path}`),
        ).toEqual([]);
    });
});
