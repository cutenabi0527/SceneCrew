import { ContractViolationError, asBigIntString, asUtcTimestamp, asUuid } from './contract';

// 공개 OpenAPI에서 옮겨 온 JSON 스키마로 응답을 검사한다. 계약에 없는 필드·형식·상한을 거절한다.
// 지원하는 키워드만 해석하며 x-·description 같은 설명 키는 무시한다. 지원 목록은 SUPPORTED_KEYWORDS이고,
// 계약 묶음에 목록 밖의 키워드가 나오면 계약 시험이 실패한다(조용히 무시되지 않게).

export const SUPPORTED_KEYWORDS = [
    '$ref',
    'type',
    'properties',
    'required',
    'additionalProperties',
    'items',
    'enum',
    'const',
    'anyOf',
    'oneOf',
    'allOf',
    'if',
    'then',
    'not',
    'minLength',
    'maxLength',
    'minItems',
    'maxItems',
    'minimum',
    'maximum',
    'exclusiveMinimum',
    'exclusiveMaximum',
    'uniqueItems',
    'pattern',
    'format',
    'minProperties',
] as const;

export type Schema = {
    $ref?: string;
    type?: string | string[];
    properties?: Record<string, Schema>;
    required?: string[];
    additionalProperties?: boolean | Schema;
    items?: Schema;
    enum?: unknown[];
    const?: unknown;
    anyOf?: Schema[];
    oneOf?: Schema[];
    allOf?: Schema[];
    if?: Schema;
    then?: Schema;
    not?: Schema;
    minLength?: number;
    maxLength?: number;
    minItems?: number;
    maxItems?: number;
    minimum?: number;
    maximum?: number;
    exclusiveMinimum?: number;
    exclusiveMaximum?: number;
    uniqueItems?: boolean;
    pattern?: string;
    format?: string;
    minProperties?: number;
};
export const fail = (path: string): never => {
    throw new ContractViolationError(path, '현재 공개 계약의 형식/상한/참조를 확인해주세요.');
};
/**
 * 스키마 묶음(이름 → 스키마)으로 검사 함수를 만든다. $ref는 마지막 경로 조각을 같은 묶음의 이름으로 읽는다.
 * 통과한 값은 그대로 돌려준다(복사·변환하지 않는다).
 */
export function schemaParser<Names extends string>(
    schemas: Record<Names, unknown>,
    // true면 additionalProperties:false를 무시한다. 필요한 필드만 읽는 손 검사기가 계약의 형식·필수·상한은
    // 그대로 검사하되, BE가 먼저 더한 필드 때문에 앱이 깨지지 않게 할 때 쓴다.
    options: { allowUnknownFields?: boolean } = {},
) {
    const definitions = schemas as unknown as Record<string, Schema>;
    function matches(schema: Schema, value: unknown, path: string) {
        try {
            check(schema, value, path);
            return true;
        } catch (e) {
            if (e instanceof ContractViolationError) return false;
            throw e;
        }
    }
    function check(s: Schema, v: unknown, path: string): void {
        if (s.$ref) check(definitions[s.$ref.split('/').at(-1)!], v, path);
        if (s.const !== undefined && v !== s.const) fail(path);
        if (s.enum && !s.enum.includes(v)) fail(path);
        if (s.anyOf && !s.anyOf.some((b) => matches(b, v, path))) fail(path);
        if (s.oneOf && s.oneOf.filter((b) => matches(b, v, path)).length !== 1) fail(path);
        s.allOf?.forEach((b) => check(b, v, path));
        if (s.not && matches(s.not, v, path)) fail(path);
        if (s.if && matches(s.if, v, path) && s.then) check(s.then, v, path);
        if (s.type) {
            const types = Array.isArray(s.type) ? s.type : [s.type];
            if (
                !types.some((t) =>
                    t === 'null'
                        ? v === null
                        : t === 'array'
                          ? Array.isArray(v)
                          : t === 'object'
                            ? v !== null && typeof v === 'object' && !Array.isArray(v)
                            : t === 'integer'
                              ? Number.isInteger(v)
                              : typeof v === t,
                )
            )
                fail(path);
        }
        if (typeof v === 'string') {
            if (
                [...v].length < (s.minLength ?? 0) ||
                [...v].length > (s.maxLength ?? Infinity) ||
                (s.pattern && !new RegExp(s.pattern).test(v))
            )
                fail(path);
            if (s.format === 'uuid') asUuid(v, path);
            if (s.format === 'date-time') asUtcTimestamp(v, path);
            if (s.format === 'date') asUtcTimestamp(`${v}T00:00:00Z`, path);
            if (s.$ref?.endsWith('/DecimalBigInt')) asBigIntString(v, path);
        }
        if (
            typeof v === 'number' &&
            (!Number.isFinite(v) ||
                v < (s.minimum ?? -Infinity) ||
                v > (s.maximum ?? Infinity) ||
                (s.exclusiveMinimum !== undefined && v <= s.exclusiveMinimum) ||
                (s.exclusiveMaximum !== undefined && v >= s.exclusiveMaximum))
        )
            fail(path);
        if (Array.isArray(v)) {
            if (v.length < (s.minItems ?? 0) || v.length > (s.maxItems ?? Infinity)) fail(path);
            if (s.uniqueItems && new Set(v.map((item) => JSON.stringify(item))).size !== v.length)
                fail(path);
            if (s.items) v.forEach((item, i) => check(s.items!, item, `${path}[${i}]`));
        }
        if (v && typeof v === 'object' && !Array.isArray(v)) {
            const obj = v as Record<string, unknown>;
            // 빠진 필수 필드는 그 이름까지 알린다
            const missing = (s.required ?? []).find((k) => !Object.hasOwn(obj, k));
            if (missing !== undefined) fail(`${path}.${missing}`);
            if (Object.keys(obj).length < (s.minProperties ?? 0)) fail(path);
            for (const [k, value] of Object.entries(obj)) {
                if (s.properties && Object.hasOwn(s.properties, k))
                    check(s.properties[k], value, `${path}.${k}`);
                else if (s.additionalProperties === false) {
                    if (!options.allowUnknownFields) fail(`${path}.${k}`);
                } else if (typeof s.additionalProperties === 'object')
                    check(s.additionalProperties, value, `${path}.${k}`);
            }
        }
    }
    return function parse<T>(name: Names, value: unknown): T {
        check(definitions[name], value, name);
        return value as T;
    };
}
