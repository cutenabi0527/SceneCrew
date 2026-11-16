// 공연 목록 카드의 "날짜 / 시간 / 장소" 한 줄. 정해지지 않은 값은 "미정"으로 쓴다.

const ZONE = 'Asia/Seoul';
const timeFmt = new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
});

export const UNDECIDED = '미정';

/** 'YYYY-MM-DD' → '11월 14일' */
function dateLabel(date: string | null) {
    const m = date && /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
    return m ? `${Number(m[2])}월 ${Number(m[3])}일` : UNDECIDED;
}

function timeOf(iso: string | null) {
    if (!iso) return null;
    const at = new Date(iso);
    return Number.isNaN(at.getTime()) ? null : timeFmt.format(at);
}

/** 시작·끝 시각 → '17:00–21:30'. 한쪽만 있으면 그쪽만, 둘 다 없으면 미정 */
function timeLabel(openAt: string | null, closeAt: string | null) {
    const open = timeOf(openAt);
    const close = timeOf(closeAt);
    if (open && close) return `${open}–${close}`;
    if (open) return `${open}–`;
    if (close) return `–${close}`;
    return UNDECIDED;
}

export function scheduleLine(event: {
    date: string | null;
    openAt: string | null;
    closeAt: string | null;
    locationName: string | null;
}) {
    return [
        dateLabel(event.date),
        timeLabel(event.openAt, event.closeAt),
        event.locationName ?? UNDECIDED,
    ].join(' / ');
}
