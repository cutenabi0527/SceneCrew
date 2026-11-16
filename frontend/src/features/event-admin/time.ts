// 공연 기본정보의 시각 입력(datetime-local)과 API의 RFC 3339 사이를 변환한다(#286).
// 입력은 이 기기의 시간대로 받고, 보낼 때 그 시간대 오프셋을 붙인다. 서버는 UTC로 저장·응답한다.

const pad = (n: number) => String(n).padStart(2, '0');

/** API 시각(UTC) → datetime-local 값(YYYY-MM-DDTHH:mm, 이 기기 시간대). 비었거나 잘못되면 ''. */
export function toLocalInput(iso: string | null | undefined): string {
    if (!iso) return '';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** datetime-local 값 → RFC 3339(이 기기 시간대 오프셋 포함). 형식이 다르면 null. */
export function fromLocalInput(value: string): string | null {
    const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
    if (!m) return null;
    const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);
    if (Number.isNaN(d.getTime()) || d.getMonth() !== +m[2] - 1 || d.getDate() !== +m[3])
        return null;
    const offset = -d.getTimezoneOffset();
    const sign = offset >= 0 ? '+' : '-';
    const abs = Math.abs(offset);
    return `${value}:00${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}
