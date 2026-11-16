import { unassignedBundles as serverBundles } from '../features/event-admin/bundles';
import type { Task as ApiTask } from '../features/plan-workflow/contract';
import { DEMO_DATE, DEMO_NOW, EVENT_DATE, NOW, writeClock } from './seed';
import type {
    CameraLink,
    Change,
    ChangeOp,
    EquipTech,
    EventData,
    EventInfo,
    Issue,
    IssueStatus,
    IssueForward,
    Member,
    Notice,
    ObsFresh,
    ObsQuality,
    Program,
    RecoveryVerdict,
    SafetyCamera,
    SafetyObservation,
    SafetyZone,
    SituationType,
    Slot,
    Task,
    TaskStatus,
    EventState,
} from './types';

// 화면과 무관한 순수 계산. WEB/APP 레이아웃이 같은 결과를 서로 다른 배치로 보여준다.

export const toMin = (t: string) => {
    const [h, m] = t.split(':').map(Number);
    return h * 60 + m;
};
export const fmtMin = (v: number) =>
    `${String(Math.floor(v / 60)).padStart(2, '0')}:${String(v % 60).padStart(2, '0')}`;
/** 변경안 시각은 같은 날의 HH:MM이며 빈 값·범위 밖 시각을 허용하지 않는다. */
export const isValidTimeRange = (start: string, end: string) => {
    const time = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
    return time.test(start) && time.test(end) && toMin(start) < toMin(end);
};
export let NOW_MIN = toMin(NOW);

/**
 * 화면의 지금 시각을 바꾼다(#371). 실서버 모드는 한국 현재 시각·오늘 날짜를, 데모로 돌아가면 고정값을 쓴다.
 * 값이 바뀐 뒤 상태가 다시 그려질 때 반영된다(실서버는 불러오기마다 liveLoad로 다시 그린다).
 */
export function setClock(now: string, date: string) {
    writeClock(now, date);
    NOW_MIN = toMin(now);
}
export const resetClock = () => setClock(DEMO_NOW, DEMO_DATE);
export const ago = (t: string) => {
    const d = NOW_MIN - toMin(t);
    return d < 1 ? '방금' : d < 60 ? `${d}분` : `${Math.floor(d / 60)}시간`;
};

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
    todo: '대기',
    doing: '진행',
    done: '완료',
    canceled: '취소',
};
export const ISSUE_STATUS_LABEL: Record<IssueStatus, string> = {
    RECEIVED: '접수됨',
    RESPONDING: '대응 중',
    RESOLUTION_REPORTED: '해결 보고',
    CLOSED: '종료',
};
export const EVENT_STATE_LABEL: Record<EventInfo['state'], string> = {
    DRAFT: '작성',
    RECRUITING: '모집',
    LIVE: '운영',
    ENDED: '종료',
};

export const byId = (d: EventData, id: string | null | undefined) =>
    id ? d.members.find((m) => m.id === id) : undefined;
export const nameOf = (d: EventData, id: string | null | undefined) =>
    byId(d, id)?.name ?? d.invitees.find((x) => x.id === id)?.name ?? '미배정';
export const groupName = (d: EventData, id: string | null | undefined) =>
    d.groups.find((g) => g.id === id)?.name ?? '그룹 없음';
/**
 * 같은 업무 묶음의 키(원 업무 id). 여러 명 배정으로 복사된 업무는 copyOf로 원 업무를 가리킨다.
 * copyOf가 없는 예전 복사본은 "원 업무 id-…" 꼴의 id와 같은 제목으로 원 업무를 찾는다.
 */
export function taskRoot(d: EventData, t: Task): string {
    if (t.copyOf) return t.copyOf;
    const parent = d.tasks
        .filter((u) => u.id !== t.id && t.id.startsWith(`${u.id}-`) && u.title === t.title)
        .sort((a, b) => a.id.length - b.id.length)[0];
    return parent ? taskRoot(d, parent) : t.id;
}

/** 같은 업무(원 업무 + 복사본) 중 취소되지 않은 것. 원 업무가 먼저 온다 */
export function taskFamily(d: EventData, t: Task): Task[] {
    const root = taskRoot(d, t);
    return d.tasks.filter((u) => u.status !== 'canceled' && taskRoot(d, u) === root);
}

/** 삭제(취소)된 업무를 되살릴 수 없는 이유. 되살릴 수 있으면 null */
export function restoreRefusal(d: EventData, taskId: string): string | null {
    const t = d.tasks.find((x) => x.id === taskId);
    if (!t) return '이미 영구 삭제된 업무라 복원할 수 없습니다.';
    if (t.status !== 'canceled') return '삭제된 업무가 아닙니다.';
    if (t.assignee && !byId(d, t.assignee))
        return '담당자가 명단에 없어 복원할 수 없습니다. 복원 뒤 다시 배정해주세요.';
    const holder = t.assignee ? sameTaskHolders(d, t).get(t.assignee) : undefined;
    if (holder)
        return `${nameOf(d, t.assignee)}님이 이미 같은 업무(${holder})를 맡고 있어 복원할 수 없습니다.`;
    return null;
}

/** 이 업무 말고 같은 업무(원 업무·복사본)를 이미 맡은 사람 → 그 업무 id */
export function sameTaskHolders(d: EventData, t: Task): Map<string, string> {
    const map = new Map<string, string>();
    taskFamily(d, t).forEach((u) => {
        if (u.id !== t.id && u.assignee && !map.has(u.assignee)) map.set(u.assignee, u.id);
    });
    return map;
}

/**
 * 같은 업무를 한꺼번에 다시 배정(고른 사람마다 한 건)할 수 없는 이유. 받을 수 있으면 null.
 * 지금 같은 업무를 맡은 사람은 팀원이 아니어도 그대로 둘 수 있고, 끝난 업무를 맡은 사람은 뺄 수 없다.
 */
