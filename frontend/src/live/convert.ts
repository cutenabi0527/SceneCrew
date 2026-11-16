import type {
    EventData,
    EventInfo,
    Member,
    Program,
    Task,
    TaskStatus,
    WorkGroup,
} from '../domain/types';
import type { Plan, Task as ApiTask } from '../features/plan-workflow/contract';
import type { Member as ApiMember } from '../features/members/contract';

// 실서버 응답을 디자인 화면의 화면 모델(EventData·EventInfo)로 바꾸는 순수 함수(#362).
// 화면·선택자는 그대로 두고 이 변환만 실데이터로 갈아 끼운다. 아직 API가 연결되지 않은 영역
// (보고·장비·안전·채팅 등)은 빈 목록으로 두며 데모 값으로 채우지 않는다.

const ZONE = 'Asia/Seoul';
const dateFmt = new Intl.DateTimeFormat('en-CA', {
    timeZone: ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
});
const timeFmt = new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
});

const clockFmt = new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
});
/** UTC 시각 → 한국 시각 HH:mm:ss(장비 자동 관측 마지막 수신처럼 초가 필요한 곳) */
export function kstClock(utc: string): string {
    return clockFmt.format(new Date(utc));
}

/** UTC 시각 → 한국 날짜 YYYY-MM-DD */
export function kstDate(utc: string): string {
    return dateFmt.format(new Date(utc));
}
/** UTC 시각 → 한국 시각 HH:mm */
export function kstTime(utc: string): string {
    return timeFmt.format(new Date(utc));
}
/** YYYY-MM-DD → 'M월 D일'(데모 화면의 행사 날짜 표기) */
export function koreanDay(date: string): string {
    const [, m, d] = date.split('-').map(Number);
    return `${m}월 ${d}일`;
}

const STATUS: Record<ApiTask['status'], TaskStatus> = {
    WAITING: 'todo',
    IN_PROGRESS: 'doing',
    DONE: 'done',
    CANCELED: 'canceled',
};

/** GET /events/{e}의 행사 본문. 계약(Event)에는 날짜·장소·운영 시각이 있으나 화면 계약 타입에 없는 값도 읽는다. */
export interface LiveEventDetail {
    id: string;
    name: string;
    state: EventInfo['state'];
    revision: string;
    instructions?: string;
    date?: string | null;
    locationName?: string | null;
    totalCapacity?: string | null;
    prepStartAt?: string | null;
    openAt?: string | null;
    closeAt?: string | null;
    teardownEndAt?: string | null;
    expectedVisitors?: string | null;
    peakVisitors?: string | null;
}

const hm = (utc: string | null | undefined) => (utc ? kstTime(utc) : undefined);

/** 행사 날짜: 계약의 date(현지 날짜)를 우선하고, 없으면 공개 시각에서 한국 날짜를 구한다. */
export function eventDay(event: LiveEventDetail): string | null {
    if (event.date) return event.date;
    if (event.openAt) return kstDate(event.openAt);
    return null;
}

export function toEventInfo(event: LiveEventDetail, plan: Plan | null): EventInfo {
    const day = eventDay(event);
    const open = hm(event.openAt);
    const close = hm(event.closeAt);
    const programs: Program[] = (plan?.programs ?? [])
        .slice()
        .sort((a, b) => Date.parse(a.startAt) - Date.parse(b.startAt))
        .map((p) => ({ id: p.id, name: p.name, start: kstTime(p.startAt), end: kstTime(p.endAt) }));
    const capacity = event.totalCapacity ? Number(event.totalCapacity) : undefined;
    const visitors = [event.expectedVisitors, event.peakVisitors].filter(Boolean).join(' / ');
    return {
        id: event.id,
        name: event.name,
        date: day ? koreanDay(day) : '날짜 미정',
        time: open && close ? `${open}–${close}` : '시간 미정',
        place: event.locationName || '장소 미정',
        state: event.state,
        revision: Number(event.revision),
        zones: (plan?.zones ?? []).map((z) => z.name),
        programs,
        prep: hm(event.prepStartAt),
        open,
        close,
        teardown: hm(event.teardownEndAt),
        capacity: Number.isFinite(capacity) ? capacity : undefined,
        visitors: visitors || undefined,
        notice: event.instructions || undefined,
    };
}

