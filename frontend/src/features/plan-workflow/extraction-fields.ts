import { BASIC_FIELDS } from '../event-admin/labels';

// 문서 추출 결과(#236, 결과3 fields)의 키를 사람이 읽는 이름으로 바꾸고, 어디에 채울지 가른다(#362).
// BE·AI가 맞춘 키 제안은 공연 기본정보(Event 컬럼)다(backend/docs/document-extraction.md). 그 밖의 키는
// 계획 생성 조건(confirmedInput)으로 보낸다. 어느 쪽이든 화면이 채우기만 하고, 저장은 총괄이 명시로 한다.

const BASIC_LABEL: Record<string, string> = Object.fromEntries(
    BASIC_FIELDS.map((f) => [f.key, f.label]),
);

/** 추출 키의 표시 이름. 모르는 키는 키 그대로 보인다. */
export function extractionLabel(key: string) {
    return BASIC_LABEL[key] ?? key;
}

export type ExtractedInput = {
    /** 공연 기본정보 칸에 채울 값 */
    basics: Record<string, string>;
    /** 계획 생성 조건으로 넣을 값 */
    conditions: Record<string, string>;
};

export function splitExtracted(fields: Record<string, string>): ExtractedInput {
    const basics: Record<string, string> = {};
    const conditions: Record<string, string> = {};
    for (const [key, value] of Object.entries(fields)) {
        if (key in BASIC_LABEL) basics[key] = value;
        else conditions[key] = value;
    }
    return { basics, conditions };
}

/**
 * 추출한 구역 조건(예: "A구역 무대 앞, B구역 객석 뒤쪽")을 구역 이름 목록으로 나눈다(#401 운영 확인).
 * 빈 이름·같은 이름은 빼고, 계획 초안 구역 상한(10개)까지만 쓴다.
 */
export function zonesFromCondition(text: string | undefined): string[] {
    const names = (text ?? '')
        .split(/[,，、]/)
        .map((z) => z.trim())
        .filter(Boolean);
    return [...new Set(names)].slice(0, 10);
}