export function familyAssignRefusal(
    d: EventData,
    taskId: string,
    targets: { groupId: string; people: string[] }[],
): string | null {
    const t = d.tasks.find((x) => x.id === taskId);
    if (!t) return '업무를 찾을 수 없습니다. 화면을 새로 고친 뒤 다시 시도해주세요.';
    const family = taskFamily(d, t);
    const all = targets.flatMap((x) => x.people);
    const twice = all.find((p, k) => all.indexOf(p) !== k);
    if (twice) return `${nameOf(d, twice)}님을 두 번 골랐습니다.`;
    for (const x of targets) {
        if (!d.groups.some((g) => g.id === x.groupId))
            return '없는 부서입니다. 화면을 새로 고친 뒤 다시 시도해주세요.';
        for (const p of x.people) {
            const m = byId(d, p);
            if (!m) return '명단에 없는 사람이 있습니다. 화면을 새로 고친 뒤 다시 시도해주세요.';
            if (m.groupId !== x.groupId)
                return `${m.name}님은 ${groupName(d, x.groupId)} 소속이 아닙니다.`;
            const keepsCurrent = family.some((u) => u.assignee === p && u.groupId === x.groupId);
            if (m.role !== 'WORKER' && !keepsCurrent)
                return `${m.name}님은 팀원이 아니어서 새로 배정할 수 없습니다. 팀원만 배정할 수 있습니다.`;
        }
    }
    const done = family.find((u) => u.status === 'done' && u.assignee && !all.includes(u.assignee));
    if (done) return `${nameOf(d, done.assignee)}님은 이 업무를 이미 끝내서 뺄 수 없습니다.`;
    const current = family.map((u) => u.assignee);
    if (
        current.every((p) => p !== null) &&
        current.length === all.length &&
        all.every((p) => family.some((u) => u.assignee === p && u.groupId === byId(d, p)?.groupId))
    )
        return '바뀐 내용이 없습니다. 지금과 같은 사람들입니다.';
    return null;
}

/**
 * 큐시트 배정(부서·사람 고르기)을 받을 수 없는 이유. 받을 수 있으면 null.
 * 화면은 배정 전에 이 문구로 안내하고, reducer도 같은 검사로 거부한다(총괄 권한 검사는 reducer가 따로 한다).
 * 지금 담당자는 팀원이 아니어도(예: 총괄이 맡은 전체 순회) 같은 부서에 그대로 둘 수 있다.
 */
export function assignRefusal(
    d: EventData,
    taskId: string,
    targets: { groupId: string; people: string[] }[],
    keep: boolean,
): string | null {
    const t = d.tasks.find((x) => x.id === taskId);
    if (!t) return '업무를 찾을 수 없습니다. 화면을 새로 고친 뒤 다시 시도해주세요.';
    if (t.status === 'done') return '이미 끝난 업무는 배정을 바꿀 수 없습니다.';
    if (t.status === 'canceled') return '취소된 업무는 배정할 수 없습니다.';
    if (!targets.length) return '배정할 부서를 골라주세요.';
    const all = targets.flatMap((x) => x.people);
    const twice = all.find((p, k) => all.indexOf(p) !== k);
    if (twice) return `${nameOf(d, twice)}님을 두 번 골랐습니다.`;
    for (const x of targets) {
        if (!d.groups.some((g) => g.id === x.groupId))
            return '없는 부서입니다. 화면을 새로 고친 뒤 다시 시도해주세요.';
        for (const p of x.people) {
            const m = byId(d, p);
            if (!m) return '명단에 없는 사람이 있습니다. 화면을 새로 고친 뒤 다시 시도해주세요.';
            if (m.groupId !== x.groupId)
                return `${m.name}님은 ${groupName(d, x.groupId)} 소속이 아닙니다.`;
            const keepsCurrent = p === t.assignee && x.groupId === t.groupId;
            if (m.role !== 'WORKER' && !keepsCurrent)
                return `${m.name}님은 팀원이 아니어서 새로 배정할 수 없습니다. 팀원만 배정할 수 있습니다.`;
        }
    }
    // 같은 업무(원 업무·복사본)를 이미 맡은 사람에게 또 배정하지 않는다. 이 업무의 지금 담당자는 그대로 둘 수 있다
    const holders = sameTaskHolders(d, t);
    const dup = all.find((p) => holders.has(p));
    if (dup)
        return `${nameOf(d, dup)}님은 이미 같은 업무(${holders.get(dup)})를 맡고 있습니다. 같은 업무를 한 사람에게 두 번 배정할 수 없습니다.`;
    const [first] = targets;
    if (
        !keep &&
        targets.length === 1 &&
        first.groupId === t.groupId &&
        first.people.length <= 1 &&
        (first.people[0] ?? null) === t.assignee
    )
        return '바뀐 내용이 없습니다. 지금과 같은 부서/담당자입니다.';
    return null;
}
/** 권한 라벨: 총괄 / 팀원(예비는 상태로 덧붙인다) */
export const roleLabel = (d: EventData, m: Member) =>
    m.role === 'CHIEF'
        ? '총괄'
        : `${groupName(d, m.groupId)} / ${m.isReserve ? '예비 팀원' : '팀원'}`;
/** 동그라미 안에 넣는 짧은 역할: 총괄 / 팀원 / 예비 */
export const shortRole = (m: Member) =>
    m.role === 'CHIEF' ? '총괄' : m.isReserve ? '예비' : '팀원';
export const membersOf = (d: EventData, groupId: string) =>
    d.members.filter((m) => m.groupId === groupId);
/** 예비 인원: 별도 풀이 아니라 팀원의 isReserve 상태로 거른다 */
export const reserves = (d: EventData) =>
    d.members.filter((m) => m.role === 'WORKER' && m.isReserve);

/**
 * 한 사람의 권한 시점(P02·BE10). 총괄은 전체, 팀원은 본인 업무·본인 보고·소속 안내만.
 * 같은 그룹이어도 다른 사람의 업무 상세·보고는 열지 않는다.
 */
