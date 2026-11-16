import { ContractViolationError } from '../contract';
import examplesFile from './examples.json';
import operationsFile from './operations.json';
import { parseV9, type V9SchemaName } from './index';

// 시험 전용: v9 연산 목록과 계약 예시·fixture. 앱 코드에서 불러오지 않는다.

export interface V9Operation {
    operationId: string;
    apiId: string;
    status: 'connected' | 'planned';
    idempotency: string | null;
    request: Record<string, string>;
    responses: Record<string, Record<string, string | null> | Record<string, never>>;
}

const { $source: source, ...ops } = operationsFile as unknown as Record<string, V9Operation> & {
    $source: string;
};
export const V9_SOURCE = source;
export const v9Operations = ops as Record<string, V9Operation>;

export interface V9Example {
    source: string;
    op: string | null;
    code: string | null;
    contentType: string;
    name: string;
    schema: V9SchemaName | null;
    valid: boolean;
    feValid?: boolean;
    value: unknown;
}
export const v9Examples = (examplesFile as unknown as { items: V9Example[] }).items;

/** 연산의 응답 스키마 이름. 계약에 없는 연산·코드면 시험이 바로 실패하도록 던진다. */
export function responseSchema(op: string, code: string, contentType = 'application/json') {
    const name = v9Operations[op]?.responses[code]?.[contentType as never] as string | undefined;
    if (!name)
        throw new ContractViolationError(op, `${code} ${contentType} 응답 스키마가 없습니다.`);
    return name as V9SchemaName;
}

/** 연산의 2xx 응답을 v9 스키마로 검사한다. */
export function parseResponse<T>(op: string, code: string, value: unknown): T {
    return parseV9<T>(responseSchema(op, code), value);
}
