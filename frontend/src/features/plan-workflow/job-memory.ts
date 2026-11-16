// 계획 생성·문서 추출 작업 기억(#268). 이 브라우저 탭의 세션 저장소에 참여 회차별로 둔다.
// 새로고침 뒤에도 진행 중이거나 끝난 작업 결과를 다시 보여 주고, 계획 후보를 가져올 때 그 작업을 만든
// 시점의 revision과 비교할 수 있게 한다. 참여 범위가 끝나면(로그아웃·회차 변경) 지운다.

export type JobKind = 'generate' | 'extract';
export type JobMemory = {
    jobs: Partial<Record<JobKind, string>>;
    /** 작업 id → 접수할 때의 공연 revision */
    revisions: Record<string, string>;
};

const PREFIX = 'scenecrew.plan-jobs';

export function jobMemoryKey(scope: {
    eventId: string;
    memberId: string;
    participationRevision: string;
}) {
    return `${PREFIX}:${scope.eventId}:${scope.memberId}:${scope.participationRevision}`;
}

const isText = (v: unknown): v is string => typeof v === 'string' && v.length > 0;

/** 저장된 기억을 읽는다. 없거나 형식이 다르면 빈 기억이다(형식이 다른 값은 쓰지 않는다). */
export function readJobMemory(key: string): JobMemory {
    const empty: JobMemory = { jobs: {}, revisions: {} };
    let raw: string | null;
    try {
        raw = sessionStorage.getItem(key);
    } catch {
        return empty;
    }
    if (!raw) return empty;
    try {
        const value = JSON.parse(raw) as Record<string, unknown>;
        const jobs = (value.jobs ?? {}) as Record<string, unknown>;
        const revisions = (value.revisions ?? {}) as Record<string, unknown>;
        const out: JobMemory = { jobs: {}, revisions: {} };
        for (const kind of ['generate', 'extract'] as const)
            if (isText(jobs[kind])) out.jobs[kind] = jobs[kind];
        for (const [id, rev] of Object.entries(revisions))
            if (isText(id) && isText(rev) && /^(0|[1-9][0-9]*)$/.test(rev)) out.revisions[id] = rev;
        return out;
    } catch {
        return empty;
    }
}

export function writeJobMemory(key: string, memory: JobMemory) {
    try {
        if (!Object.keys(memory.jobs).length) sessionStorage.removeItem(key);
        else sessionStorage.setItem(key, JSON.stringify(memory));
    } catch {
        // 저장하지 못하면 이 화면에서만 기억한다(새로고침하면 결과를 다시 보이지 못한다).
    }
}

/**
 * 마지막으로 저장한 계획 생성 조건(confirmedInput). 서버 조회 응답에는 조건이 없고 저장(PATCH input)은 전체를
 * 바꾸므로, 새로고침 뒤 질문 답·추출 채우기만으로 저장하면 기존 조건이 모두 지워진다. 이 탭에서 저장한 조건을
 * 기억해 편집 칸의 시작값으로 쓴다(#268 리뷰). 다른 탭·기기에서 저장한 조건은 알 수 없다.
 */
const conditionsKey = (key: string) => `${key}:conditions`;

/** 이 탭에서 조건을 저장(또는 기억)한 적이 있는가. 없으면 서버에 어떤 조건이 있는지 이 화면은 모른다 */
export function hasConditionsMemory(key: string): boolean {
    try {
        return sessionStorage.getItem(conditionsKey(key)) !== null;
    } catch {
        return false;
    }
}

export function readConditions(key: string): Record<string, string> {
    try {
        const raw = sessionStorage.getItem(conditionsKey(key));
        if (!raw) return {};
        const value = JSON.parse(raw) as unknown;
        if (typeof value !== 'object' || value === null || Array.isArray(value)) return {};
        const out: Record<string, string> = {};
        for (const [k, v] of Object.entries(value)) if (typeof v === 'string') out[k] = v;
        return out;
    } catch {
        return {};
    }
}

export function writeConditions(key: string, conditions: Record<string, string>) {
    try {
        sessionStorage.setItem(conditionsKey(key), JSON.stringify(conditions));
    } catch {
        // 저장하지 못하면 새로고침 뒤 조건 칸이 비어 시작한다(전체 교체 경고가 남아 있다)
    }
}

/**
 * 이 탭에서 올린 공연 문서 목록(이름·올린 시각). 서버는 등록 문서 목록을 주지 않고 등록 응답에도 올린 시각이
 * 없어서, 등록이 끝난 때의 이 기기 시각을 함께 기억해 문서 입력 아래 목록에 보인다.
 */
export type UploadedDocument = { id: string; name: string; uploadedAt: string };
const documentsKey = (key: string) => `${key}:documents`;

export function readDocuments(key: string): UploadedDocument[] {
    try {
        const raw = sessionStorage.getItem(documentsKey(key));
        if (!raw) return [];
        const value = JSON.parse(raw) as unknown;
        if (!Array.isArray(value)) return [];
        return value.filter(
            (d): d is UploadedDocument =>
                typeof d === 'object' &&
                d !== null &&
                typeof d.id === 'string' &&
                typeof d.name === 'string' &&
                typeof d.uploadedAt === 'string',
        );
    } catch {
        return [];
    }
}

export function writeDocuments(key: string, documents: UploadedDocument[]) {
    try {
        sessionStorage.setItem(documentsKey(key), JSON.stringify(documents));
    } catch {
        // 저장하지 못하면 새로고침 뒤 목록이 비어 보인다(서버의 등록 문서는 그대로다)
    }
}

/**
 * 마지막으로 성공한 문서 추출(작업 id·끝난 것을 본 시각). 서버 작업 상태에는 시각이 없고 지난 추출 목록도 없어서,
 * 이 탭에서 추출이 끝난 것을 처음 본 때를 기억해 '문서 추출 요청' 옆에 보인다. 같은 작업은 시각을 다시 쓰지 않는다.
 */
export type ExtractionRecord = { jobId: string; at: string };
const extractionKey = (key: string) => `${key}:extraction`;

export function readLastExtraction(key: string): ExtractionRecord | null {
    try {
        const raw = sessionStorage.getItem(extractionKey(key));
        if (!raw) return null;
        const value = JSON.parse(raw) as Partial<ExtractionRecord> | null;
        return value && typeof value.jobId === 'string' && typeof value.at === 'string'
            ? { jobId: value.jobId, at: value.at }
            : null;
    } catch {
        return null;
    }
}

export function writeLastExtraction(key: string, record: ExtractionRecord) {
    try {
        sessionStorage.setItem(extractionKey(key), JSON.stringify(record));
    } catch {
        // 저장하지 못하면 새로고침 뒤 마지막 추출 시각이 보이지 않는다
    }
}

export function clearJobMemory(key: string) {
    try {
        sessionStorage.removeItem(key);
        sessionStorage.removeItem(conditionsKey(key));
        sessionStorage.removeItem(documentsKey(key));
        sessionStorage.removeItem(extractionKey(key));
        // 문서 표 아래 장비 목록 미리보기(ReferenceFiles)
        sessionStorage.removeItem(`${key}:equipmentSheet`);
    } catch {
        // 지울 수 없으면 둔다. 다음 참여 회차는 키가 달라 섞이지 않는다.
    }
}