/**
 * 총괄이 명시로 전달한 사람·부서만 수신자다. 보고자·업무와 같은 부서라는 이유로는 열지 않는다.
 * 수신자는 이슈 요약을 보며, 다른 사람의 보고 원문·분석 권한은 받지 않는다.
 */
export const receivesIssueForward = (f: IssueForward, member: Member | undefined) =>
    !!member &&
    (f.memberId === member.id ||
        f.mentions.includes(member.id) ||
        (!!f.groupId && !f.memberId && f.groupId === member.groupId));

/** 본문의 @이름 태그를 행사 참여자로 찾는다. 긴 이름부터 맞춰 "김하"가 "김하준"을 가로채지 않게 한다 */
export function mentionsIn(d: EventData, text: string) {
    const names = [...d.members].sort((a, b) => b.name.length - a.name.length);
    const found = new Set<string>();
    for (const m of text.matchAll(/@(\S+)/g)) {
        const hit = names.find((x) => m[1].startsWith(x.name));
        if (hit) found.add(hit.id);
    }
    return [...found];
}

export function viewer(d: EventData, me: string) {
    const m = byId(d, me);
    const isChief = m?.role === 'CHIEF';
    const group = d.groups.find((g) => g.id === m?.groupId);
    const touches = (i: Issue) =>
        i.tasks.some((id) => d.tasks.find((t) => t.id === id)?.assignee === me);
    return {
        me: m,
        isChief,
        /** W03 장비 현황 조회: 총괄 또는 장비 조회 허용 그룹의 팀원(V9-D13) */
        canViewEquipment: isChief || group?.equipmentView === true,
        /** 장비 등록·복구 대안·변경안은 총괄만 */
        canManageEquipment: isChief,
        /** 대응안·확정·종료 같은 이슈 관리와 업무 편집은 총괄만 */
        canManage: isChief,
        canViewIssue: (i: Issue) =>
            isChief ||
            i.reporter === me ||
            i.reports.some((r) => r.by === me) ||
            touches(i) ||
            !!i.forwards?.some((f) => receivesIssueForward(f, m)),
        canViewReport: (r: Issue['reports'][number]) => isChief || r.by === me,
        canViewIssueAnalysis: (i: Issue) => isChief || i.reporter === me,
        canReportIssue: (i: Issue) => isChief || i.reporter === me || touches(i),
        canViewTask: (t: Task) => isChief || t.assignee === me,
    };
}
export type Viewer = ReturnType<typeof viewer>;

/** 업무 묶음 단위로 본 업무. 최초 배정은 묶음 전체를 한 사람에게 연결한다(부분 배정 없음). */
export function bundlesOf(d: EventData, groupId?: string) {
    const map = new Map<string, Task[]>();
    d.tasks
        .filter((t) => t.status !== 'canceled' && (!groupId || t.groupId === groupId))
        .forEach((t) => map.set(t.bundle, [...(map.get(t.bundle) ?? []), t]));
    return [...map.entries()].map(([key, tasks]) => ({
        key,
        groupId: tasks[0].groupId,
        tasks: tasks.sort((a, b) => a.start.localeCompare(b.start)),
        assignee: tasks[0].assignee,
    }));
}
export type Bundle = ReturnType<typeof bundlesOf>[number];
const API_STATUS: Record<TaskStatus, ApiTask['status']> = {
    todo: 'WAITING',
    doing: 'IN_PROGRESS',
    done: 'DONE',
    canceled: 'CANCELED',
};
/** 서버 045 규칙(event-admin/bundles)으로 고를 수 있는 묶음 키. 취소 업무·그룹 혼합 묶음은 빠진다. */
function serverBundleKeys(d: EventData) {
    const tasks = d.tasks.map(
        (t) =>
            ({
                bundleKey: t.bundle,
                workGroupId: t.groupId,
                status: API_STATUS[t.status],
                assigneeId: t.assignee ?? null,
                firstAssignedAt: t.firstAssignedAt ?? null,
                startAt: new Date(`${t.date ?? EVENT_DATE}T${t.start}:00+09:00`).toISOString(),
                endAt: new Date(`${t.date ?? EVENT_DATE}T${t.end}:00+09:00`).toISOString(),
            }) as ApiTask,
    );
    return new Set(serverBundles(tasks).map((b) => b.bundleKey));
}
/**
 * 최초 경로를 쓸 수 있는 묶음: 모든 업무가 대기·미배정·첫 배정 기록 없음.
 * 실서버 모드(bundleRule 'server')는 서버 045와 같이 취소 업무가 섞이거나 그룹이 섞인 묶음도 뺀다.
 */
export const unassignedBundles = (d: EventData, groupId?: string) => {
    const allowed = d.bundleRule === 'server' ? serverBundleKeys(d) : null;
    return bundlesOf(d, groupId).filter(
        (b) =>
            (!allowed || allowed.has(b.key)) &&
            b.tasks.every((t) => t.status === 'todo' && !t.assignee && !t.firstAssignedAt),
    );
};
/** 같은 날짜에서 시간이 겹치는가. 날짜가 없는 업무는 행사 당일로 본다. */
const overlaps = (a: Task, b: Task) =>
    (a.date ?? EVENT_DATE) === (b.date ?? EVENT_DATE) && a.start < b.end && b.start < a.end;
/** 최초 배정 후보 검사: 같은 그룹·활성/가용·비예비·기존 업무와 시간이 겹치지 않음 */
export const canTakeBundle = (d: EventData, m: Member, b: Bundle) =>
    m.role === 'WORKER' &&
    !m.isReserve &&
    m.available &&
    m.groupId === b.groupId &&
    !tasksOf(d, m.id).some((t) => t.status !== 'canceled' && b.tasks.some((x) => overlaps(t, x)));

export const tasksOf = (d: EventData, id: string) =>
    d.tasks.filter((t) => t.assignee === id).sort((a, b) => a.start.localeCompare(b.start));
