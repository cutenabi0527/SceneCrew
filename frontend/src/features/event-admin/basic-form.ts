import type { BasicKey } from './labels';

// W01-DRAFT 공연 관리(보고서 틀)의 기본정보 입력 배치와 날짜·시각 글자 입력(#362).
// 날짜·시각은 달력 칸 대신 글자 칸으로 받고, 회색 예시(placeholder)를 보인다.
// - 공연 날짜: YYYY-MM-DD
// - 공연 시작·종료 시각: HH:mm만 받는다(날짜는 공연 날짜를 쓴다)
// - 준비 시작·철수 종료 시각: YYYY-MM-DD HH:mm
// 편집 값(edits)에는 친 글자 그대로, 또는 저장·추출 값(YYYY-MM-DDTHH:mm)이 들어 있다. 보낼 때 YYYY-MM-DDTHH:mm로 맞춘다.

/** 한 행에 둘 칸과 너비 비율 */
export const BASIC_ROWS: { keys: BasicKey[]; ratio: number[] }[] = [
    { keys: ['name', 'locationName', 'address'], ratio: [2, 1, 1] },
    {
        keys: ['totalCapacity', 'desiredReserveCount', 'expectedVisitors', 'peakVisitors'],
        ratio: [1, 1, 1, 1],
    },
    { keys: ['description'], ratio: [1] },
    { keys: ['date', 'openAt', 'closeAt'], ratio: [2, 1, 1] },
    { keys: ['prepStartAt', 'teardownEndAt'], ratio: [1, 1] },
];

/** 제목 줄 오른쪽에 회색으로 보일 최대 한도. 계약에 정해진 최댓값만 둔다(이름 20자·정원 31명). 날짜·시각 칸은 없다 */
export const FIELD_LIMIT: Partial<Record<BasicKey, string>> = {
    name: '최대 20자',
    totalCapacity: '최대 31명',
};

/** 시각만 받는 칸(날짜는 공연 날짜) */
export const TIME_ONLY: readonly BasicKey[] = ['openAt', 'closeAt'];

const LOCAL = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})$/;
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const pad = (n: number) => String(n).padStart(2, '0');

/** 실제로 있는 날짜(YYYY-MM-DD)인지 */
export function isDate(text: string) {
    const m = DATE.exec(text.trim());
    if (!m) return false;
    const d = new Date(+m[1], +m[2] - 1, +m[3]);
    return d.getMonth() === +m[2] - 1 && d.getDate() === +m[3];
}

/** 칸에 보일 글자. 저장·추출 값(YYYY-MM-DDTHH:mm)은 보기 형식으로 바꾸고, 사용자가 친 글자는 그대로 둔다 */
export function shownTime(key: BasicKey, value: string) {
    const m = LOCAL.exec(value);
    if (!m) return value;
    return TIME_ONLY.includes(key) ? m[2] : `${m[1]} ${m[2]}`;
}

function clock(hh: string, mm: string) {
    const [h, m] = [Number(hh), Number(mm)];
    return h < 24 && m < 60 ? `${pad(h)}:${pad(m)}` : null;
}

/** 시각 칸 글자 → YYYY-MM-DDTHH:mm. 빈 칸은 '', 형식이 틀리거나(시각만 칸은) 공연 날짜가 없으면 null */
export function localTime(key: BasicKey, text: string, date: string): string | null {
    const t = shownTime(key, text.trim());
    if (!t) return '';
    if (TIME_ONLY.includes(key)) {
        const m = /^(\d{1,2}):(\d{2})$/.exec(t);
        const hm = m && clock(m[1], m[2]);
        return hm && isDate(date) ? `${date.trim()}T${hm}` : null;
    }
    const m = /^(\d{4}-\d{2}-\d{2})\s+(\d{1,2}):(\d{2})$/.exec(t);
    const hm = m && clock(m[2], m[3]);
    return m && hm && isDate(m[1]) ? `${m[1]}T${hm}` : null;
}

/** 날짜·시각 칸의 회색 예시. 공연 날짜를 쓴 뒤에는 그 날짜로 보인다 */
export function example(key: BasicKey, date: string) {
    const day = isDate(date) ? date.trim() : '2026-12-24';
    if (key === 'date') return '예: 2026-12-24';
    if (key === 'openAt') return '예: 18:00';
    if (key === 'closeAt') return '예: 21:00';
    if (key === 'prepStartAt') return `예: ${day} 09:00`;
    if (key === 'teardownEndAt') return `예: ${day} 23:00`;
    return undefined;
}

/** 형식이 틀린 칸의 안내 */
export function formatHint(key: BasicKey) {
    if (key === 'date') return 'YYYY-MM-DD';
    if (TIME_ONLY.includes(key)) return 'HH:mm, 공연 날짜 먼저 입력';
    return 'YYYY-MM-DD HH:mm';
}
