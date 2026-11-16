import { BASIC_FIELDS, type BasicKey } from './labels';

// 공연 공개 전에 채워야 할 조건을 화면에서 미리 알린다(W01-DRAFT 공연 관리의 노란 안내 카드).
// 서버 공개 검사(BE EventCompleteness·공개 계획 검사)를 따라가며, 최종 판단은 공개 요청의 422 응답이다.
// 시각은 입력 칸 값(이 기기 시간대 'YYYY-MM-DDTHH:mm')으로 비교한다.

/** 서버가 비어 있으면 거절하는 기본정보(공연 날짜·운영 시각 4개·장소·주소·정원) */
const REQUIRED: BasicKey[] = [
    'date',
    'prepStartAt',
    'openAt',
    'closeAt',
    'teardownEndAt',
    'locationName',
    'address',
    'totalCapacity',
];
const LABEL = Object.fromEntries(BASIC_FIELDS.map((f) => [f.key, f.label])) as Record<
    BasicKey,
    string
>;

export type PublishState = {
    /** 입력 칸에 보이는 값(저장하지 않은 편집 포함) */
    values: Record<BasicKey, string>;
    /** 저장하지 않은 기본정보 편집이 있다 */
    unsaved: boolean;
    /** 상세 정보(계획) 팝업에 저장하지 않은 편집이 있다 */
    planEditing: boolean;
    /** 저장된 계획의 직무 그룹·업무 수. 못 읽었으면 null(조건을 띄우지 않는다) */
    plan: { workGroups: number; tasks: number } | null;
    /** 형식이 틀린 칸과 맞는 형식 안내. 이 칸들은 비었다고 하지 않고 다른 검사에서도 뺀다 */
    invalid?: { key: BasicKey; hint: string }[];
};

const whole = (v: string) => (/^\d+$/.test(v.trim()) ? Number(v.trim()) : null);

/** 아직 채우지 못한 공개 조건을 사람이 읽는 줄로 돌려준다. 없으면 빈 배열 */
export function publishChecklist({
    values,
    unsaved,
    planEditing,
    plan,
    invalid = [],
}: PublishState): string[] {
    const out: string[] = [];
    const bad = new Set(invalid.map((i) => i.key));
    const v = (key: BasicKey) => (bad.has(key) ? '' : (values[key] ?? '').trim());

    for (const { key, hint } of invalid) out.push(`${LABEL[key]} 형식 확인(${hint})`);
    const missing = REQUIRED.filter((key) => !bad.has(key) && !v(key));
    if (missing.length) out.push(`입력할 항목: ${missing.map((key) => LABEL[key]).join(', ')}`);

    const [prep, open, close, teardown] = [
        v('prepStartAt'),
        v('openAt'),
        v('closeAt'),
        v('teardownEndAt'),
    ];
    if (
        (prep && open && prep > open) ||
        (open && close && open >= close) ||
        (close && teardown && close > teardown)
    )
        out.push('시각 순서: 준비 시작 ≤ 공연 시작 < 공연 종료 ≤ 철수 종료');
    const date = v('date');
    if (
        date &&
        ((prep && prep.slice(0, 10) !== date) || (teardown && teardown.slice(0, 10) !== date))
    )
        out.push('준비 시작과 철수 종료는 공연 날짜와 같은 날이어야 합니다');

    const capacity = v('totalCapacity') ? whole(v('totalCapacity')) : undefined;
    if (capacity === null || (capacity !== undefined && (capacity < 1 || capacity > 31)))
        out.push('운영 인원 정원은 1~31명입니다');
    const reserve = whole(v('desiredReserveCount') || '0');
    if (reserve === null || (typeof capacity === 'number' && reserve >= capacity))
        out.push('희망 예비 인원은 0명 이상, 운영 인원 정원보다 적어야 합니다');
    for (const key of ['expectedVisitors', 'peakVisitors'] as const)
        if (v(key) && whole(v(key)) === null)
            out.push(`${LABEL[key]}는 0 이상의 숫자로 입력해주세요`);

    if (plan && plan.workGroups === 0)
        out.push('상세 정보 업로드에서 직무 그룹을 1개 이상 만들어 저장해주세요');
    if (plan && plan.tasks === 0)
        out.push('상세 정보 업로드에서 업무를 1개 이상 만들어 저장해주세요');
    if (unsaved) out.push('입력한 기본정보를 임시 저장해주세요');
    if (planEditing) out.push('상세 정보의 계획 편집을 저장해주세요');
    return out;
}