export const openTasksOf = (d: EventData, id: string) =>
    tasksOf(d, id).filter((t) => t.status !== 'done' && t.status !== 'canceled');
/**
 * 이슈가 속한 직무 그룹. 실서버는 이슈의 workGroupId, 데모는 연결된 업무의 그룹, 없으면 보고자의 그룹이다.
 * 구역 이름은 그룹이 아니다(#381 리뷰: 구역과 그룹을 비교해 그룹별 미해결 표시가 늘 비어 있었다).
 */
export function issueGroupOf(d: EventData, i: Issue): string | undefined {
    if (i.groupId) return i.groupId;
    for (const id of i.tasks) {
        const g = d.tasks.find((t) => t.id === id)?.groupId;
        if (g) return g;
    }
    return d.members.find((m) => m.id === i.reporter)?.groupId ?? undefined;
}

export const canStartTask = (t: Task) => t.status === 'todo' && toMin(t.start) - 5 <= NOW_MIN;
export const isLate = (t: Task) =>
    (t.status === 'todo' && toMin(t.start) < NOW_MIN) ||
    (t.status === 'doing' && toMin(t.end) < NOW_MIN);

/** 보고·대응(W04) 큐: WEB은 탭+분할 화면, APP은 내 업무의 진행 보고 탭 목록으로 같은 묶음을 쓴다. */
export function responseQueues(d: EventData, me: string) {
    const v = viewer(d, me);
    const visible = d.issues.filter(v.canViewIssue);
    const order = (a: Issue, b: Issue) => a.at.localeCompare(b.at);
    return {
        news: visible
            .filter((i) => i.status === 'RECEIVED' || i.status === 'RESPONDING')
            .sort(order),
        resolve: visible.filter((i) => i.status === 'RESOLUTION_REPORTED').sort(order),
        approve: v.isChief ? d.changes.filter((c) => c.status === 'READY') : [],
        closed: visible.filter((i) => i.status === 'CLOSED'),
    };
}
export type ResponseQueues = ReturnType<typeof responseQueues>;

export function currentPhase(ev: EventInfo) {
    const now = ev.programs.find((p) => toMin(p.start) <= NOW_MIN && NOW_MIN < toMin(p.end));
    const next = ev.programs
        .filter((p) => toMin(p.start) > NOW_MIN)
        .sort((a, b) => toMin(a.start) - toMin(b.start))[0];
    return { now, next };
}

export function affectedOf(c: Change) {
    const ids = new Set<string>();
    c.ops.forEach((o) => {
        if ((o.kind === 'update' || o.kind === 'cancel') && o.before.assignee)
            ids.add(o.before.assignee);
        if (o.kind === 'update' || o.kind === 'add') {
            if (o.after.assignee) ids.add(o.after.assignee);
            o.after.extraAssignees?.forEach((x) => x && ids.add(x));
        }
    });
    return [...ids];
}

export type DiffField = {
    label: '시간' | '담당' | '부서' | '구역' | '절차' | '상태';
    before: string;
    after: string;
};

/** 업무 전체 항목의 현재/변경 후 값. 값이 다르면 바뀐 항목이다. */
export type DetailField = { label: string; before: string; after: string };

/** 변경 operation 한 건. fields는 바뀐 필드만, detail은 업무 전체 항목을 현재/변경 후로 담는다. */
export type DiffRow = {
    key: string;
    target: string;
    code: string;
    kind: ChangeOp['kind'];
    fields: DiffField[];
    detail: DetailField[];
    before: string;
    after: string;
    whoBefore?: string;
    whoAfter?: string;
    /** 이 줄로 업무가 바뀌는 사람 id (APP 변경 확인 카드에서 "내 것만" 거르는 데 사용) */
    people: string[];
};

const fmtSlot = (s?: Slot) => (s ? `${s.start}~${s.end}` : '');
/** 담당 이름. 추가 담당이 있으면 함께 적는다 */
const whoOf = (d: EventData, s: Slot) =>
    s.extraAssignees?.length
        ? [s.assignee, ...s.extraAssignees].map((x) => nameOf(d, x)).join(', ')
        : nameOf(d, s.assignee);
/** 절차 한 단계의 들여쓰기 수준. 단계 글 앞의 공백 네 칸이 한 수준이다 */
export const stepLevel = (step: string) => Math.floor((step.length - step.trimStart().length) / 4);
/** 절차를 글머리표 '-'와 들여쓰기로 한 줄씩 적는다 */
const stepsText = (steps?: string[]) =>
    steps?.map((s) => `${' '.repeat(s.length - s.trimStart().length)}- ${s.trim()}`).join('\n') ||
    '없음';
const changedFields = (...fields: DiffField[]) => fields.filter((f) => f.before !== f.after);
const slotFields = (d: EventData, before: Slot, after: Slot): DiffField[] =>
    changedFields(
        { label: '시간', before: fmtSlot(before), after: fmtSlot(after) },
        { label: '담당', before: whoOf(d, before), after: whoOf(d, after) },
        { label: '구역', before: before.zone ?? '없음', after: after.zone ?? '없음' },
        {
            label: '부서',
            before: before.groupId ? groupName(d, before.groupId) : '',
            after: after.groupId ? groupName(d, after.groupId) : '',
        },
    );

