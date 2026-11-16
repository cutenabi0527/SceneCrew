import { schemaParser } from '../schema';
import schemasFile from './schemas.json';

// v9 계약(#327, develop 병합본) 묶음. frontend/docs/tools/v9_bundle.py가 만든다. 손으로 고치지 않는다.
// 연산 목록은 frontend/docs/tools/v9-operations.txt다. 앱은 스키마만 불러온다.
// 연산 목록·계약 예시는 시험 전용(./testing.ts)이라 앱 번들에 넣지 않는다.

export type V9SchemaName = Exclude<keyof typeof schemasFile, '$source'>;
export const parseV9 = schemaParser(schemasFile as Record<V9SchemaName, unknown>);
/** 계약에 없는 필드만 허용하고 나머지(필수·형식·상한·enum)는 v9 스키마대로 검사한다. 손 검사기의 첫 단계다. */
export const checkV9Shape = schemaParser(schemasFile as Record<V9SchemaName, unknown>, {
    allowUnknownFields: true,
});
