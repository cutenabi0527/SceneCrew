// 공연 관리 화면의 표시 문구(#286). 공개 거절(422 EVENT_INCOMPLETE)의 필드·사유를 사람이 읽는 말로 바꾼다.

export const STATE_LABEL = {
    DRAFT: '작성 중',
    RECRUITING: '참여 모집 중',
    LIVE: '운영 중',
    ENDED: '종료',
} as const;

export const BASIC_FIELDS = [
    { key: 'name', label: '공연 이름', kind: 'text', required: true },
    { key: 'description', label: '공연 설명', kind: 'textarea', required: true },
    { key: 'date', label: '공연 날짜', kind: 'date' },
    { key: 'prepStartAt', label: '준비 시작 시각', kind: 'time' },
    { key: 'openAt', label: '공연 시작 시각', kind: 'time' },
    { key: 'closeAt', label: '공연 종료 시각', kind: 'time' },
    { key: 'teardownEndAt', label: '철수 종료 시각', kind: 'time' },
    { key: 'locationName', label: '장소 이름', kind: 'text' },
    { key: 'address', label: '주소', kind: 'text' },
    { key: 'totalCapacity', label: '운영 인원 정원(1~31)', kind: 'number' },
    { key: 'expectedVisitors', label: '예상 관객 수', kind: 'number' },
    { key: 'peakVisitors', label: '최대 예상 관객 수', kind: 'number' },
    { key: 'desiredReserveCount', label: '희망 예비 인원', kind: 'number', required: true },
] as const;

export type BasicKey = (typeof BASIC_FIELDS)[number]['key'];

const FIELD_LABEL: Record<string, string> = {
    ...Object.fromEntries(BASIC_FIELDS.map((f) => [f.key, f.label])),
    workGroups: '직무 그룹',
    tasks: '업무',
    zones: '구역',
    programs: '프로그램',
    'programs.zoneId': '프로그램의 구역',
    'programs.time': '프로그램 시간',
    'tasks.workGroupId': '업무의 직무 그룹',
    'tasks.zoneId': '업무의 구역',
    'tasks.programId': '업무의 프로그램',
    'tasks.time': '업무 시간',
    'tasks.steps': '업무 절차',
    'tasks.checklist': '업무 점검 항목',
    'tasks.bundleKey': '업무 묶음',
};

const REASON_LABEL: Record<string, string> = {
    MISSING: '비어 있습니다',
    BLANK: '공백만 입력되어 있습니다',
    ORDER: '시각 순서가 맞지 않습니다(준비 ≤ 공연 시작 < 공연 종료 ≤ 철수)',
    DATE_MISMATCH: '공연 날짜와 다른 날입니다',
    RANGE: '허용 범위를 벗어났습니다',
    LIMIT: '개수 상한을 넘었습니다',
    INVALID_REFERENCE: '연결된 대상이 맞지 않습니다',
    TIME_OUT_OF_RANGE: '운영 시간 밖이거나 시작이 끝보다 늦습니다',
    DUPLICATE: '중복된 항목이 있습니다',
    BUNDLE_MISMATCH: '같은 묶음이 다른 그룹에 있습니다',
    TIME_OVERLAP: '같은 묶음 안에서 시간이 겹칩니다',
};

/** 공개 거절 한 줄. 빈 계획(그룹·업무 없음)은 따로 설명한다. */
export function incompleteLine(item: { field: string; reason: string }) {
    if (item.reason === 'MISSING' && item.field === 'workGroups')
        return '활성 직무 그룹이 1개 이상 있어야 합니다.';
    if (item.reason === 'MISSING' && item.field === 'tasks')
        return '활성 그룹에 취소되지 않은 업무가 1개 이상 있어야 합니다.';
    const field = FIELD_LABEL[item.field] ?? item.field;
    const reason = REASON_LABEL[item.reason] ?? item.reason;
    return `${field}: ${reason}`;
}