export function diffRows(d: EventData, ev: EventInfo, c: Change): DiffRow[] {
    return c.ops.map((o, k): DiffRow => {
        if (o.kind === 'program') {
            const p = ev.programs.find((x) => x.id === o.program);
            return {
                key: `p${k}`,
                kind: o.kind,
                code: o.program,
                fields: changedFields({
                    label: '시간',
                    before: fmtSlot(o.before),
                    after: fmtSlot(o.after),
                }),
                target: `${p?.name ?? ''} 프로그램`,
                detail: [
                    { label: '프로그램', before: p?.name ?? '', after: p?.name ?? '' },
                    { label: '시간', before: fmtSlot(o.before), after: fmtSlot(o.after) },
                ],
                before: fmtSlot(o.before),
                after: fmtSlot(o.after),
                people: [],
            };
        }
        if (o.kind === 'add') {
            return {
                key: `a${k}`,
                kind: o.kind,
                code: o.tempId,
                fields: [
                    { label: '시간', before: '없음', after: fmtSlot(o.after) },
                    { label: '담당', before: '없음', after: whoOf(d, o.after) },
                    { label: '구역', before: '없음', after: o.after.zone ?? '없음' },
                    { label: '절차', before: '없음', after: stepsText(o.steps) },
                ],
                target: o.title,
                detail: [
                    { label: '업무', before: '없음', after: o.title },
                    { label: '시간', before: '없음', after: fmtSlot(o.after) },
                    { label: '담당', before: '없음', after: whoOf(d, o.after) },
                    { label: '구역', before: '없음', after: o.after.zone ?? '없음' },
                    { label: '상태', before: '없음', after: '새 업무' },
                    { label: '절차', before: '없음', after: stepsText(o.steps) },
                ],
                before: '새 업무',
                after: `${fmtSlot(o.after)} / ${o.after.zone ?? ''}`,
                whoAfter: whoOf(d, o.after),
                people: [o.after.assignee, ...(o.after.extraAssignees ?? [])].filter(
                    (x): x is string => !!x,
                ),
            };
        }
        const t = d.tasks.find((x) => x.id === o.taskId);
        const title = t?.title ?? d.purgedTasks?.[o.taskId] ?? o.taskId;
        const status = t ? TASK_STATUS_LABEL[t.status] : '업무 있음';
        const steps = stepsText(t?.steps);
        const zoneOf = (s: Slot) => s.zone ?? t?.zone ?? '없음';
        if (o.kind === 'cancel') {
            return {
                key: `c${k}`,
                fields: [
                    {
                        label: '상태',
                        before: t ? TASK_STATUS_LABEL[t.status] : '업무 있음',
                        after: '취소',
                    },
                ],
                kind: o.kind,
                code: o.taskId,
                target: title,
                detail: [
                    { label: '업무', before: title, after: title },
                    { label: '시간', before: fmtSlot(o.before), after: '없음' },
                    { label: '담당', before: nameOf(d, o.before.assignee), after: '없음' },
                    { label: '구역', before: zoneOf(o.before), after: '없음' },
                    { label: '상태', before: status, after: '취소' },
                    { label: '절차', before: steps, after: '없음' },
                ],
                before: fmtSlot(o.before),
                after: '취소',
                whoBefore: nameOf(d, o.before.assignee),
                people: o.before.assignee ? [o.before.assignee] : [],
            };
        }
        const whoBefore = nameOf(d, o.before.assignee);
        const whoAfter = whoOf(d, o.after);
        // 편집으로 바꾼 절차. 확정 뒤에는 업무가 이미 바뀌었으므로 편집 때 적어 둔 이전 값을 쓴다
        const stepsBefore = o.before.steps ? stepsText(o.before.steps) : steps;
        const stepsAfter = o.after.steps ? stepsText(o.after.steps) : steps;
        return {
            key: `u${k}`,
            fields: [
                ...slotFields(d, o.before, o.after),
                ...changedFields({ label: '절차', before: stepsBefore, after: stepsAfter }),
            ],
            kind: o.kind,
            code: o.taskId,
            target: title,
            detail: [
                { label: '업무', before: title, after: title },
                { label: '시간', before: fmtSlot(o.before), after: fmtSlot(o.after) },
                { label: '담당', before: whoBefore, after: whoAfter },
                { label: '구역', before: zoneOf(o.before), after: zoneOf(o.after) },
                { label: '상태', before: status, after: status },
                { label: '절차', before: stepsBefore, after: stepsAfter },
            ],
            before: fmtSlot(o.before),
            after: fmtSlot(o.after),
            whoBefore,
            whoAfter: whoBefore !== whoAfter ? whoAfter : undefined,
            people: [o.before.assignee, o.after.assignee, ...(o.after.extraAssignees ?? [])].filter(
                (x): x is string => !!x,
            ),
        };
    });
}

/** 채팅 채널: 전체 + 직무 그룹. 팀원은 현재 소속 그룹 채널만, 총괄은 모든 그룹 채널을 본다. */
export function visibleRooms(d: EventData, me: string) {
    const m = byId(d, me);
    return d.rooms.filter(
        (r) => r.kind === 'EVENT' || m?.role === 'CHIEF' || r.groupId === m?.groupId,
    );
}
/** 변경·투입 알림은 읽기와 별도로 본인이 "확인"해야 끝난다 */
/** 완료할 수 있는가: 필수 체크 항목을 모두 마쳤다(서버 규칙과 같다, #373). */
export const checklistReady = (t: Task) => t.checklist.every((c) => c.done || c.required === false);

export const needsAck = (n: Notice) => (n.kind === 'change' || n.kind === 'deploy') && !n.ack;

export function unreadNotices(d: EventData, me: string) {
    return d.notices.filter((n) => n.to === me && (!n.read || needsAck(n))).length;
}

// ── v9 운영 상황·장비·안전 ─────────────────────────────────────

export const SITUATION_LABEL: Record<SituationType, string> = {
    SCHEDULE_DELAY: '일정 지연',
    STAFF_GAP: '인력 공백',
    TASK_CONFLICT: '업무 충돌',
    EQUIPMENT_ANOMALY: '장비 이상',
    PERFORMANCE_ORDER_CHANGE: '공연 순서 변경',
    AUDIENCE_SAFETY: '관객 안전',
};
export const SITUATIONS = Object.keys(SITUATION_LABEL) as SituationType[];

