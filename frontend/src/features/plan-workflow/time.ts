export function displayTime(value: string) {
    return new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });
}

/** UTC ISO 시각을 datetime-local 입력값(기기 시간대)으로 바꾼다 */
export function toLocalInput(iso: string) {
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
