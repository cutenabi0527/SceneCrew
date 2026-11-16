import { EVENT_DATE, NOW, initialState, seedData } from './seed';
import {
    checklistReady,
    affectedOf,
    assignRefusal,
    familyAssignRefusal,
    restoreRefusal,
    taskFamily,
    taskRoot,
    byId,
    bundlesOf,
    canTakeBundle,
    changeStaleReasons,
    fmtMin,
    groupName,
    isValidTimeRange,
    mentionsIn,
    nameOf,
    previewHashOf,
    reserves,
    supportCandidates,
    taskTargetHash,
    toMin,
    unassignedBundles,
} from './selectors';
import type {
    Message,
    Change,
    ChangeOp,
    DemoState,
    EventData,
    EventInfo,
    Task,
    FloorPlan,
    Issue,
    EquipStep,
    Notice,
    PlanEdit,
    ReportVia,
    SafetyStep,
    SituationType,
} from './types';

// 서버가 생기기 전까지 화면이 공유하는 단일 상태 변경 지점. 모든 id/시각은 액션이 들고 와서 reducer는 순수하게 유지한다.

/** 본인이 고치는 사용자 정보. 이름은 총괄만 바로 바뀌고 팀원은 총괄 확인을 거친다. */
export type ProfileInput = { name: string; avail: string; skills: string; wait: string };

export type ReportInput = {
    id: string;
    taskId: string | null;
    issueId: string | null;
    zone: string;
    text: string;
    via: ReportVia;
    /** 실서버 음성 보고의 원음 id(060). 데모는 쓰지 않는다 */
    audioId?: string | null;
    kind: 'INITIAL' | 'FOLLOWUP' | 'RESOLUTION';
};

export type Action =
    | {
          /** 실서버 응답을 변환한 화면 상태로 통째로 바꾼다(#362). me는 현재 참여자 id다. */
          type: 'liveLoad';
          me: string;
          eventId: string;
          events: EventInfo[];
          data: EventData;
      }
    /** 실서버 채팅 연결이 받은 메시지(#373). 화면 안에서만 쓰는 값이다 */
    | { type: 'liveChat'; messages: Message[] }
    | { type: 'login'; me: string }
    | { type: 'logout' }
    | { type: 'enterEvent'; eventId: string }
    | { type: 'leaveEvent' }
    | { type: 'persona'; me: string }
    | { type: 'join'; groupId: string; reserve: boolean; available: boolean }
    | { type: 'assignRun'; memberId: string }
    | { type: 'assignResult'; memberId: string }
    | { type: 'assignRetry'; memberId: string }
    | { type: 'assignManual'; bundle: string; memberId: string }
    | { type: 'setAvailable'; available: boolean }
    | { type: 'updateProfile'; profile: ProfileInput }
    | { type: 'replacePlan'; plan: PlanEdit }
    | { type: 'nameDecision'; memberId: string; approve: boolean }
    | { type: 'taskStart'; id: string }
    | { type: 'taskDone'; id: string }
    | { type: 'toggleCheck'; id: string; index: number }
    | { type: 'report'; input: ReportInput }
    | { type: 'aiChange'; issueId: string; id: string; reserveId?: string }
    | { type: 'applyChange'; id: string }
    | { type: 'rebaseChange'; id: string }
    | { type: 'discardChange'; id: string }
    | { type: 'closeIssue'; id: string }
    | { type: 'endEvent' }
    | { type: 'ackNotice'; id: string }
    | { type: 'readNotice'; id: string }
    | { type: 'readAllNotices' }
    | { type: 'send'; room: string; text: string; id: string }
    | {
          type: 'setSituation';
          issueId: string;
          primary: SituationType | null;
          related: SituationType[];
          /** 정정 사유(V9-D48). 실서버는 필수, 데모는 기록하지 않는다. */
          reason?: string;
      }
    | { type: 'recoveryChange'; optionId: string; id: string }
    /** 복구 대안 계산(실서버 전용, #371). 데모는 미리 계산된 대안을 쓴다. */
    | { type: 'recoveryRun'; issueId: string; assetIds: string[] }
    /** 실서버: 가장 최근 보고를 다시 분석한다(062, #358). 데모는 바뀌는 것이 없다. */
    | { type: 'reanalyze'; issueId: string }
    | { type: 'safetyChange'; zoneId: string; memberId: string; id: string }
    | { type: 'equipmentRecord'; taskId: string; step: EquipStep }
    | { type: 'safetyRecord'; taskId: string; step: SafetyStep }
    | { type: 'addEquipment'; id: string; name: string; kind: string; place: string }
    | {
          type: 'importEquipment';
          items: { id: string; name: string; kind: string; place: string }[];
          source: string;
      }
    | { type: 'cameraLink'; cameraId: string; link: 'CONNECTING' | 'CONNECTED' | 'DISCONNECTED' }
    | { type: 'cameraCalibrate'; cameraId: string }
    | { type: 'cameraInvalidate'; cameraId: string; reason: string }
    | { type: 'cameraActive'; cameraId: string; active: boolean }
    | {
          /** 업무 조정 목록 보기: 부서·담당 재배정. 변경안을 만들어 바로 총괄 확정 경로로 적용한다 */
          type: 'reassignTask';
          id: string;
          taskId: string;
          groupId: string;
          assignee: string | null;
      }
    | {
          /**
           * 공연 계획 큐시트: 업무를 여러 부서·여러 사람에게 배정한다(데모 전용).
           * targets[k]는 한 부서와 그 부서에서 고른 사람들(비면 그 부서의 미배정).
           * keep=false면 첫 대상이 기존 업무를 옮기고(부서·담당 변경) 나머지 대상은 같은 업무를 새로 받는다.
           * keep=true면 기존 업무는 그대로 두고 모든 대상이 같은 업무를 새로 받는다(두 부서에 모두 배정).
           * 같은 부서 안 두 번째 사람부터는 같은 내용의 업무를 한 건씩 받는다(extraAssignees).
           */
          type: 'assignTask';
          id: string;
          taskId: string;
          targets: { groupId: string; people: string[] }[];
          keep: boolean;
      }
    | {
          /**
           * 같은 업무(여러 명에게 복사 배정된 업무)를 한꺼번에 다시 배정한다. 고른 사람마다 한 건씩 맡고,
           * 빠진 사람의 업무는 새로 고른 사람에게 넘기거나 취소한다. 변경안은 하나만 만든다.
           */
          type: 'assignFamily';
          id: string;
          taskId: string;
          targets: { groupId: string; people: string[] }[];
      }
    | {
          /**
           * 큐시트에서 업무 삭제(총괄). 지우지 않고 취소 변경안 하나로 기록해 담당자에게 업무 취소 알림이 간다.
           * 같은 업무 여러 건을 함께 삭제해도 변경안은 하나다.
           */
          type: 'deleteTasks';
          id: string;
          taskIds: string[];
      }
    /** 삭제(취소)된 업무 복원(총괄). 담당자에게 다시 확인을 받는다 */
    | { type: 'restoreTask'; taskId: string }
    /** 삭제(취소)된 업무 영구 삭제(총괄). 업무를 지우고 이름만 변경 기록용으로 남긴다 */
    | { type: 'purgeTask'; taskId: string }
    | {
          /** 총괄이 이슈를 다른 부서(전체) 또는 그 부서의 특정 사람에게 전달하고, 본문의 @이름도 알린다 */
          type: 'forwardIssue';
          issueId: string;
          groupId: string | null;
          memberId: string | null;
          note: string;
      }
    | {
          /** 확정 전 변경안 편집: 바꿀 값(시간·담당·절차)과 확인 필요 사항. 편집한 내용이 새 미리 보기가 된다 */
          type: 'editChange';
          id: string;
          ops: ChangeOp[];
          checks?: string;
      }
    | {
          /**
           * 평면도·구역 편집(W05·총괄): 이미지(바꿀 때만)와 구역 상자 목록을 한 번에 저장한다.
           * id가 있는 상자는 그 구역의 이름·영역만 바꾸고(면적·기준·최소 유지는 유지), 없는 상자는 새 구역이 된다.
           * 목록에 없는 기존 구역은 지운다.
           */
          type: 'setFloorPlan';
          plan?: FloorPlan | null;
          zones: { id: string | null; name: string; rect: [number, number, number, number] }[];
      }
    | {
          type: 'memberManage';
          memberId: string;
          patch: { available?: boolean; isReserve?: boolean; groupId?: string };
      }
    | { type: 'reset' };

