import {
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useRef,
    useState,
    useSyncExternalStore,
    type Dispatch,
    type ReactNode,
} from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import type { Action } from '../domain/reducer';
import type { EquipStep } from '../domain/types';
import { DispatchContext, useDemoState } from '../domain/store-context';
import { useIdentity } from '../features/identity/context';
import type { IdentitySession } from '../features/identity/session';
import { MemberDesk } from '../features/members/client';
import { responder as responderOf } from '../features/response/client';
import type { Member as ApiMember } from '../features/members/contract';
import { workflow, type Workflow } from '../features/plan-workflow/client';
import type { Plan, Task as ApiTask } from '../features/plan-workflow/contract';
import { classifyFailure } from '../shared/api/failure';
import { asUuid } from '../shared/api/contract';
import { resetClock, setClock } from '../domain/selectors';
import { LiveContext, useLive } from './context';
import { kstTime, screenDate, toScreen, type LiveEventDetail } from './convert';
import { ChatConnection } from '../features/live-chat/connection';
import { toMessages } from './convert-chat';
import { OPS_HANDLERS } from './handlers-ops';
import { WORK_HANDLERS } from './handlers-work';
import { SAFETY_HANDLERS } from './handlers-safety';
import { enterPath } from './enter-path';
import { linkSafety, type LiveSafetyLink } from './safety-link';
import { fillOps, loadOps, type OpsRaw } from './ops';
import { UNKNOWN, type Handler, type LiveRuntime } from './runtime';
import { assignBundle, emptyIfDenied, runWrite } from './write';

// 실서버 모드(#362). 실제 계정으로 고른 행사를 서버에서 읽어 디자인 화면의 저장소에 넣고,
// 화면이 보내는 쓰기 액션 중 서버와 연결된 것만 API 명령으로 바꾼다. 연결되지 않은 쓰기는 막고 알린다.
// 데모 로그인(/demo/login)과 데모 시험은 이 모듈을 거치지 않는다.

const TARGET_KEY = 'scenecrew-live-event';

/** 실서버 모드에서도 화면 안에서만 의미가 있어 그대로 통과시키는 액션 */
const LOCAL_ACTIONS = new Set<Action['type']>(['enterEvent', 'leaveEvent', 'liveChat']);

/** 새 보고가 곧 보이도록 다시 읽는 간격(SSE가 없어 짧게 다시 읽는다, #371) */
const POLL_MS = 10_000;

function readTarget(): string | null {
    try {
        return sessionStorage.getItem(TARGET_KEY);
    } catch {
        return null;
    }
}
function writeTarget(eventId: string | null) {
    try {
        if (eventId) sessionStorage.setItem(TARGET_KEY, eventId);
        else sessionStorage.removeItem(TARGET_KEY);
    } catch {
        // 저장소를 못 쓰면 새로고침 때 다시 고르면 된다.
    }
}

async function allTasks(client: Workflow): Promise<ApiTask[]> {
    const items: ApiTask[] = [];
    const seen = new Set<string>();
    let cursor: string | undefined;
    do {
        const page = await client.tasks(cursor);
        items.push(...page.items);
        cursor = page.nextCursor ?? undefined;
        if (cursor && seen.has(cursor)) break;
        if (cursor) seen.add(cursor);
    } while (cursor);
    return items;
}

/** 행사 한 벌을 읽는다. 계획이 아직 없거나 인원 목록 권한이 없으면 그 부분만 비운다. */
async function loadSnapshot(
    session: IdentitySession,
    eventId: string,
    recoveryIssues: ReadonlySet<string>,
    analysisJobs: ReadonlyMap<string, string> = new Map(),
    equipmentRecords: ReadonlyMap<string, Partial<Record<EquipStep, string>>> = new Map(),
    previous?: OpsRaw,
) {
    const summary = await session.event(eventId);
    const scope = session.scopes.participation(eventId);
    if (!scope || !summary.myParticipation.active)
        throw new Error('현재 참여 중인 공연이 아닙니다.');
    const client = workflow(session, scope);
    let event: LiveEventDetail;
    let plan: Plan | null = null;
    try {
        const read = await client.read();
        event = read.event as unknown as LiveEventDetail;
        plan = read.plan;
    } catch (error) {
        if (classifyFailure(error).kind === 'access-denied') throw error;
        event = (await client.detail()) as unknown as LiveEventDetail;
    }
    const [tasks, members] = await Promise.all([
        allTasks(client).catch(emptyIfDenied<ApiTask>),
        new MemberDesk(session, scope)
            .members()
            .then((page) => page.items)
            .catch(emptyIfDenied<ApiMember>),
    ]);
    const me = summary.myParticipation;
    const ops = await loadOps(
        session,
        scope,
        me.eventRole === 'CHIEF',
        recoveryIssues,
        analysisJobs,
        { tasks, equipmentRecords, previous },
    );
    // 팀원은 인원 목록을 못 볼 수 있다. 화면의 '나'가 비지 않도록 본인 참여 정보로 채운다.
    const roster = members.some((m) => m.memberId === me.memberId)
        ? members
        : [
              ...members,
              {
                  memberId: me.memberId,
                  displayName: session.snapshot().account?.displayName ?? '나',
                  workGroupId: me.workGroupId,
                  eventRole: me.eventRole,
                  active: true,
                  available: true,
                  isReserve: false,
                  participationRevision: me.participationRevision,
              },
          ];
    return {
        client,
        snapshot: { event, plan, tasks, members: roster },
        memberId: me.memberId,
        chief: me.eventRole === 'CHIEF',
        ops,
    };
}

