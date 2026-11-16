// 공연 만들기 PDF 탭에서 고른 파일을 계획 입력 화면의 PDF 탭으로 넘긴다(V9-D65).
// 같은 페이지 안 이동이라 메모리에만 둔다. 새로고침하면 사라지고, 그때는 PDF 탭이 파일을 다시 고르게 한다.

const pending = new Map<string, File[]>();

export function handFiles(eventId: string, files: File[]) {
    pending.set(eventId, [...files]);
}

/** 넘겨받은 파일. 자동 진행을 시작한 뒤 dropFiles로 지운다(두 번 올리지 않는다). */
export function peekFiles(eventId: string): File[] | null {
    return pending.get(eventId) ?? null;
}

export function dropFiles(eventId: string) {
    pending.delete(eventId);
}

/** 첫 파일 이름(확장자 제외)으로 만든 임시 공연 이름. 문서에 공연 이름이 있으면 자동 진행이 바꾼다. */
export function provisionalName(files: File[]): string {
    const base = (files[0]?.name ?? '').replace(/\.[^.]+$/, '').trim();
    const name = [...(base || '새 공연')].slice(0, 20).join('').trim();
    return name || '새 공연';
}