/**
 * 종료(ENDED) 뒤에는 읽기만 한다: 새 보고·관측·수행·배정·변경 적용·채팅을 막는다(IA04).
 * 본인 계정 행위(프로필)와 알림 읽음·확인 기록은 기존 권한을 따른다.
 */
const BLOCKED_WHEN_ENDED = new Set<Action['type']>([
    'join',
    'assignRun',
    'assignResult',
    'assignRetry',
    'assignManual',
    'setAvailable',
    'taskStart',
    'taskDone',
    'toggleCheck',
    'report',
    'aiChange',
    'applyChange',
    'rebaseChange',
    'closeIssue',
    'send',
    'setSituation',
    'recoveryChange',
    'recoveryRun',
    'reanalyze',
    'safetyChange',
    'equipmentRecord',
    'safetyRecord',
    'addEquipment',
    'importEquipment',
    'cameraLink',
    'cameraCalibrate',
    'cameraInvalidate',
    'cameraActive',
    'memberManage',
    'reassignTask',
    'assignTask',
    'forwardIssue',
    'editChange',
    'setFloorPlan',
]);

const at = NOW;

/** 만든 시점의 미리 보기 지문을 붙인다. 승인 직전 같은지 다시 확인한다(X01) */
function seal(c: Change): Change {
    return { ...c, previewHash: previewHashOf(c) };
}

function edit(state: DemoState, fn: (d: EventData) => void): DemoState {
    const data = structuredClone(state.data);
    fn(data);
    return { ...state, data };
}

/** AI 입력·승인 유효성에 영향을 주는 변경(계획·인원/가용·배정·보고)마다 현재 상태 번호를 올린다(P04). */
function bump(state: DemoState): DemoState {
    return {
        ...state,
        events: state.events.map((e) =>
            e.id === state.eventId ? { ...e, revision: e.revision + 1 } : e,
        ),
    };
}

function notify(
    d: EventData,
    n: Omit<Notice, 'id' | 'at' | 'read' | 'ack'> & { ack?: boolean },
    seq: number,
) {
    if (!byId(d, n.to)) return;
    d.notices.push({ ack: true, ...n, id: `n${seq}-${d.notices.length}`, at, read: false });
}

/** 본인 보고의 처리 알림(M04): 보고자에게 대응 확정·해결 확인을 알린다 */
function notifyReporter(
    d: EventData,
    issueId: string | null,
    text: string,
    seq: number,
    me: string | null,
) {
    const i = issueId ? d.issues.find((x) => x.id === issueId) : undefined;
    if (!i || i.reporter === me) return;
    notify(
        d,
        { to: i.reporter, kind: 'report', text: `[내 보고] ${i.title} / ${text}`, issue: i.id },
        seq,
    );
}