export function LiveBridge({ children }: { children: ReactNode }) {
    const session = useIdentity();
    const snapshot = useSyncExternalStore(session.subscribe, session.snapshot);
    const account = snapshot.account;
    const state = useDemoState();
    const rawDispatch = useContext(DispatchContext)!;
    const runtime = useRef<LiveRuntime | null>(null);
    const [status, setStatus] = useState('');
    // 디자인 안전 화면이 쓰는 서버 연결(#372). 총괄이 안전 지도를 읽었을 때만 있다
    const [safety, setSafety] = useState<LiveSafetyLink | null>(null);
    // 디자인 화면이 직접 부르는 API의 서버 연결(#396 내 연결). 같은 공연을 다시 읽으면 같은 연결을 그대로 쓴다
    const [client, setClient] = useState<Workflow | null>(null);
    // 새로고침 뒤 다시 읽기에 실패했다. 이때만 이동 보호가 로그인으로 보내게 한다.
    const [restoreFailed, setRestoreFailed] = useState(false);

    // 고른 행사. 저장소를 못 쓰는 환경에서도 다시 읽기·주기 읽기가 이어지도록 메모리에도 둔다.
    // (새로고침 뒤 복구는 저장소에 남은 값으로만 할 수 있다.)
    const target = useRef<string | null>(null);
    const setTarget = useCallback((eventId: string | null) => {
        target.current = eventId;
        writeTarget(eventId);
    }, []);
    const currentTarget = useCallback(() => target.current ?? readTarget(), []);
    // 불러오기 일련번호. 늦게 끝난 앞 불러오기(주기 읽기 등)가 뒤 결과를 덮지 않게 가장 최근 것만 반영한다.
    const loads = useRef(0);
    // 이 화면에서 복구 계산을 돌린 이슈(장비 이상이 아니어도 다시 읽을 때 결과를 함께 읽는다)
    const recoveryIssues = useRef(new Set<string>());
    // 이 화면이 접수한 보고 분석 작업(이슈 id → 작업 id). 다시 읽을 때 진행·실패 상태를 함께 읽는다(#358).
    const analysisJobs = useRef(new Map<string, string>());
    // 실서버 채팅 연결(#373). 들어갈 수 있는 채널이 바뀔 때만 새로 만든다.
    const chat = useRef<ChatConnection | null>(null);
    // 이 화면에서 남긴 장비 수행 기록(#373). 서버에 읽기 API가 없어 화면 실행 동안만 기억한다.
    const equipmentRecords = useRef(new Map<string, Partial<Record<EquipStep, string>>>());
    const equipmentProgress = useRef(new Map<string, Set<string>>());

    const load = useCallback(
        async (eventId: string) => {
            const n = ++loads.current;
            const loaded = await loadSnapshot(
                session,
                eventId,
                recoveryIssues.current,
                analysisJobs.current,
                equipmentRecords.current,
                // 같은 공연을 다시 읽을 때만 직전 원본을 넘긴다(일시 오류 때 알림·채널 유지)
                runtime.current?.client.scope.eventId === eventId ? runtime.current.ops : undefined,
            );
            if (n !== loads.current) return;
            const { client, snapshot, memberId, chief, ops } = loaded;
            // '지금 시각'은 한국 현재 시각, 화면 기준 날짜는 행사 날짜다(데모는 둘 다 고정값).
            const now = new Date().toISOString();
            const day = screenDate(snapshot.event, now);
            setClock(kstTime(now), day);
            const { info, data } = toScreen(snapshot, day);
            const chiefId = data.members.find((m) => m.role === 'CHIEF')?.id;
            const opIndex = fillOps(data, ops, snapshot.plan, snapshot.tasks, chiefId);
            runtime.current = {
                session,
                client,
                eventRevision: snapshot.event.revision,
                chief,
                plan: snapshot.plan,
                tasks: snapshot.tasks,
                ops,
                opIndex,
                recoveryIssues: recoveryIssues.current,
                analysisJobs: analysisJobs.current,
                equipmentRecords: equipmentRecords.current,
                equipmentProgress: equipmentProgress.current,
                chat,
            };
            setClient((prev) =>
                prev && prev.scope.eventId === client.scope.eventId && prev.scope === client.scope
                    ? prev
                    : client,
            );
            const safe = chief ? ops.safety : null;
            setSafety((prev) =>
                safe
                    ? linkSafety(prev, client, {
                          map: safe.map,
                          cameras: safe.cameras,
                          zones: (snapshot.plan?.zones ?? []).map((z) => ({
                              id: z.id,
                              name: z.name,
                          })),
                          groups: (snapshot.plan?.workGroups ?? []).map((g) => ({
                              id: g.id,
                              name: g.name,
                          })),
                          members: snapshot.members,
                          eventRevision: snapshot.event.revision,
                      })
                    : null,
            );
            rawDispatch({ type: 'liveLoad', me: memberId, eventId, events: [info], data });
        },
        [session, rawDispatch],
    );

    const open = useCallback(
        async (eventId: string) => {
            asUuid(eventId, 'eventId');
            setTarget(eventId);
            setStatus('');
            await load(eventId);
        },
        [load, setTarget],
    );

    const reload = useCallback(async () => {
        const eventId = currentTarget();
        if (eventId) await load(eventId);
    }, [load, currentTarget]);

    // 새로고침·앱 재시작 뒤 계정이 복구되면 고르던 행사를 다시 연다.
    const restored = useRef(false);
    useEffect(() => {
        if (!account || restored.current || state.live) return;
        const eventId = currentTarget();
        if (!eventId) return;
        restored.current = true;
        load(eventId).catch(() => {
            setTarget(null);
            setRestoreFailed(true);
        });
    }, [account, state.live, load, setTarget, currentTarget]);

    // 실제 계정이 로그아웃되면 실데이터를 지운다.
    useEffect(() => {
        if (account || !state.live) return;
        runtime.current = null;
        setTarget(null);
        resetClock();
        rawDispatch({ type: 'logout' });
    }, [account, state.live, rawDispatch, setTarget]);

    // 채팅(#373): 채널(방)이 있으면 연결을 열고, 받은 메시지·보내는 중 상태를 화면 상태에 넣는다.
    const roomKey = state.live ? state.data.rooms.map((r) => r.id).join(',') : '';
    const chatEvent = state.live ? state.eventId : null;
    const chatMe = state.me ?? '';
    useEffect(() => {
        if (!roomKey || !chatEvent) return;
        const scope = session.scopes.participation(chatEvent);
        if (!scope) return;
        const channels = roomKey.split(',').map((k) => (k === 'ALL' ? null : k));
        const connection = new ChatConnection(session, scope, channels);
        chat.current = connection;
        const off = connection.subscribe((snap) =>
            rawDispatch({ type: 'liveChat', messages: toMessages(snap, chatMe) }),
        );
        connection.start();
        return () => {
            off();
            connection.stop();
            if (chat.current === connection) chat.current = null;
        };
    }, [roomKey, chatEvent, chatMe, session, rawDispatch]);

    // 보고·변경안이 새로고침 없이 보이도록 화면이 보이는 동안 주기적으로 다시 읽는다.
    // 쓰기를 처리하는 중에는 건너뛴다(끝나면 어차피 다시 읽는다).
    const busy = useRef(false);
    useEffect(() => {
        if (!state.live) return;
        const timer = window.setInterval(() => {
            if (busy.current || document.visibilityState !== 'visible') return;
            reload().catch(() => undefined);
        }, POLL_MS);
        return () => window.clearInterval(timer);
    }, [state.live, reload]);

    // 앱으로 돌아오거나 탭이 다시 보이면 바로 다시 읽는다(IA04, #307). 푸시를 받은 뒤 돌아와도 최신이 보인다.
    useEffect(() => {
        if (!state.live) return;
        const back = () => {
            if (busy.current || document.visibilityState !== 'visible') return;
            reload().catch(() => undefined);
        };
        document.addEventListener('visibilitychange', back);
        return () => document.removeEventListener('visibilitychange', back);
    }, [state.live, reload]);

    const dispatch = useMemo<Dispatch<Action>>(() => {
        if (!state.live) return rawDispatch;
        return (action: Action) => {
            if (LOCAL_ACTIONS.has(action.type)) return rawDispatch(action);
            if (action.type === 'logout') {
                void session.logout().finally(() => {
                    runtime.current = null;
                    setSafety(null);
                    setClient(null);
                    setTarget(null);
                    resetClock();
                    rawDispatch({ type: 'logout' });
                });
                return;
            }
            const current = runtime.current;
            const handler = current && HANDLERS[action.type];
            if (!current || !handler) {
                setStatus(
                    '이 기능은 아직 실서버와 연결되지 않았습니다. 화면 값은 바뀌지 않았습니다.',
                );
                return;
            }
            // 알림 읽음(075)은 알림을 열 때 화면이 함께 보내는 배경 쓰기다. 다시 보내도 같고 업무를 바꾸지 않으므로
            // 쓰기 잠금을 잡지 않는다. 잡으면 곧바로 누른 변경 확인(077)이 '처리 중'으로 막힌다(#373).
            // 채팅 보내기는 WebSocket으로 간다. 저장 확인은 연결이 받은 메시지로 보이므로 쓰기 잠금·다시 읽기를 타지 않는다.
            // 잠금을 타면 연달아 보낸 두 번째 메시지가 '처리 중'으로 막혀 사라진다(#373 리뷰).
            if (action.type === 'send') {
                void handler(current, action, state)
                    .then((text) => {
                        if (text) setStatus(text);
                    })
                    .catch(() => setStatus(UNKNOWN));
                return;
            }
            if (action.type === 'readNotice') {
                // 이미 읽은 알림이면 아무것도 보내지 않고 다시 읽지도 않는다
                if (state.data.notices.find((x) => x.id === action.id)?.read) return;
                void handler(current, action, state)
                    .then((text) => {
                        if (text) setStatus(text);
                    })
                    .catch(() => undefined)
                    .finally(() => void reload().catch(() => undefined));
                return;
            }
            // 쓰기는 한 번에 하나만 한다. 앞 쓰기가 끝나기 전의 새 쓰기는 보내지 않고 알린다.
            if (busy.current) {
                setStatus('앞서 보낸 요청을 처리하고 있습니다. 끝난 뒤 다시 시도해주세요.');
                return;
            }
            setStatus('서버에 반영하고 있습니다.');
            busy.current = true;
            // 쓰기 전에 시작한 주기 읽기 결과는 버린다(쓰기 뒤에 다시 읽는다).
            loads.current++;
            runWrite(() => handler(current, action, state), reload)
                .then(setStatus)
                .finally(() => {
                    busy.current = false;
                });
        };
    }, [state, rawDispatch, session, reload, setTarget]);

    // 저장해 둔 공연이 있고 계정 복구가 끝나지 않았거나 계정이 있으면, 다시 읽을 때까지 '복구 중'이다.
    const restoring =
        !state.live &&
        !restoreFailed &&
        readTarget() !== null &&
        (!snapshot.ready || account !== null);
    const value = useMemo(
        // 로그아웃 등으로 실서버 모드를 벗어나면 안전 연결도 넘기지 않는다
        () => ({
            open,
            reload,
            status,
            restoring,
            safety: state.live ? safety : null,
            client: state.live ? client : null,
        }),
        [open, reload, status, restoring, safety, client, state.live],
    );
    return (
        <LiveContext.Provider value={value}>
            <DispatchContext.Provider value={dispatch}>
                {children}
                {state.live && status && (
                    <p role="status" className="live-status" style={STATUS_STYLE}>
                        {status}
                    </p>
                )}
            </DispatchContext.Provider>
        </LiveContext.Provider>
    );
}

