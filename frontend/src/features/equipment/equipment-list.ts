/** 한 번에 읽는 자료 크기·행 수 상한(현장 장비 목록 규모) */
export const MAX_BYTES = 256 * 1024;
const MAX_ROWS = 200;

export type ImportRow = { name: string; kind: string; place: string; error?: string };

/**
 * 장비 목록 자료(CSV·TXT·MD)를 "이름, 종류, 위치" 행으로 읽는다. 첫 줄이 머리글이면 건너뛴다.
 * MD 표(| 이름 | 종류 | 위치 |)도 같은 열 순서로 읽는다. 읽은 값은 후보일 뿐이며 확인 뒤에만 등록한다.
 */
export function parseEquipmentList(text: string): ImportRow[] {
    const lines = text
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l && !/^\|?\s*:?-{2,}/.test(l));
    const rows = lines.map((line) => {
        const cells = (
            line.startsWith('|') ? line.replace(/^\||\|$/g, '').split('|') : line.split(/\t|,/)
        ).map((c) => c.trim());
        return cells;
    });
    if (rows[0] && /이름|name/i.test(rows[0][0] ?? '')) rows.shift();
    return rows.slice(0, MAX_ROWS).map(([name = '', kind = '', place = '']) => ({
        name,
        kind,
        place: place || '미확인',
        error: !name ? '이름 없음' : !kind ? '종류 없음' : undefined,
    }));
}