export const EQUIP_TECH_LABEL: Record<EquipTech, string> = {
    OK: '정상',
    FAULT: '이상',
    CHECK: '점검 필요',
    UNKNOWN: '미확인',
};
export const RECOVERY_VERDICT_LABEL: Record<RecoveryVerdict, string> = {
    OK: '실행 가능',
    CONDITIONAL: '조건부',
    NO: '불가',
    UNKNOWN: '판단 불가',
};
export const CAMERA_INPUT_LABEL = { BROWSER: '웹 연결', COLLECTOR: '예비 수집 프로그램' } as const;
export const CAMERA_LINK_LABEL: Record<CameraLink, string> = {
    CONNECTED: '연결됨',
    CONNECTING: '연결 중',
    DISCONNECTED: '단절',
    NONE: '카메라 없음',
};
export const OBS_QUALITY_LABEL: Record<ObsQuality, string> = {
    GOOD: '양호',
    PARTIAL: '부분 관측',
    LOW: '저품질',
    NONE: '미관측',
};
export const OBS_FRESH_LABEL: Record<ObsFresh, string> = {
    PENDING: '분석 대기',
    FRESH: '최신',
    STALE: '낡음',
    FAILED: '분석 실패',
};

/** 장비가 지금 시각에 쓰이는 예약(없으면 미사용) */
export const reservationNow = (d: EventData, assetId: string) =>
    d.reservations.find(
        (r) => r.assetId === assetId && toMin(r.start) <= NOW_MIN && NOW_MIN < toMin(r.end),
    );

/** 같은 장비의 예약끼리 시간이 겹치면 충돌이다 */
export function reservationConflicts(d: EventData, assetId: string) {
    const rs = d.reservations.filter((r) => r.assetId === assetId);
    return rs.filter((a) =>
        rs.some((b) => b !== a && toMin(a.start) < toMin(b.end) && toMin(b.start) < toMin(a.end)),
    );
}

/**
 * 구역 관측 판정. 면적·기준이 확인된 경우에만 명/㎡·기준 대비 상태를 계산하고,
 * 미관측·낡음·실패는 0명/정상으로 바꾸지 않고 확인 필요로 둔다(P12).
 */
export function zoneReading(d: EventData, zoneId: string) {
    const zone = d.safetyZones.find((z) => z.id === zoneId);
    const obs = d.observations.find((o) => o.zoneId === zoneId);
    // 단절·비활성·보정 무효 카메라의 마지막 값은 현재 값으로 쓰지 않는다(0명/정상으로 보이지 않게)
    const cam = obs?.cameraId ? d.cameras.find((c) => c.id === obs.cameraId) : undefined;
    const usable =
        !!obs &&
        obs.count != null &&
        obs.fresh === 'FRESH' &&
        obs.quality !== 'NONE' &&
        cameraUsable(cam);
    const density = usable && zone?.area ? obs!.count! / zone.area : null;
    // 실서버는 서버 판정(risk)을 쓴다. 지금 값으로 쓸 수 없는 관측이면 판정과 관계없이 확인 필요다(#372)
    const state: 'OVER' | 'NEAR' | 'OK' | 'CHECK' = obs?.risk
        ? usable
            ? obs.risk
            : 'CHECK'
        : density == null || zone?.limit == null || obs?.quality !== 'GOOD'
          ? 'CHECK'
          : density > zone.limit
            ? 'OVER'
            : density > zone.limit * 0.8
              ? 'NEAR'
              : 'OK';
    return { zone, obs, usable, density, state };
}
export const ZONE_STATE_LABEL = {
    OVER: '기준 초과',
    NEAR: '기준 근접',
    OK: '기준 이내',
    CHECK: '확인 필요',
} as const;

/** 지금 그 사람이 수행 중인 안전 업무의 구역 */
function currentSafetyZone(d: EventData, memberId: string) {
    return tasksOf(d, memberId).find(
        (t) =>
            t.safety &&
            t.status !== 'done' &&
            t.status !== 'canceled' &&
            toMin(t.start) <= NOW_MIN &&
            NOW_MIN < toMin(t.end),
    )?.safety?.zoneId;
}

/**
 * 안전 지원 후보: 안전 직무 그룹의 실제 가용 인원 중 원 구역 최소 유지·필수 업무·관측 최신성을 검사한다.
 * 한적한 구역이라도 필수 업무가 있으면 제외하고, 원 구역 관측이 낡았으면 조건부로 둔다(S04).
 */
export function supportCandidates(d: EventData, targetZoneId: string) {
    const target = d.safetyZones.find((z) => z.id === targetZoneId);
    const safetyGroups = new Set(d.tasks.filter((t) => t.safety).map((t) => t.groupId));
    const center = (z?: SafetyZone) =>
        z ? [z.rect[0] + z.rect[2] / 2, z.rect[1] + z.rect[3] / 2] : [0, 0];
    return d.members
        .filter((m) => m.role === 'WORKER' && m.groupId && safetyGroups.has(m.groupId))
        .map((m) => {
            const fromId = currentSafetyZone(d, m.id);
            const from = d.safetyZones.find((z) => z.id === fromId);
            const [x1, y1] = center(from ?? d.safetyZones.find((z) => z.name === '입구'));
            const [x2, y2] = center(target);
            const eta = Math.max(1, Math.round(Math.hypot(x2 - x1, y2 - y1) / 20));
            const staffIn = from
                ? d.members.filter((x) => currentSafetyZone(d, x.id) === from.id).length
                : 0;
            const entry = d.tasks.find(
                (t) =>
                    t.assignee === m.id &&
                    t.status === 'doing' &&
                    !t.safety &&
                    toMin(t.start) <= NOW_MIN &&
                    NOW_MIN < toMin(t.end),
            );
            const base = { member: m, from: from?.name ?? entry?.zone ?? null, eta };
            if (fromId === targetZoneId) return null;
            if (!m.available)
                return {
                    ...base,
                    verdict: 'NO' as const,
                    reason: `가용 아님 / ${m.wait ?? m.avail}`,
                };
            if (entry) {
                const zone = d.safetyZones.find((z) => z.name === entry.zone);
                return {
                    ...base,
                    verdict: 'NO' as const,
                    reason: zone?.essential ?? `${entry.title} 수행 중`,
                };
            }
            if (from && staffIn - 1 < from.minStaff)
                return {
                    ...base,
                    verdict: 'NO' as const,
                    reason: `${from.name} 최소 ${from.minStaff}명 유지 조건을 깨뜨립니다`,
                };
            if (from && zoneReading(d, from.id).state === 'CHECK')
                return {
                    ...base,
                    verdict: 'CONDITIONAL' as const,
                    reason: `${from.name} 관측이 부분적이라 원 구역 상황 확인이 필요합니다`,
                };
            if (!from)
                return {
                    ...base,
                    verdict: 'CONDITIONAL' as const,
                    reason: '현재 위치가 확인되지 않았습니다',
                };
            return {
                ...base,
                verdict: 'OK' as const,
                reason: `${from.name}에 ${staffIn - 1}명이 남아 최소 유지 조건을 지킵니다`,
            };
        })
        .filter((c): c is NonNullable<typeof c> => !!c)
        .sort(
            (a, b) =>
                ['OK', 'CONDITIONAL', 'NO'].indexOf(a.verdict) -
                ['OK', 'CONDITIONAL', 'NO'].indexOf(b.verdict),
        );
}
export type SupportCandidate = ReturnType<typeof supportCandidates>[number];