export function toGroups(plan: Plan | null): WorkGroup[] {
    return (plan?.workGroups ?? []).map((g) => ({
        id: g.id,
        name: g.name,
        guide: g.instructions ?? '',
    }));
}

/** 활성 참여자만 화면에 둔다. 최초 배정 상태(assign)는 서버가 아직 주지 않아 비워 둔다. */
export function toMembers(members: readonly ApiMember[]): Member[] {
    return members
        .filter((m) => m.active)
        .map((m) => ({
            id: m.memberId,
            name: m.displayName,
            role: m.eventRole,
            groupId: m.workGroupId,
            isReserve: m.isReserve,
            available: m.available,
            avail: '',
        }));
}

/**
 * 업무 변환. 행사 당일 업무는 date를 비워 화면의 '행사 당일' 판단(EVENT_DATE 비교)을 그대로 통과시킨다.
 * 변경 확인 대기 여부는 업무 응답에 없어 ack=true로 둔다(변경 확인 화면이 따로 보여 준다).
 */
export function toTasks(tasks: readonly ApiTask[], plan: Plan | null, day: string | null): Task[] {
    const zoneName = new Map((plan?.zones ?? []).map((z) => [z.id, z.name]));
    return tasks.map((t) => {
        const date = kstDate(t.startAt);
        return {
            id: t.id,
            title: t.title,
            groupId: t.workGroupId,
            zone: zoneName.get(t.zoneId) ?? '구역 미확인',
            date: day && date === day ? undefined : date,
            start: kstTime(t.startAt),
            end: kstTime(t.endAt),
            assignee: t.assigneeId,
            bundle: t.bundleKey,
            firstAssignedAt: t.firstAssignedAt ?? undefined,
            program: t.programId ?? undefined,
            status: STATUS[t.status],
            ack: true,
            steps: t.steps,
            checklist: t.checklist.map((c) => ({
                text: c.text,
                done: c.checked,
                required: c.required,
            })),
        };
    });
}

export function emptyData(): EventData {
    return {
        groups: [],
        members: [],
        invitees: [],
        tasks: [],
        issues: [],
        changes: [],
        notices: [],
        log: [],
        rooms: [],
        messages: [],
        equipment: [],
        reservations: [],
        recovery: [],
        safetyZones: [],
        cameras: [],
        observations: [],
    };
}

export interface LiveSnapshot {
    event: LiveEventDetail;
    plan: Plan | null;
    tasks: readonly ApiTask[];
    members: readonly ApiMember[];
}

/**
 * 화면 기준 날짜. '지금 시각'(한국 현재 시각)과 따로 둔다. 화면은 기준 날짜의 업무만 '행사 당일'로 보므로
 * 행사 날짜를 알면 늘 행사 날짜를 쓴다(행사 전날에 열어도 당일 업무가 시간표에서 빠지지 않게).
 * 행사 날짜를 모르면 한국 오늘 날짜를 쓴다.
 */
export function screenDate(event: LiveEventDetail, nowUtc: string): string {
    return eventDay(event) ?? kstDate(nowUtc);
}

/**
 * 불러온 실데이터 한 벌을 화면 모델로 바꾼다. today는 화면 기준 날짜(screenDate)이며
 * 그날 업무는 날짜를 비워 화면이 당일 업무로 본다. 없으면 행사 날짜를 쓴다.
 */
export function toScreen(
    snapshot: LiveSnapshot,
    today: string | null = eventDay(snapshot.event),
): { info: EventInfo; data: EventData } {
    const info = toEventInfo(snapshot.event, snapshot.plan);
    const data = emptyData();
    // 최초 배정 묶음은 서버 045와 같은 규칙으로 판정한다
    data.bundleRule = 'server';
    data.groups = toGroups(snapshot.plan);
    data.members = toMembers(snapshot.members);
    data.tasks = toTasks(snapshot.tasks, snapshot.plan, today);
    return { info, data };
}