const chiefs = (d: EventData) => d.members.filter((m) => m.role === 'CHIEF').map((m) => m.id);
/** 구역의 담당 그룹: 그 구역 업무가 가장 많은 그룹(없으면 첫 그룹) */
function groupByZone(d: EventData, zone: string) {
    const count = new Map<string, number>();
    d.tasks
        .filter((t) => t.zone === zone)
        .forEach((t) => count.set(t.groupId, (count.get(t.groupId) ?? 0) + 1));
    return [...count.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? d.groups[0].id;
}
const isChief = (state: DemoState) => byId(state.data, state.me)?.role === 'CHIEF';
const plusMin = (t: string, m: number) => fmtMin(toMin(t) + m);

function slot(d: EventData, taskId: string) {
    const t = d.tasks.find((x) => x.id === taskId)!;
    return { start: t.start, end: t.end, assignee: t.assignee, zone: t.zone };
}

/** 지금 투입할 수 있는 예비 인원: 지정한 사람 → 같은 그룹 → 다른 그룹의 가용 예비 순 */
function pickReserve(d: EventData, zone: string, prefer?: string) {
    const free = reserves(d).filter((m) => m.available);
    if (prefer && free.some((m) => m.id === prefer)) return prefer;
    const g = groupByZone(d, zone);
    return (free.find((m) => m.groupId === g) ?? free[0])?.id ?? null;
}

/** AI 대응안(데모): I5는 대표 시연의 고정 대응안(1곡 3분 지연 + 예비 마이크 전환), 그 외는 현장 확인 업무 1건 추가 */
function proposeOps(d: EventData, issue: Issue, prefer?: string): ChangeOp[] {
    const reserve = pickReserve(d, issue.zone, prefer);
    const groupId = groupByZone(d, issue.zone);
    if (issue.id === 'I5' && d.tasks.some((t) => t.id === 'T21')) {
        const t21 = slot(d, 'T21');
        const t22 = slot(d, 'T22');
        const t09 = slot(d, 'T09');
        return [
            {
                kind: 'program',
                program: 'P2',
                before: { start: '18:30', end: '18:40' },
                after: { start: '18:33', end: '18:43' },
            },
            {
                kind: 'update',
                taskId: 'T21',
                before: t21,
                after: { ...t21, start: '18:33', end: '18:43' },
            },
            {
                kind: 'update',
                taskId: 'T22',
                before: t22,
                after: { ...t22, start: '18:43', end: '18:45' },
            },
            { kind: 'update', taskId: 'T09', before: t09, after: { ...t09, end: '18:33' } },
            {
                kind: 'add',
                tempId: 'T40',
                groupId: 'g4',
                title: '예비 마이크 C 세트 전환',
                after: { start: '18:25', end: '18:31', assignee: reserve, zone: '스테이지' },
                steps: [
                    '음향 부스 예비 함에서 마이크 C 세트 인수',
                    '스테이지 설치',
                    '객석 출력 재시험',
                ],
                equipment: {
                    assets: ['MIC-C'],
                    pickup: '음향 부스 예비 함',
                    dest: '스테이지',
                    reason: '메인 마이크 A 무음',
                    records: {},
                },
            },
        ];
    }
    return [
        {
            kind: 'add',
            tempId: `T${50 + d.changes.length}`,
            groupId,
            title: `${issue.analysis?.category === '인원 지원' ? '지원 인력 투입' : '현장 확인'} / ${issue.zone}`,
            after: { start: at, end: plusMin(at, 30), assignee: reserve, zone: issue.zone },
            steps: ['현장 상황 확인', '조치 결과 보고'],
        },
    ];
}

/** 묶음 전체를 한 사람에게 처음 연결한다. 일부만 저장하지 않는다. */
function assignBundle(d: EventData, key: string, memberId: string, seq: number, by: string) {
    const b = bundlesOf(d).find((x) => x.key === key);
    const m = byId(d, memberId);
    if (!b || !m) return;
    b.tasks.forEach((t) => {
        const task = d.tasks.find((x) => x.id === t.id)!;
        task.assignee = memberId;
        task.firstAssignedAt = at;
    });
    m.assign = 'DONE';
    // 처음 맡는 사실의 알림이며 공식 변경 확인(ack) 대상이 아니다(P03)
    notify(
        d,
        {
            to: memberId,
            kind: 'assign',
            text: `${groupName(d, b.groupId)} 업무 ${b.tasks.length}건이 배정되었습니다 / ${b.tasks
                .map((t) => `${t.start} ${t.title}`)
                .join(', ')}`,
            task: b.tasks[0].id,
        },
        seq,
    );
    d.log.push({
        date: EVENT_DATE,
        at,
        type: '배정',
        text: `${m.name} 최초 배정 / ${b.tasks.map((t) => t.id).join(', ')} / ${by}`,
    });
}

export function reducer(state: DemoState, action: Action): DemoState {
    const seq = state.seq + 1;
    const current = state.events.find((e) => e.id === state.eventId);
    if (current?.state === 'ENDED' && BLOCKED_WHEN_ENDED.has(action.type)) return state;
    switch (action.type) {
        case 'liveLoad':
            return {
                me: action.me,
                eventId: action.eventId,
                events: action.events,
                // 채팅 메시지는 연결이 따로 채운다. 같은 공연을 다시 읽을 때 지우지 않는다(#373).
                data:
                    state.live && state.eventId === action.eventId
                        ? { ...action.data, messages: state.data.messages }
                        : action.data,
                seq: state.seq,
                live: true,
            };
        case 'liveChat':
            return { ...state, data: { ...state.data, messages: action.messages } };
        case 'login':
            return { ...state, me: action.me, eventId: null };
        case 'logout':
            // 실서버 모드를 끝내면 실데이터를 남기지 않고 데모 초기 상태로 돌아간다.
            if (state.live) return initialState();
            return { ...state, me: null, eventId: null };
        case 'enterEvent':
            return { ...state, eventId: action.eventId };
        case 'leaveEvent':
            return { ...state, eventId: null };
        case 'persona':
            return { ...state, me: action.me };
        case 'reset':
            return { ...initialState(), me: state.me, eventId: state.eventId, data: seedData() };
        case 'join': {
            const me = state.me;
            const inv = state.data.invitees.find((x) => x.id === me);
            if (!me || !inv || byId(state.data, me)) return state;
            return bump({
                ...edit(state, (d) => {
                    d.invitees = d.invitees.filter((x) => x.id !== me);
                    d.members.push({
                        id: me,
                        name: inv.name,
                        role: 'WORKER',
                        groupId: action.groupId,
                        isReserve: action.reserve,
                        available: action.available,
                        avail: '13:30–17:30',
                        // 예비로 참여하면 최초 자동 배정 대상이 아니다(비예비만)
                        assign: action.reserve ? undefined : 'WAITING',
                    });
                    d.log.push({
                        date: EVENT_DATE,
                        at,
                        type: '배정',
                        text: `${inv.name} ${groupName(d, action.groupId)} 그룹 참여${action.reserve ? ' (예비)' : ''}`,
                    });
                }),
                seq,
            });
        }
        case 'assignRun':
            return edit(state, (d) => {
                const m = byId(d, action.memberId);
                if (m?.assign === 'WAITING') m.assign = 'RUNNING';
            });
        case 'assignResult': {
            const m0 = byId(state.data, action.memberId);
            if (m0?.assign !== 'RUNNING') return state;
            // 데모 AI: 같은 그룹의 미배정 묶음 중 시간이 겹치지 않는 첫 묶음. 실제는 서버가 검증 후 확정한다.
            const pick = unassignedBundles(state.data, m0.groupId ?? '').find((b) =>
                canTakeBundle(state.data, m0, b),
            );
            if (!pick) {
                return edit(state, (d) => {
                    byId(d, action.memberId)!.assign = 'NO_CANDIDATE';
                });
            }
            return bump({
                ...edit(state, (d) => assignBundle(d, pick.key, action.memberId, seq, 'AI 자동')),
                seq,
            });
        }
        case 'assignRetry':
            return edit(state, (d) => {
                const m = byId(d, action.memberId);
                if (m?.assign && m.assign !== 'DONE') m.assign = 'WAITING';
            });
        case 'assignManual': {
            const m0 = byId(state.data, action.memberId);
            const b = unassignedBundles(state.data).find((x) => x.key === action.bundle);
            if (!m0 || !b || !canTakeBundle(state.data, m0, b)) return state;
            return bump({
                ...edit(state, (d) =>
                    assignBundle(d, b.key, m0.id, seq, `${nameOf(d, state.me)} 수동 대체`),
                ),
                seq,
            });
        }
        case 'setAvailable':
            // available=false는 기존 업무를 해제하지 않는다(P02·BE10)
            return bump(
                edit(state, (d) => {
                    const m = byId(d, state.me);
                    if (m) m.available = action.available;
                }),
            );
        case 'reassignTask': {
            // 총괄이 목록에서 끌어 놓고 팝업에서 확인한 재배정. 직접 고치지 않고 변경안(X01)을 만든 뒤
            // 같은 승인 검사(applyChange)로 적용해 revision·알림·본인 확인 흐름을 그대로 탄다.
            const ev = state.events.find((e) => e.id === state.eventId);
            const t = state.data.tasks.find((x) => x.id === action.taskId);
            const g = state.data.groups.find((x) => x.id === action.groupId);
            const who = action.assignee ? byId(state.data, action.assignee) : null;
            if (!ev || !t || !g || !isChief(state)) return state;
            // 화면이 안내하는 것과 같은 검사(끝난 업무·소속·팀원 여부·바뀐 것 없음)
            const people = action.assignee ? [action.assignee] : [];
            if (assignRefusal(state.data, t.id, [{ groupId: g.id, people }], false)) return state;
            const before = slot(state.data, t.id);
            const created = edit(state, (d) => {
                d.changes.push(
                    seal({
                        id: action.id,
                        issueId: null,
                        by: state.me!,
                        at,
                        status: 'READY',
                        mode: 'MANUAL',
                        title: `${t.title} 재배정 / ${g.name} ${who ? who.name : '미배정'}`,
                        baseRevision: ev.revision,
                        source: '업무 조정 / 목록 배정',
                        ops: [
                            {
                                kind: 'update',
                                taskId: t.id,
                                before: { ...before, groupId: t.groupId },
                                after: { ...before, groupId: g.id, assignee: action.assignee },
                            },
                        ],
                    }),
                );
            });
            return reducer({ ...created, seq }, { type: 'applyChange', id: action.id });
        }
        case 'assignTask': {
            // 변경안을 만들어 같은 승인 검사(applyChange)로 적용한다(revision·알림·본인 확인 흐름 유지)
            const ev = state.events.find((e) => e.id === state.eventId);
            const t = state.data.tasks.find((x) => x.id === action.taskId);
            if (!ev || !t || !isChief(state)) return state;
            // 화면이 안내하는 것과 같은 검사(끝난 업무·중복·소속·팀원 여부·바뀐 것 없음)
            if (assignRefusal(state.data, t.id, action.targets, action.keep)) return state;
            const [first, ...rest] = action.targets;
            const before = slot(state.data, t.id);
            const extra = (people: string[]) =>
                people.length > 1 ? { extraAssignees: people.slice(1) } : {};
            const copy = (x: { groupId: string; people: string[] }): ChangeOp => ({
                kind: 'add',
                tempId: `${t.id}-${x.groupId}`,
                groupId: x.groupId,
                copyOf: taskRoot(state.data, t),
                title: t.title,
                steps: t.steps,
                after: {
                    start: t.start,
                    end: t.end,
                    zone: t.zone,
                    assignee: x.people[0] ?? null,
                    ...extra(x.people),
                },
                ...(t.equipment && { equipment: t.equipment }),
                ...(t.safety && { safety: t.safety }),
            });
            // 기존 업무의 부서·담당이 그대로면 그 업무는 건드리지 않는다(변경 확인 알림도 보내지 않음)
            const leadSame =
                first.groupId === t.groupId && (first.people[0] ?? null) === t.assignee;
            const ops: ChangeOp[] = action.keep
                ? action.targets.map(copy)
                : leadSame
                  ? [
                        ...(first.people.length > 1
                            ? [copy({ groupId: first.groupId, people: first.people.slice(1) })]
                            : []),
                        ...rest.map(copy),
                    ]
                  : [
                        {
                            kind: 'update',
                            taskId: t.id,
                            before: { ...before, groupId: t.groupId },
                            after: {
                                ...before,
                                groupId: first.groupId,
                                assignee: first.people[0] ?? null,
                                ...extra(first.people),
                            },
                        },
                        ...rest.map(copy),
                    ];
            const label = action.targets
                .map(
                    (x) =>
                        `${groupName(state.data, x.groupId)} ${
                            x.people.length
                                ? x.people.map((p) => nameOf(state.data, p)).join(', ')
                                : '미배정'
                        }`,
                )
                .join(', ');
            const created = edit(state, (d) => {
                d.changes.push(
                    seal({
                        id: action.id,
                        issueId: null,
                        by: state.me!,
                        at,
                        status: 'READY',
                        mode: 'MANUAL',
                        title: `${t.title} ${action.keep ? '추가 배정' : '배정'} / ${label}`,
                        baseRevision: ev.revision,
                        source: '공연 계획 / 큐시트 배정',
                        ops,
                    }),
                );
            });
            return withRelated(
                reducer({ ...created, seq }, { type: 'applyChange', id: action.id }),
                action.id,
                t.id,
            );
        }
        case 'restoreTask': {
            const t = state.data.tasks.find((x) => x.id === action.taskId);
            if (!t || !isChief(state) || restoreRefusal(state.data, t.id)) return state;
            return bump({
                ...edit(state, (d) => {
                    const u = d.tasks.find((x) => x.id === t.id)!;
                    Object.assign(u, { status: 'todo', ack: false, changed: '삭제했던 업무 복원' });
                    if (u.assignee)
                        notify(
                            d,
                            {
                                to: u.assignee,
                                targetIds: [u.id],
                                targetHash: taskTargetHash(d, [u.id]),
                                kind: 'change',
                                text: `${u.title} — 삭제했던 업무가 복원되었습니다`,
                                task: u.id,
                                ack: false,
                            },
                            seq,
                        );
                    d.log.push({
                        date: EVENT_DATE,
                        at,
                        type: '변경',
                        text: `업무 복원 / ${u.title} (${u.id}) / ${nameOf(d, state.me)}`,
                    });
                }),
                seq,
            });
        }
        case 'purgeTask': {
            const t = state.data.tasks.find((x) => x.id === action.taskId);
            // 삭제(취소)된 업무만 영구 삭제한다
            if (!t || !isChief(state) || t.status !== 'canceled') return state;
            return {
                ...edit(state, (d) => {
                    d.tasks = d.tasks.filter((x) => x.id !== t.id);
                    d.purgedTasks = { ...d.purgedTasks, [t.id]: t.title };
                    d.log.push({
                        date: EVENT_DATE,
                        at,
                        type: '변경',
                        text: `업무 영구 삭제 / ${t.title} (${t.id}) / ${nameOf(d, state.me)}`,
                    });
                }),
                seq,
            };
        }
        case 'deleteTasks': {
            const ev = state.events.find((e) => e.id === state.eventId);
            const tasks = action.taskIds.map((id) => state.data.tasks.find((x) => x.id === id));
            if (!ev || !isChief(state) || !tasks.length) return state;
            // 끝났거나 이미 취소된 업무는 삭제하지 않는다
            if (!tasks.every((t) => t && t.status !== 'done' && t.status !== 'canceled'))
                return state;
            const list = tasks as Task[];
            const [t] = list;
            const created = edit(state, (d) => {
                d.changes.push(
                    seal({
                        id: action.id,
                        issueId: null,
                        by: state.me!,
                        at,
                        status: 'READY',
                        mode: 'MANUAL',
                        title: `${t.title} 삭제 / ${list.map((x) => nameOf(state.data, x.assignee)).join(', ')}`,
                        baseRevision: ev.revision,
                        source: '공연 계획 / 큐시트 삭제',
                        ops: list.map((x) => ({
                            kind: 'cancel',
                            taskId: x.id,
                            before: slot(state.data, x.id),
                        })),
                    }),
                );
            });
            const applied = reducer({ ...created, seq }, { type: 'applyChange', id: action.id });
            // 같은 업무를 여러 건 지웠으면 지운 업무를 변경 기록에 링크로 남긴다
            if (list.length < 2) return applied;
            return edit(applied, (d) => {
                const c = d.changes.find((x) => x.id === action.id);
                if (c?.status === 'APPLIED') c.related = list.map((x) => x.id);
            });
        }
        case 'assignFamily': {
            const ev = state.events.find((e) => e.id === state.eventId);
            const t = state.data.tasks.find((x) => x.id === action.taskId);
            if (!ev || !t || !isChief(state)) return state;
            if (familyAssignRefusal(state.data, t.id, action.targets)) return state;
            const family = taskFamily(state.data, t);
            const root = family.find((u) => u.id === taskRoot(state.data, t)) ?? family[0];
            const wanted = action.targets.flatMap((x) =>
                x.people.map((p) => ({ groupId: x.groupId, person: p })),
            );
            // 그대로 맡는 사람은 그 업무를 두고, 빠진 사람의 업무(미배정 포함)는 새로 고른 사람에게 넘긴다.
            // 넘길 사람이 모자라면 남은 업무를 취소하고, 남으면 같은 업무를 한 건씩 새로 만든다
            // 한 사람당 한 건만 남긴다(예전에 같은 사람에게 두 번 배정된 업무가 있으면 하나만 두고 정리한다)
            const kept: Task[] = [];
            family.forEach((u) => {
                const w = wanted.some((x) => x.person === u.assignee && x.groupId === u.groupId);
                if (w && !kept.some((k) => k.assignee === u.assignee)) kept.push(u);
            });
            const freed = family.filter((u) => !kept.includes(u) && u.status !== 'done');
            const added = wanted.filter(
                (w) => !kept.some((u) => u.assignee === w.person && u.groupId === w.groupId),
            );
            const ops: ChangeOp[] = [];
            freed.forEach((u, k) => {
                const w = added[k];
                const before = slot(state.data, u.id);
                if (w)
                    ops.push({
                        kind: 'update',
                        taskId: u.id,
                        before: { ...before, groupId: u.groupId },
                        after: { ...before, groupId: w.groupId, assignee: w.person },
                    });
                // 아무도 남지 않으면 원 업무는 미배정으로 남기고 나머지만 취소한다
                else if (!wanted.length && u.id === root.id)
                    ops.push({
                        kind: 'update',
                        taskId: u.id,
                        before: { ...before, groupId: u.groupId },
                        after: { ...before, assignee: null },
                    });
                else ops.push({ kind: 'cancel', taskId: u.id, before });
            });
            added.slice(freed.length).forEach((w) =>
                ops.push({
                    kind: 'add',
                    tempId: `${root.id}-${w.groupId}`,
                    groupId: w.groupId,
                    copyOf: root.id,
                    title: root.title,
                    steps: root.steps,
                    after: {
                        start: root.start,
                        end: root.end,
                        zone: root.zone,
                        assignee: w.person,
                    },
                    ...(root.equipment && { equipment: root.equipment }),
                    ...(root.safety && { safety: root.safety }),
                }),
            );
            if (!ops.length) return state;
            const label = wanted.length
                ? wanted.map((w) => nameOf(state.data, w.person)).join(', ')
                : '미배정';
            const created = edit(state, (d) => {
                d.changes.push(
                    seal({
                        id: action.id,
                        issueId: null,
                        by: state.me!,
                        at,
                        status: 'READY',
                        mode: 'MANUAL',
                        title: `${root.title} 같은 업무 ${family.length}건 일괄 배정 / ${label}`,
                        baseRevision: ev.revision,
                        source: '공연 계획 / 큐시트 배정(같은 업무 일괄)',
                        ops,
                    }),
                );
            });
            return withRelated(
                reducer({ ...created, seq }, { type: 'applyChange', id: action.id }),
                action.id,
                root.id,
                family.map((u) => u.id),
            );
        }
        case 'setFloorPlan': {
            if (!isChief(state)) return state;
            const names = action.zones.map((z) => z.name.trim());
            const ok =
                names.every((n) => n.length > 0 && n.length <= 30) &&
                new Set(names).size === names.length &&
                action.zones.every(
                    ({ rect: [x, y, w, h] }) =>
                        [x, y, w, h].every((v) => Number.isFinite(v)) &&
                        x >= 0 &&
                        y >= 0 &&
                        w >= 2 &&
                        h >= 2 &&
                        x + w <= 100.5 &&
                        y + h <= 100.5,
                );
            if (!ok) return state;
            return {
                ...edit(state, (d) => {
                    if (action.plan !== undefined) {
                        if (action.plan) d.floorPlan = action.plan;
                        else delete d.floorPlan;
                    }
                    const prev = new Map(d.safetyZones.map((z) => [z.id, z]));
                    d.safetyZones = action.zones.map((z, k) => {
                        const old = z.id ? prev.get(z.id) : undefined;
                        return old
                            ? { ...old, name: z.name.trim(), rect: z.rect }
                            : {
                                  id: `z-${seq}-${k}`,
                                  name: z.name.trim(),
                                  area: null,
                                  limit: null,
                                  minStaff: 0,
                                  rect: z.rect,
                              };
                    });
                    // 지운 구역은 카메라 관측 구역에서도 뺀다
                    const alive = new Set(d.safetyZones.map((z) => z.id));
                    d.cameras.forEach((c) => (c.zones = c.zones.filter((id) => alive.has(id))));
                    d.log.push({
                        date: EVENT_DATE,
                        at,
                        type: '안전',
                        text: `평면도/구역 ${d.safetyZones.length}개 저장 / ${nameOf(d, state.me)}`,
                    });
                }),
                seq,
            };
        }
        case 'editChange': {
            // 총괄이 확정 전 변경안의 "변경 후" 값과 확인 필요 사항을 고친다. 대상은 그대로 두고, 고친 내용으로 미리 보기 지문을 다시 만든다
            if (!isChief(state)) return state;
            const c0 = state.data.changes.find((x) => x.id === action.id);
            if (!c0 || c0.status !== 'READY' || c0.ops.length !== action.ops.length) return state;
            const valid = action.ops.every((o, k) => {
                const prev = c0.ops[k];
                if (o.kind !== prev.kind) return false;
                if (o.kind === 'cancel') return true;
                if (!isValidTimeRange(o.after.start, o.after.end)) return false;
                const extras = o.after.extraAssignees ?? [];
                const people = [o.after.assignee, ...extras].filter((x) => !!x) as string[];
                if (new Set(people).size !== people.length) return false;
                return people.every((who) => byId(state.data, who)?.role === 'WORKER');
            });
            if (!valid) return state;
            return edit(state, (d) => {
                const c = d.changes.find((x) => x.id === action.id)!;
                c.ops = c.ops.map((o, k) => {
                    const n = action.ops[k];
                    if (o.kind === 'cancel' || n.kind === 'cancel') return o;
                    if (o.kind === 'add')
                        return n.kind === 'add'
                            ? { ...o, steps: n.steps, after: { ...o.after, ...n.after } }
                            : o;
                    if (o.kind === 'program') return { ...o, after: { ...o.after, ...n.after } };
                    // 확정 뒤에도 전후를 비교할 수 있게 바꾸기 전 절차를 현재 값에 적어 둔다
                    const task = d.tasks.find((t) => t.id === o.taskId);
                    return {
                        ...o,
                        before: { ...o.before, steps: n.after.steps ? task?.steps : undefined },
                        after: { ...o.after, ...n.after },
                    };
                });
                if (action.checks !== undefined) c.checks = action.checks;
                c.previewHash = previewHashOf(c);
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '변경',
                    text: `변경안 편집 / ${c.title} / ${nameOf(d, state.me)}`,
                });
            });
        }
        case 'forwardIssue': {
            if (!isChief(state)) return state;
            const i0 = state.data.issues.find((x) => x.id === action.issueId);
            if (!i0) return state;
            const g = action.groupId
                ? state.data.groups.find((x) => x.id === action.groupId)
                : null;
            if (action.groupId && !g) return state;
            const who = action.memberId ? byId(state.data, action.memberId) : null;
            // 사람을 고르면 고른 부서의 팀원이어야 한다
            if (action.memberId && (!who || (g && who.groupId !== g.id))) return state;
            const note = action.note.trim().slice(0, 500);
            const mentions = mentionsIn(state.data, note).filter((id) => id !== state.me);
            const to = new Set<string>(
                who
                    ? [who.id]
                    : g
                      ? state.data.members
                            .filter((m) => m.role === 'WORKER' && m.groupId === g.id)
                            .map((m) => m.id)
                      : [],
            );
            mentions.forEach((id) => to.add(id));
            to.delete(state.me ?? '');
            if (!to.size) return state;
            return edit(state, (d) => {
                const i = d.issues.find((x) => x.id === action.issueId)!;
                i.forwards = [
                    ...(i.forwards ?? []),
                    {
                        at,
                        by: state.me!,
                        groupId: g?.id ?? null,
                        memberId: who?.id ?? null,
                        mentions,
                        note,
                    },
                ];
                to.forEach((id) =>
                    notify(
                        d,
                        {
                            to: id,
                            kind: 'report',
                            text: `[전달] ${i.title}${mentions.includes(id) ? ' / 회원님이 태그되었습니다' : ''}${note ? ` / ${note}` : ''}`,
                            issue: i.id,
                        },
                        seq,
                    ),
                );
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '이슈',
                    text: `${i.id} 전달 / ${who ? who.name : g ? `${g.name} 전체` : '태그'}${mentions.length ? ` / @${mentions.map((id) => nameOf(d, id)).join(' @')}` : ''} / ${nameOf(d, state.me)}`,
                    ref: i.id,
                });
            });
        }
        case 'memberManage': {
            // 가용·예비 전환은 기존 업무를 유지한다. 소속 이동은 진행 중 업무가 있으면 거절하고
            // 대기 업무 담당만 해제한다. 최초 배정 시각과 완료·취소 기록은 보존한다(BE02).
            if (!isChief(state)) return state;
            const m0 = byId(state.data, action.memberId);
            if (!m0 || m0.role !== 'WORKER') return state;
            const { available, isReserve, groupId } = action.patch;
            if (groupId !== undefined && !state.data.groups.some((g) => g.id === groupId))
                return state;
            const moving = groupId !== undefined && m0.groupId !== groupId;
            if (
                moving &&
                state.data.tasks.some((t) => t.assignee === m0.id && t.status === 'doing')
            )
                return state;
            return bump(
                edit(state, (d) => {
                    const m = byId(d, action.memberId)!;
                    const changes: string[] = [];
                    if (available !== undefined && m.available !== available) {
                        m.available = available;
                        changes.push(available ? '가용' : '지금 불가');
                    }
                    if (isReserve !== undefined && m.isReserve !== isReserve) {
                        m.isReserve = isReserve;
                        changes.push(isReserve ? '예비 전환' : '예비 해제');
                    }
                    if (groupId !== undefined && m.groupId !== groupId) {
                        changes.push(`소속 ${groupName(d, m.groupId)} → ${groupName(d, groupId)}`);
                        m.groupId = groupId;
                        d.tasks
                            .filter((t) => t.assignee === m.id && t.status === 'todo')
                            .forEach((t) => {
                                t.assignee = null;
                                t.ack = false;
                                t.changed = '소속 이동으로 담당 해제';
                            });
                    }
                    if (!changes.length) return;
                    d.log.push({
                        date: EVENT_DATE,
                        at,
                        type: '인원',
                        text: `${m.name} ${changes.join(' / ')} / ${nameOf(d, state.me)}`,
                    });
                }),
            );
        }
        case 'replacePlan': {
            // 데모 계획 수정: 계획 전체를 바꾼다. 남은 업무는 배정·진행·확인 상태를 이어 받고,
            // 같은 업무의 복사본도 원 업무의 내용을 따른다. 계획에서 뺀 업무는 복사본까지 지운다.
            const { plan } = action;
            const next = bump(
                edit(state, (d) => {
                    d.groups = plan.groups.map((g) => {
                        const old = d.groups.find((x) => x.id === g.id);
                        return { ...old, id: g.id, name: g.name, guide: g.guide };
                    });
                    const kept = new Map(plan.tasks.map((t) => [t.id, t]));
                    const rootOf = (t: Task) => t.copyOf ?? t.id;
                    const tasks: Task[] = [];
                    for (const t of d.tasks) {
                        const edited = kept.get(rootOf(t));
                        if (!edited) continue;
                        tasks.push({
                            ...t,
                            title: edited.title,
                            groupId: edited.groupId,
                            zone: edited.zone,
                            program: edited.program,
                            bundle: edited.bundle,
                            date: edited.date,
                            start: edited.start,
                            end: edited.end,
                            steps: edited.steps,
                            checklist: edited.checklist.map((c, i) => ({
                                text: c.text,
                                required: c.required,
                                done: t.checklist[i]?.text === c.text ? t.checklist[i].done : false,
                            })),
                        });
                    }
                    const existing = new Set(d.tasks.map((t) => t.id));
                    for (const t of plan.tasks) {
                        if (existing.has(t.id)) continue;
                        tasks.push({
                            ...t,
                            assignee: null,
                            status: 'todo',
                            ack: true,
                            checklist: t.checklist.map((c) => ({ ...c, done: false })),
                        });
                    }
                    d.tasks = tasks;
                    d.log.push({ date: EVENT_DATE, at, type: '정보', text: '공연 계획 수정' });
                }),
            );
            return {
                ...next,
                events: next.events.map((e) =>
                    e.id === (state.eventId ?? 'e1')
                        ? {
                              ...e,
                              zones: plan.zones,
                              programs: plan.programs.map((p) => {
                                  const old = e.programs.find((x) => x.id === p.id);
                                  return { ...old, ...p };
                              }),
                              notice: plan.notice || undefined,
                          }
                        : e,
                ),
            };
        }
        case 'updateProfile': {
            const { name, avail, skills, wait } = action.profile;
            return {
                ...bump(
                    edit(state, (d) => {
                        const m = byId(d, state.me);
                        if (!m) return;
                        m.avail = avail;
                        m.skills = skills || undefined;
                        m.wait = wait || undefined;
                        const next = name.trim();
                        if (!next || next === m.name) {
                            delete m.nameRequest;
                        } else if (m.role === 'CHIEF') {
                            d.log.push({
                                date: EVENT_DATE,
                                at,
                                type: '정보',
                                text: `${m.name} → ${next} 이름 변경`,
                            });
                            m.name = next;
                            delete m.nameRequest;
                        } else if (m.nameRequest !== next) {
                            m.nameRequest = next;
                            chiefs(d).forEach((to) =>
                                notify(
                                    d,
                                    {
                                        to,
                                        kind: 'profile',
                                        text: `${m.name} 님이 이름을 '${next}'(으)로 바꾸려 합니다 / 확인 필요`,
                                    },
                                    seq,
                                ),
                            );
                        }
                    }),
                ),
                seq,
            };
        }
        case 'nameDecision':
            return {
                ...edit(state, (d) => {
                    const m = byId(d, action.memberId);
                    if (!m?.nameRequest || byId(d, state.me)?.role !== 'CHIEF') return;
                    const next = m.nameRequest;
                    delete m.nameRequest;
                    if (action.approve) {
                        d.log.push({
                            date: EVENT_DATE,
                            at,
                            type: '정보',
                            text: `${m.name} → ${next} 이름 변경 승인 / ${nameOf(d, state.me)}`,
                        });
                        m.name = next;
                    }
                    notify(
                        d,
                        {
                            to: m.id,
                            kind: 'profile',
                            text: action.approve
                                ? `이름 변경이 승인되었습니다 / ${next}`
                                : `이름 변경 요청('${next}')이 반려되었습니다`,
                        },
                        seq,
                    );
                }),
                seq,
            };
        case 'taskStart':
            return edit(state, (d) => {
                const t = d.tasks.find((x) => x.id === action.id);
                if (t?.status === 'todo') t.status = 'doing';
            });
        case 'taskDone':
            return edit(state, (d) => {
                const t = d.tasks.find((x) => x.id === action.id);
                if (t && checklistReady(t)) t.status = 'done';
            });
        case 'toggleCheck':
            return edit(state, (d) => {
                const c = d.tasks.find((x) => x.id === action.id)?.checklist[action.index];
                if (c) c.done = !c.done;
            });
        case 'report': {
            const r = action.input;
            const me = state.me!;
            return bump({
                ...edit(state, (d) => {
                    const report = { kind: r.kind, by: me, at, via: r.via, text: r.text };
                    const existing = r.issueId
                        ? d.issues.find((i) => i.id === r.issueId)
                        : undefined;
                    if (existing) {
                        existing.reports.push(report);
                        if (r.kind === 'RESOLUTION') existing.status = 'RESOLUTION_REPORTED';
                        d.log.push({
                            date: EVENT_DATE,
                            at,
                            type: '이슈',
                            text: `${existing.id} 추가 보고 / ${nameOf(d, me)}`,
                            ref: existing.id,
                        });
                        return;
                    }
                    const title = r.text.length > 24 ? `${r.text.slice(0, 24)}…` : r.text;
                    d.issues.push({
                        id: r.id,
                        title,
                        summary: r.text,
                        zone: r.zone,
                        status: 'RECEIVED',
                        at,
                        reporter: me,
                        tasks: r.taskId ? [r.taskId] : [],
                        reports: [report],
                        next: '내용을 확인하고 대응안을 만드세요.',
                    });
                    const text = `${nameOf(d, me)} / ${r.zone} / ${title}`;
                    // 공식 보고는 총괄에게 간다. 그룹은 승인권이 없는 평면 분류다.
                    chiefs(d).forEach(
                        (to) =>
                            to !== me && notify(d, { to, kind: 'report', text, issue: r.id }, seq),
                    );
                    d.log.push({
                        date: EVENT_DATE,
                        at,
                        type: '이슈',
                        text: `${r.id} ${title} 접수 / ${nameOf(d, me)}`,
                        ref: r.id,
                    });
                }),
                seq,
            });
        }
        case 'aiChange':
            return {
                ...edit(state, (d) => {
                    const issue = d.issues.find((i) => i.id === action.issueId);
                    const ev = state.events.find((e) => e.id === state.eventId);
                    if (!issue || !ev) return;
                    d.changes.push(
                        seal({
                            id: action.id,
                            issueId: issue.id,
                            by: state.me!,
                            at,
                            status: 'READY',
                            mode: 'AI',
                            title: `${issue.title} 대응안`,
                            baseRevision: ev.revision,
                            ops: proposeOps(d, issue, action.reserveId),
                        }),
                    );
                    issue.status = 'RESPONDING';
                }),
                seq,
            };
        case 'applyChange': {
            const c0 = state.data.changes.find((c) => c.id === action.id);
            const ev0 = state.events.find((e) => e.id === state.eventId);
            // 총괄만, 검토한 revision이 지금과 같을 때만 적용한다. 조용히 최신 내용으로 계산하지 않는다.
            if (!c0 || !ev0 || c0.status !== 'READY') return state;
            if (byId(state.data, state.me)?.role !== 'CHIEF') return state;
            // 승인 직전 검사: revision·미리 보기 지문·관측·가용·예약 중 하나라도 바뀌었으면 적용하지 않는다
            if (changeStaleReasons(state.data, ev0, c0).length) return state;
            const events = state.events.map((e) => {
                if (e.id !== ev0.id) return e;
                const programs = e.programs.map((p) => {
                    const op = c0.ops.find((o) => o.kind === 'program' && o.program === p.id);
                    return op && op.kind === 'program'
                        ? { ...p, start: op.after.start, end: op.after.end }
                        : p;
                });
                return { ...e, revision: e.revision + 1, programs };
            });
            const next = edit(state, (d) => {
                const c = d.changes.find((x) => x.id === action.id)!;
                const reserveIds = new Set(reserves(d).map((m) => m.id));
                applyOps(d, c, seq);
                c.status = 'APPLIED';
                c.appliedAt = at;
                const issue = c.issueId ? d.issues.find((i) => i.id === c.issueId) : undefined;
                if (issue && issue.status === 'RECEIVED') issue.status = 'RESPONDING';
                notifyReporter(d, c.issueId, '대응이 확정되었습니다', seq, state.me);
                affectedOf(c).forEach((to) => {
                    // 확인 대상: 이 적용으로 바뀐 본인 업무. 대상 지문이 바뀌면 다시 확인받는다(M08)
                    const targetIds = d.tasks
                        .filter((t) => t.assignee === to && !t.ack)
                        .map((t) => t.id);
                    notify(
                        d,
                        {
                            to,
                            targetIds,
                            targetHash: taskTargetHash(d, targetIds),
                            kind: reserveIds.has(to) ? 'deploy' : 'change',
                            text: reserveIds.has(to)
                                ? `${c.title} — 예비 인원으로 새 업무에 투입되었습니다`
                                : `${c.title} — 내 업무가 바뀌었습니다`,
                            change: c.id,
                            ack: false,
                        },
                        seq,
                    );
                });
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '변경',
                    text: `변경 확정 / ${c.title} / ${c.ops.length}건 / ${nameOf(d, state.me)}`,
                    ref: c.issueId ?? undefined,
                });
            });
            return { ...next, events, seq };
        }
        case 'rebaseChange': {
            // 낡은 변경안: 바꿀 값은 유지하고 '현재' 값을 지금 상태로 다시 읽어 명시 재확인을 받는다.
            const ev = state.events.find((e) => e.id === state.eventId);
            if (!ev) return state;
            return edit(state, (d) => {
                const c = d.changes.find((x) => x.id === action.id);
                if (!c || c.status !== 'READY') return;
                c.ops = c.ops.map((o) =>
                    (o.kind === 'update' || o.kind === 'cancel') &&
                    d.tasks.some((t) => t.id === o.taskId)
                        ? { ...o, before: slot(d, o.taskId) }
                        : o,
                );
                c.baseRevision = ev.revision;
                // 사용자가 명시로 다시 확인한 시점의 관측·미리 보기를 새 근거로 삼는다
                c.basis?.obs?.forEach((b) => {
                    b.at = d.observations.find((o) => o.zoneId === b.zoneId)?.at ?? null;
                });
                c.previewHash = previewHashOf(c);
            });
        }
        case 'discardChange':
            return edit(state, (d) => {
                const c = d.changes.find((x) => x.id === action.id);
                if (c) c.status = 'DISCARDED';
            });
        case 'closeIssue':
            return edit(state, (d) => {
                const i = d.issues.find((x) => x.id === action.id);
                if (!i) return;
                i.status = 'CLOSED';
                i.closedAt = at;
                notifyReporter(d, i.id, '해결 확인 후 종료되었습니다', seq, state.me);
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '이슈',
                    text: `${i.id} 해결 확인 / 종료 / ${nameOf(d, state.me)}`,
                    ref: i.id,
                });
            });
        case 'endEvent': {
            if (byId(state.data, state.me)?.role !== 'CHIEF') return state;
            const next = edit(state, (d) => {
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '행사',
                    text: `행사 종료 / ${nameOf(d, state.me)}`,
                });
            });
            return {
                ...next,
                events: state.events.map((e) =>
                    e.id === state.eventId ? { ...e, state: 'ENDED' } : e,
                ),
            };
        }
        case 'ackNotice':
            return edit(state, (d) => {
                const n = d.notices.find((x) => x.id === action.id);
                if (!n || n.to !== state.me) return;
                n.ack = true;
                n.read = true;
                // 확인한 대상 지문을 기록한다. 대상이 바뀌어 다시 확인해도 이전 확인은 남긴다
                if (n.targetIds)
                    n.acks = [...(n.acks ?? []), { at, hash: taskTargetHash(d, n.targetIds) }];
                d.tasks.filter((t) => t.assignee === n.to && !t.ack).forEach((t) => (t.ack = true));
            });
        case 'readNotice':
            return edit(state, (d) => {
                const n = d.notices.find((x) => x.id === action.id);
                if (n) n.read = true;
            });
        case 'readAllNotices':
            return edit(state, (d) =>
                d.notices.filter((n) => n.to === state.me).forEach((n) => (n.read = true)),
            );
        case 'setSituation':
            // 총괄이 근거와 함께 유형을 확인/정정한다. 원문·이전 분석은 지우지 않는다(P14).
            if (!isChief(state)) return state;
            return edit(state, (d) => {
                const i = d.issues.find((x) => x.id === action.issueId);
                if (!i) return;
                i.situation = {
                    primary: action.primary,
                    related: action.related.filter((r) => r !== action.primary),
                    source: '총괄 확인',
                    by: state.me!,
                    at,
                };
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '이슈',
                    text: `${i.id} 상황 유형 확인 / ${nameOf(d, state.me)}`,
                    ref: i.id,
                });
            });
        case 'recoveryRun':
        case 'reanalyze':
            return state;
        case 'recoveryChange': {
            // 복구 대안 선택은 미리 보기다. 변경안을 만들 뿐 예약·담당·물리 상태는 승인 전까지 그대로다.
            const o = state.data.recovery.find((x) => x.id === action.optionId);
            const ev = state.events.find((e) => e.id === state.eventId);
            if (!o || !ev || !isChief(state)) return state;
            if (o.verdict !== 'OK' && o.verdict !== 'CONDITIONAL') return state;
            return {
                ...edit(state, (d) => {
                    const staff = byId(d, o.staff);
                    d.changes.push(
                        seal({
                            id: action.id,
                            issueId: o.issueId,
                            by: state.me!,
                            at,
                            status: 'READY',
                            mode: 'AI',
                            title: o.title,
                            baseRevision: ev.revision,
                            source: '장비 운영 / 복구 대안',
                            reservations: o.reservations,
                            releases: o.releases,
                            ops: [
                                {
                                    kind: 'add',
                                    tempId: `T4${d.changes.length}`,
                                    groupId: staff?.groupId ?? groupByZone(d, o.task.zone),
                                    title: o.task.title,
                                    after: {
                                        start: o.task.start,
                                        end: o.task.end,
                                        assignee: o.staff,
                                        zone: o.task.zone,
                                    },
                                    steps: o.task.steps,
                                    equipment: {
                                        assets: o.task.assets,
                                        pickup: o.task.pickup,
                                        dest: o.task.dest,
                                        reason: o.task.reason,
                                        records: {},
                                    },
                                },
                            ],
                        }),
                    );
                    const issue = d.issues.find((i) => i.id === o.issueId);
                    if (issue && issue.status === 'RECEIVED') issue.status = 'RESPONDING';
                }),
                seq,
            };
        }
        case 'safetyChange': {
            // 안전 지원: 원 구역 최소 유지·필수 업무를 지키는 후보만 변경안으로 만든다. 승인 전에는 이동하지 않는다.
            const ev = state.events.find((e) => e.id === state.eventId);
            const zone = state.data.safetyZones.find((z) => z.id === action.zoneId);
            const cand = supportCandidates(state.data, action.zoneId).find(
                (c) => c.member.id === action.memberId,
            );
            if (!ev || !zone || !cand || cand.verdict === 'NO' || !isChief(state)) return state;
            return {
                ...edit(state, (d) => {
                    const issue = d.issues.find(
                        (i) =>
                            i.zone === zone.name &&
                            i.status !== 'CLOSED' &&
                            i.situation?.primary === 'AUDIENCE_SAFETY',
                    );
                    d.changes.push(
                        seal({
                            id: action.id,
                            issueId: issue?.id ?? null,
                            by: state.me!,
                            at,
                            status: 'READY',
                            mode: 'AI',
                            title: `${zone.name} 안전 지원 / ${cand.member.name}`,
                            baseRevision: ev.revision,
                            source: '안전 / 지원 후보',
                            // 근거로 본 구역 관측. 승인 직전 갱신·낡음을 비교한다
                            basis: {
                                obs: [
                                    {
                                        zoneId: zone.id,
                                        at:
                                            d.observations.find((o) => o.zoneId === zone.id)?.at ??
                                            null,
                                    },
                                ],
                            },
                            ops: [
                                {
                                    kind: 'add',
                                    tempId: `T6${d.changes.length}`,
                                    groupId: cand.member.groupId ?? groupByZone(d, zone.name),
                                    title: `${zone.name} 안전 지원`,
                                    after: {
                                        start: at,
                                        end: plusMin(at, 30),
                                        assignee: cand.member.id,
                                        zone: zone.name,
                                    },
                                    steps: [
                                        `${zone.name}로 이동(약 ${cand.eta}분)`,
                                        '관객 밀집 현장 확인',
                                        '통로 확보 후 결과 보고',
                                    ],
                                    safety: {
                                        zoneId: zone.id,
                                        keep: cand.from ? `${cand.from} 최소 인원 유지` : '',
                                        records: {},
                                    },
                                },
                            ],
                        }),
                    );
                    if (issue && issue.status === 'RECEIVED') issue.status = 'RESPONDING';
                }),
                seq,
            };
        }
        case 'equipmentRecord':
            // 인수·설치·재시험은 각각 따로 기록한다. 같은 단계는 한 번만, 앞 단계 없이 뒤 단계를 기록하지 않는다.
            return edit(state, (d) => {
                const t = d.tasks.find((x) => x.id === action.taskId);
                if (!t?.equipment || t.assignee !== state.me) return;
                const r = t.equipment.records;
                if (r[action.step]) return;
                if (action.step === 'install' && !r.pickup) return;
                if (action.step === 'retest' && !r.install) return;
                r[action.step] = at;
                const label = { pickup: '인수', install: '설치', retest: '재시험' }[action.step];
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '변경',
                    text: `${t.id} 장비 ${label} 기록 / ${nameOf(d, state.me)}`,
                });
            });
        case 'addEquipment':
            // 장비 등록(D01): 담당자 확인 전이라 기술 상태는 미확인으로 시작한다
            if (!isChief(state)) return state;
            return edit(state, (d) => {
                d.equipment.push({
                    id: action.id,
                    name: action.name,
                    kind: action.kind,
                    place: action.place,
                    tech: 'UNKNOWN',
                    source: '미확인',
                    path: [action.id],
                });
            });
        case 'importEquipment':
            // 장비 자료 가져오기(D01): 확인 화면에서 고른 행만 등록하고, 기술 상태는 담당자 확인 전이라 미확인이다
            if (!isChief(state)) return state;
            return edit(state, (d) => {
                action.items.forEach((it) => {
                    if (d.equipment.some((a) => a.id === it.id)) return;
                    d.equipment.push({
                        id: it.id,
                        name: it.name,
                        kind: it.kind,
                        place: it.place,
                        tech: 'UNKNOWN',
                        source: '미확인',
                        note: `자료 가져오기 / ${action.source}`,
                        path: [it.id],
                    });
                });
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '장비',
                    text: `장비 자료 ${action.items.length}건 등록 / ${action.source} / ${nameOf(d, state.me)}`,
                });
            });
        case 'cameraLink':
            // 고정 카메라 연결(S01): 연결되면 첫 분석 전까지 관측은 분석 대기다
            if (!isChief(state)) return state;
            return edit(state, (d) => {
                const c = d.cameras.find((x) => x.id === action.cameraId);
                if (!c || c.active === false) return;
                c.link = action.link;
                if (action.link === 'CONNECTED') {
                    c.frameAt = `${at}:00`;
                    d.observations
                        .filter((o) => o.cameraId === c.id)
                        .forEach((o) => (o.fresh = 'PENDING'));
                }
            });
        case 'cameraCalibrate':
            // 구역/ROI 보정 확인: 이전 보정 무효를 지우고, 새 보정 기준의 첫 분석 전까지 관측은 분석 대기다
            if (!isChief(state)) return state;
            return edit(state, (d) => {
                const c = d.cameras.find((x) => x.id === action.cameraId);
                if (!c || c.link !== 'CONNECTED' || c.active === false) return;
                c.calibrated = true;
                c.calibratedAt = at;
                delete c.calibrationInvalid;
                d.observations
                    .filter((o) => o.cameraId === c.id)
                    .forEach((o) => (o.fresh = 'PENDING'));
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '안전',
                    text: `${c.name} 보정 확인 / ${nameOf(d, state.me)}`,
                });
            });
        case 'cameraInvalidate':
            // 카메라 이동·줌·구역 변경: 재보정 전 관련 추정은 확인 필요(C03)
            if (!isChief(state)) return state;
            return edit(state, (d) => {
                const c = d.cameras.find((x) => x.id === action.cameraId);
                if (!c) return;
                c.calibrationInvalid = action.reason;
            });
        case 'cameraActive':
            if (!isChief(state)) return state;
            return edit(state, (d) => {
                const c = d.cameras.find((x) => x.id === action.cameraId);
                if (!c) return;
                c.active = action.active;
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '안전',
                    text: `${c.name} ${action.active ? '활성' : '비활성'} / ${nameOf(d, state.me)}`,
                });
            });
        case 'safetyRecord':
            return edit(state, (d) => {
                const t = d.tasks.find((x) => x.id === action.taskId);
                if (!t?.safety || t.assignee !== state.me) return;
                const r = t.safety.records;
                if (r[action.step]) return;
                if (action.step === 'check' && !r.arrive) return;
                r[action.step] = at;
                const label = action.step === 'arrive' ? '도착' : '현장 확인';
                d.log.push({
                    date: EVENT_DATE,
                    at,
                    type: '변경',
                    text: `${t.id} ${label} 보고 / ${nameOf(d, state.me)}`,
                });
            });
        case 'send':
            return {
                ...edit(state, (d) => {
                    const last = Math.max(
                        0,
                        ...d.messages.filter((m) => m.room === action.room).map((m) => m.seq),
                    );
                    d.messages.push({
                        id: action.id,
                        room: action.room,
                        seq: last + 1,
                        by: state.me!,
                        at,
                        text: action.text,
                    });
                }),
                seq,
            };
    }
}

