import { BASIC_FIELDS, type BasicKey } from './labels';
import { toLocalInput } from './time';

// 문서 추출(#236) 후보를 공연 관리의 기본정보 편집 칸 값으로 바꾼다(#362). 저장은 하지 않는다.

/**
 * 시각 칸 후보. 날짜와 시:분이 모두 있는 ISO 형식만 받는다. 날짜만 있으면(예: 2026-11-18) Date가 UTC 자정으로
 * 읽어 09:00 같은 없던 시각이 생기므로 채우지 않는다. 시간대가 없으면 이 기기 시간대 시각으로 본다.
 */
const ISO_TIME = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})?$/;
/** 문서 추출기는 시각 칸을 `17:30:00`처럼 시각만 준다. 같은 추출의 공연 날짜가 있을 때만 그 날짜와 합친다. */
const CLOCK = /^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
function timeValue(raw: string, date: string | undefined) {
    const text = raw.trim();
    if (ISO_TIME.test(text)) return toLocalInput(text.replace(' ', 'T'));
    const day = date?.trim();
    return CLOCK.test(text) && day && DATE.test(day) ? toLocalInput(`${day}T${text}`) : '';
}

/** 추출 후보 → 기본정보 편집 칸 값. 시각·날짜로 읽을 수 없는 값은 채우지 않는다. */
export function prefillEdits(prefill: Record<string, string>) {
    const next: Partial<Record<BasicKey, string>> = {};
    for (const f of BASIC_FIELDS) {
        const raw = prefill[f.key];
        if (raw === undefined) continue;
        const value =
            f.kind === 'time'
                ? timeValue(raw, prefill.date)
                : f.kind === 'date'
                  ? /^\d{4}-\d{2}-\d{2}/.test(raw)
                      ? raw.slice(0, 10)
                      : ''
                  : raw;
        if (value) next[f.key] = value;
    }
    return next;
}