/** "HH:MM" 또는 "HH:MM:SS" → 자정부터 초 */
export const toSec = (t: string) => {
    const [h, m, s = 0] = t.split(':').map(Number);
    return h * 3600 + m * 60 + s;
};
/**
 * 최신 승인 계획 대비 실제 차이(초). 확인된 실제 시각이 없으면 null이다(0초 지연으로 채우지 않는다).
 * 분 단위로 뭉개지 않도록 초를 그대로 돌려준다.
 */
export function programDelaySec(p: Program, edge: 'start' | 'end'): number | null {
    const actual = edge === 'start' ? p.actualStart : p.actualEnd;
    return actual ? toSec(actual) - toSec(p[edge]) : null;
}
/** +1분 20초 / -30초 / 정시 */
export function formatDelay(sec: number) {
    if (sec === 0) return '정시';
    const a = Math.abs(sec);
    const m = Math.floor(a / 60);
    const r = a % 60;
    const body = [m ? `${m}분` : '', r ? `${r}초` : ''].filter(Boolean).join(' ');
    return `${sec > 0 ? '+' : '-'}${body}`;
}

export type CueSection = {
    key: string;
    kind: 'program' | 'prep' | 'transition' | 'teardown';
    label: string;
    program?: Program;
    tasks: Task[];
};
/**
 * 큐시트(W01): 곡/프로그램 순서대로 묶고, 프로그램에 연결되지 않은 업무는 시간으로 준비·전환·철수 칸에 둔다.
 * 시간상 인접하다고 업무를 프로그램에 자동 종속시키지 않는다(연결된 program만 그 곡 아래에 둔다).
 */
export function cueSections(ev: EventInfo, tasks: Task[]): CueSection[] {
    const ps = ev.programs.slice().sort((a, b) => toMin(a.start) - toMin(b.start));
    const linked = new Set(ps.map((p) => p.id));
    const free = tasks.filter((t) => !t.program || !linked.has(t.program));
    const out: CueSection[] = [];
    const push = (s: CueSection) => s.tasks.length && out.push(s);
    const first = ps[0];
    const last = ps[ps.length - 1];
    push({
        key: 'prep',
        kind: 'prep',
        label: '공연 전 준비',
        tasks: free.filter((t) => !first || toMin(t.start) < toMin(first.start)),
    });
    ps.forEach((p, i) => {
        push({
            key: p.id,
            kind: 'program',
            label: p.name,
            program: p,
            tasks: tasks.filter((t) => t.program === p.id),
        });
        const next = ps[i + 1];
        if (next)
            push({
                key: `${p.id}-${next.id}`,
                kind: 'transition',
                label: `전환 / ${p.name} → ${next.name}`,
                tasks: free.filter(
                    (t) => toMin(t.start) >= toMin(p.start) && toMin(t.start) < toMin(next.start),
                ),
            });
    });
    if (last)
        push({
            key: 'teardown',
            kind: 'teardown',
            label: '공연 후 철수',
            tasks: free.filter((t) => toMin(t.start) >= toMin(last.start)),
        });
    return out;
}