/** 추가 담당: 같은 내용의 업무를 사람마다 하나씩 만든다. 진행 기록은 물려받지 않는다 */
/**
 * 적용된 배정 변경안에 함께 바뀐 같은 업무의 id를 남긴다(변경 기록에서 링크로 보인다).
 * 지금 같은 업무 + 이번에 취소된 업무(before)를 모두 남기고, 같은 업무가 한 건뿐이면 남기지 않는다.
 */
function withRelated(state: DemoState, changeId: string, taskId: string, before: string[] = []) {
    const c = state.data.changes.find((x) => x.id === changeId);
    const t = state.data.tasks.find((x) => x.id === taskId);
    if (!c || c.status !== 'APPLIED' || !t) return state;
    const ids = [...new Set([...before, ...taskFamily(state.data, t).map((u) => u.id)])];
    if (ids.length < 2) return state;
    return edit(state, (d) => {
        d.changes.find((x) => x.id === changeId)!.related = ids;
    });
}

function addExtraTasks(d: EventData, base: Task, extras: string[] | undefined, seq: number) {
    extras?.forEach((assignee, k) =>
        d.tasks.push({
            ...base,
            id: `${base.id}-${seq}-${k + 1}`,
            // 같은 업무를 한 건씩 더 받는 복사본: 원 업무를 기억해 큐시트·일괄 변경이 같은 업무로 묶는다
            copyOf: base.copyOf ?? base.id,
            assignee,
            firstAssignedAt: at,
            status: 'todo',
            ack: false,
            changed: '새로 배정된 업무',
            checklist: base.steps.map((text) => ({ text, done: false })),
            ...(base.equipment && { equipment: { ...base.equipment, records: {} } }),
            ...(base.safety && { safety: { ...base.safety, records: {} } }),
        }),
    );
}