const STATUS_STYLE = {
    position: 'fixed',
    left: '50%',
    bottom: '16px',
    transform: 'translateX(-50%)',
    maxWidth: 'calc(100vw - 32px)',
    margin: 0,
    padding: '10px 16px',
    borderRadius: '8px',
    background: '#1f2937',
    color: '#ffffff',
    fontSize: '14px',
    zIndex: 1000,
} as const;

/** 변경안 결과를 끝까지 받지 못했을 때의 안내 */
function changeText(result: { kind: string }, step: string) {
    if (result.kind === 'conflict')
        return `${step} 중 그사이 다른 변경이 있어 멈췄습니다. 최신 내용으로 다시 불러왔습니다.`;
    if (result.kind === 'rejected')
        return `${step}이 거절됐습니다. 같은 부서의 활동 가능한 팀원인지, 시간이 겹치지 않는지 확인해주세요.`;
    return UNKNOWN;
}

/**
 * 서버와 연결된 쓰기 액션. 각 화면 묶음(#362·#371·#372·#373)이 연결할 때마다 여기에 더한다.
 * 결과는 화면이 직접 바꾸지 않고 서버에서 다시 읽어 그린다.
 */
const HANDLERS: Partial<Record<Action['type'], Handler>> = {
    ...OPS_HANDLERS,
    ...WORK_HANDLERS,
    ...SAFETY_HANDLERS,
    async assignManual({ client, eventRevision }, action) {
        if (action.type !== 'assignManual') throw new Error('잘못된 액션');
        return (await assignBundle(client, action.bundle, action.memberId, eventRevision)).text;
    },

    /**
     * W01 업무 조정(#354, V9-D47). 아직 처음 배정되지 않은 업무는 묶음 전체를 045로 처음 배정하고,
     * 이미 배정된 업무의 담당 변경·해제는 변경안(ASSIGN_TASK)을 만들어 미리보기 뒤 총괄 승인으로 적용한다.
     * 계약에 업무의 부서를 바꾸는 연산이 없어 다른 부서로 옮기기는 하지 않는다.
     */
    async reassignTask({ session, client, eventRevision }, action, state) {
        if (action.type !== 'reassignTask') throw new Error('잘못된 액션');
        const task = state.data.tasks.find((t) => t.id === action.taskId);
        if (!task) return '업무를 찾지 못했습니다. 최신 내용을 다시 불러왔습니다.';
        if (action.groupId !== task.groupId)
            return '다른 부서로 업무를 옮기는 것은 지원하지 않습니다. 같은 부서 안에서 담당만 바꿀 수 있습니다.';
        if (action.assignee === task.assignee) return '이미 그 담당입니다. 바뀐 것이 없습니다.';
        if (!task.assignee && !task.firstAssignedAt) {
            if (!action.assignee) return '아직 배정되지 않은 업무입니다. 바뀐 것이 없습니다.';
            const { ok, text } = await assignBundle(
                client,
                task.bundle,
                action.assignee,
                eventRevision,
            );
            const size = state.data.tasks.filter((t) => t.bundle === task.bundle).length;
            return ok && size > 1 ? `${text} 같은 묶음의 업무 ${size}건이 함께 배정됩니다.` : text;
        }
        const desk = responderOf(session, client.scope);
        const created = await desk.createManual({
            target: `assign:${task.id}:${action.assignee ?? 'none'}`,
            operations: [
                {
                    type: 'ASSIGN_TASK',
                    payload: {
                        taskId: task.id,
                        fromMemberId: task.assignee,
                        toMemberId: action.assignee,
                    },
                },
            ],
            rationale: '업무 조정에서 총괄이 담당을 바꿈',
            expectedEventRevision: eventRevision,
        });
        if (created.kind !== 'done') return changeText(created, '변경안 만들기');
        const set = created.data;
        const previewed = await desk.preview(set.id, set.revision, set.eventRevision);
        if (previewed.kind !== 'done') return changeText(previewed, '미리보기');
        const ready = previewed.data;
        if (!ready.previewHash)
            return '미리보기 결과를 받지 못했습니다. 변경 승인 화면에서 확인해주세요.';
        const applied = await desk.apply(ready.id, {
            expectedChangeSetRevision: ready.revision,
            previewHash: ready.previewHash,
            expectedEventRevision: ready.eventRevision,
        });
        if (applied.kind !== 'done') return changeText(applied, '승인');
        return action.assignee
            ? '담당을 바꿨습니다. 새 담당자에게 변경 확인 알림이 갑니다.'
            : '담당을 비웠습니다. 이전 담당자에게 변경 확인 알림이 갑니다.';
    },
};

/** /live/events/:eventId — 실제 계정으로 고른 행사를 디자인 화면으로 연다. */
export function LiveEnter() {
    const { eventId = '' } = useParams();
    const [params] = useSearchParams();
    const next = enterPath(params.get('next'), params.get('n'), params.get('tab'));
    const { open } = useLive();
    const navigate = useNavigate();
    const [error, setError] = useState('');
    useEffect(() => {
        let alive = true;
        open(eventId)
            .then(() => {
                if (alive) navigate(next, { replace: true });
            })
            .catch(() => {
                if (alive) setError('공연을 열지 못했습니다. 참여 중인 공연인지 확인해주세요.');
            });
        return () => {
            alive = false;
        };
    }, [eventId, open, navigate, next]);
    return (
        <main className="stack" style={{ padding: '24px' }}>
            <p role="status">{error || '공연을 불러오고 있습니다.'}</p>
            {error && <Link to="/account/events">내 공연 목록</Link>}
        </main>
    );
}