/** 짧은 지문(FNV-1a 32bit). 같은 내용이면 같은 값이며 보안 해시가 아니다 */
export function hashOf(value: unknown): string {
    let h = 0x811c9dc5;
    for (const ch of JSON.stringify(value) ?? '') {
        h ^= ch.codePointAt(0)!;
        h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h.toString(16).padStart(8, '0');
}
export const previewHashOf = (
    c: Pick<Change, 'ops' | 'reservations' | 'releases' | 'baseRevision'>,
) => hashOf([c.baseRevision, c.ops, c.reservations ?? [], c.releases ?? []]);

/** 변경 확인 대상 지문: 본인 업무의 시간·구역·담당·상태·이름. 대상이 바뀌면 값이 달라진다 */
export function taskTargetHash(d: EventData, ids: string[]) {
    return hashOf(
        ids.map((id) => {
            const t = d.tasks.find((x) => x.id === id);
            return t ? [t.id, t.title, t.start, t.end, t.zone, t.assignee, t.status] : [id, null];
        }),
    );
}

/**
 * 승인 직전 검사(X01): 만든 뒤 바뀐 전제를 이유와 함께 돌려준다. 하나라도 있으면 승인하지 않는다.
 * 조용히 최신 diff로 바꿔 승인하지 않고, 사용자가 다시 확인(rebase)한 뒤에만 진행한다.
 */
export function changeStaleReasons(d: EventData, ev: EventInfo, c: Change): string[] {
    if (c.status !== 'READY') return [];
    const out: string[] = [];
    if (c.baseRevision !== ev.revision)
        out.push(
            `만든 뒤 계획/인원/배정이 바뀌었습니다(기준 ${c.baseRevision} → 현재 ${ev.revision}).`,
        );
    if (c.previewHash && c.previewHash !== previewHashOf(c))
        out.push('변경 내용이 검토한 미리 보기와 다릅니다.');
    c.ops.forEach((o) => {
        if (o.kind !== 'cancel' && !isValidTimeRange(o.after.start, o.after.end))
            out.push('변경 후 시작/종료 시간을 올바르게 입력해주세요.');
    });
    c.basis?.obs?.forEach((b) => {
        const zone = d.safetyZones.find((z) => z.id === b.zoneId)?.name ?? b.zoneId;
        const o = d.observations.find((x) => x.zoneId === b.zoneId);
        const cam = o?.cameraId ? d.cameras.find((c) => c.id === o.cameraId) : undefined;
        if (!d.safetyZones.some((z) => z.id === b.zoneId) || !cameraUsable(cam))
            out.push(`${zone} 카메라의 연결/활성/보정을 다시 확인해주세요.`);
        if (!o || o.fresh !== 'FRESH')
            out.push(`${zone} 관측이 ${o ? OBS_FRESH_LABEL[o.fresh] : '없음'} 상태입니다.`);
        else if (o.at !== b.at)
            out.push(`${zone} 관측이 갱신되었습니다(기준 ${b.at ?? '-'} → 현재 ${o.at ?? '-'}).`);
    });
    const people = new Set<string>();
    c.ops.forEach((o) => {
        if (o.kind !== 'add' && o.kind !== 'update') return;
        if (o.after.assignee) people.add(o.after.assignee);
        o.after.extraAssignees?.forEach((x) => people.add(x));
    });
    people.forEach((id) => {
        const m = d.members.find((x) => x.id === id);
        if (!m || !m.available) out.push(`${m?.name ?? id} 님이 지금 가용이 아닙니다.`);
    });
    // 해제할 예약이 그새 없어졌거나 바뀌었으면 다시 확인한다
    c.releases?.forEach((id) => {
        if (!d.reservations.some((r) => r.id === id))
            out.push(`해제할 예약(${id})이 이미 없어졌습니다.`);
    });
    c.reservations?.forEach((r) => {
        const clash = d.reservations.find(
            (x) =>
                !c.releases?.includes(x.id) &&
                x.assetId === r.assetId &&
                toMin(x.start) < toMin(r.end) &&
                toMin(r.start) < toMin(x.end),
        );
        if (clash) {
            const name = d.equipment.find((a) => a.id === r.assetId)?.name ?? r.assetId;
            out.push(`${name} 예약이 ${clash.start}–${clash.end}와 겹칩니다.`);
        }
    });
    return out;
}

export type CameraState = { label: string; kind: 'ok' | 'warn' | 'bad' | '' };
/**
 * 카메라 상태(W05/S01): 연결·활성·보정·분석을 각각 따로 판단한다.
 * 카메라 없음/연결 중/단절/비활성/보정 무효/보정 필요/분석 대기/분석 실패/낡음을 서로 다른 문구로 낸다.
 */
export function cameraStates(
    cam: SafetyCamera | undefined,
    obs?: SafetyObservation,
): CameraState[] {
    if (!cam || (cam.link === 'NONE' && !cam.unlinked)) return [{ label: '카메라 없음', kind: '' }];
    const out: CameraState[] = [];
    if (cam.active === false) out.push({ label: '비활성', kind: '' });
    // 실서버 웹 연결 칸(#397): 등록 안됨·첫 사진 없음·입력 종류·연결한 사람
    if (cam.unlinked) out.push({ label: '등록 안됨', kind: '' });
    else if (cam.noFirstFrame) out.push({ label: '첫 사진 없음', kind: 'bad' });
    else
        out.push({
            label: CAMERA_LINK_LABEL[cam.link],
            kind: cam.link === 'CONNECTED' ? 'ok' : cam.link === 'CONNECTING' ? 'warn' : 'bad',
        });
    if (cam.input) out.push({ label: CAMERA_INPUT_LABEL[cam.input], kind: 'ok' });
    if (cam.linkedBy) out.push({ label: `연결 ${cam.linkedBy}`, kind: 'ok' });
    if (cam.calibrationInvalid) out.push({ label: '보정 무효', kind: 'bad' });
    else if (!cam.calibrated) out.push({ label: '보정 필요', kind: 'warn' });
    if (obs)
        out.push({
            // '분석 대기'·'분석 실패'는 이미 '분석'으로 시작한다
            label: OBS_FRESH_LABEL[obs.fresh].startsWith('분석')
                ? OBS_FRESH_LABEL[obs.fresh]
                : `분석 ${OBS_FRESH_LABEL[obs.fresh]}`,
            kind: obs.fresh === 'FRESH' ? 'ok' : obs.fresh === 'PENDING' ? 'warn' : 'bad',
        });
    return out;
}
/** 지금 집계를 현재 값으로 쓸 수 있는 카메라인가(연결·활성·유효 보정) */
export const cameraUsable = (cam: SafetyCamera | undefined) =>
    !!cam &&
    cam.link === 'CONNECTED' &&
    cam.active !== false &&
    cam.calibrated &&
    !cam.calibrationInvalid;

/**
 * 카메라 현재 화면을 볼 수 있는 공연 상태인가. 참여 모집(RECRUITING)에도 담당자가 연결해 사진을 보내고
 * 총괄이 그 화면으로 구역을 보정하므로 공연 시작 전부터 연다(#401 운영 확인, 서버 수신·조회도 RECRUITING·LIVE).
 * 작성 중·종료 뒤에는 닫는다(C09).
 */
export const cameraViewable = (state: EventState) => state === 'RECRUITING' || state === 'LIVE';
/** 현재 미리보기를 열 수 있는 카메라인가(연결·활성). 보정이 무효여도 화면은 볼 수 있다 */
export const cameraStreamable = (c: SafetyCamera) => c.link === 'CONNECTED' && c.active !== false;