function applyOps(d: EventData, c: Change, seq: number) {
    c.ops.forEach((o) => {
        if (o.kind === 'update') {
            const t = d.tasks.find((x) => x.id === o.taskId);
            if (!t) return;
            // 부서 이동: 새 부서의 새 묶음이 된다. 최초 자동 배정 경로는 되살리지 않는다(firstAssignedAt 유지)
            if (o.after.groupId && o.after.groupId !== t.groupId) {
                t.groupId = o.after.groupId;
                t.bundle = `${o.after.groupId}-${t.id}`;
            }
            Object.assign(t, {
                start: o.after.start,
                end: o.after.end,
                // null은 명시 미배정, undefined는 기존 담당 유지
                assignee: o.after.assignee === undefined ? t.assignee : o.after.assignee,
                firstAssignedAt: t.firstAssignedAt ?? (o.after.assignee ? at : undefined),
                ack: false,
                changed: `${o.before.start}~${o.before.end} → ${o.after.start}~${o.after.end}`,
                // 절차를 고쳐도 그대로 남은 단계의 완료 표시는 유지한다
                ...(o.after.steps && {
                    steps: o.after.steps,
                    checklist: o.after.steps.map(
                        (text) =>
                            t.checklist?.find((x) => x.text === text) ?? { text, done: false },
                    ),
                }),
            });
            addExtraTasks(d, t, o.after.extraAssignees, seq);
        } else if (o.kind === 'cancel') {
            const t = d.tasks.find((x) => x.id === o.taskId);
            if (t) Object.assign(t, { status: 'canceled', ack: false, changed: '업무 취소' });
        } else if (o.kind === 'add') {
            const id = d.tasks.some((t) => t.id === o.tempId) ? `${o.tempId}-${seq}` : o.tempId;
            d.tasks.push({
                id,
                title: o.title,
                groupId: o.groupId,
                // 승인 경로로 추가된 업무는 새 묶음이며 최초 자동 배정 경로를 되살리지 않는다(P09)
                bundle: `${o.groupId}-${id}`,
                ...(o.copyOf && { copyOf: o.copyOf }),
                // 같은 업무의 복사본은 원 업무와 같은 공연 구간(큐시트 곡/구간)에 둔다
                ...(o.copyOf && {
                    program: d.tasks.find((x) => x.id === o.copyOf)?.program,
                }),
                firstAssignedAt: o.after.assignee ? at : undefined,
                zone: o.after.zone ?? '',
                start: o.after.start,
                end: o.after.end,
                assignee: o.after.assignee ?? null,
                status: 'todo',
                ack: false,
                changed: '새로 배정된 업무',
                steps: o.steps,
                checklist: o.steps.map((text) => ({ text, done: false })),
                ...(o.equipment && { equipment: { ...o.equipment, records: {} } }),
                ...(o.safety && { safety: { ...o.safety, records: {} } }),
            });
            addExtraTasks(d, d.tasks[d.tasks.length - 1], o.after.extraAssignees, seq);
        }
    });
    // 승인된 대안이 대체하는 기존 예약을 먼저 해제하고, 새 예약만 실제 예약으로 옮긴다(미리 보기 때는 저장하지 않는다)
    if (c.releases?.length)
        d.reservations = d.reservations.filter((r) => !c.releases!.includes(r.id));
    c.reservations?.forEach((r, k) => d.reservations.push({ ...r, id: `R${seq}-${k}` }));
}
