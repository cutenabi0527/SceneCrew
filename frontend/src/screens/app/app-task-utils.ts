import { NOW_MIN, toMin } from '../../domain/selectors';
import { EVENT_DATE } from '../../domain/seed';
import type { Task } from '../../domain/types';

// APP 내 업무·업무 상세가 함께 쓰는 계산(컴포넌트 파일과 나눠 둔다: 빠른 새로고침 규칙)

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토'];

/** '11월 14일 (토)'. 오늘이면 앞에 '오늘 · '를 붙인다(today) */
export function dateLabel(iso: string, today = false) {
    const [y, m, d] = iso.split('-').map(Number);
    const w = WEEKDAY[new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
    const day = `${m}월 ${d}일 (${w})`;
    return today && iso === EVENT_DATE ? `오늘 · ${day}` : day;
}

export const isClosed = (t: Task) => t.status === 'done' || t.status === 'canceled';

/** 진행 막대 값: 예정 시간 중 지난 비율(0~1) */
export function progressOf(t: Task) {
    const a = toMin(t.start);
    const b = toMin(t.end);
    if (b <= a) return 1;
    return Math.min(1, Math.max(0, (NOW_MIN - a) / (b - a)));
}
