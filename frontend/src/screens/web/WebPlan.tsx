import { Fragment, useState } from 'react';
import { useUrlState } from '../../shared/navigation/url-state';
import { useLocation, useNavigate, useParams } from 'react-router';
import { useData, useEvent, useSession, useViewer } from '../../domain/hooks';
import {
    affectedOf,
    cueSections,
    formatDelay,
    nameOf,
    programDelaySec,
    taskFamily,
    taskRoot,
} from '../../domain/selectors';
import { useDemoState } from '../../domain/store-context';
import type { Program } from '../../domain/types';
import { EventOverview, GroupGuides, PhaseTable } from '../../features/plan/PlanParts';
import { PlanTimeline } from '../../features/plan/PlanTimeline';
import { CueDialogs, CueGroupBoxes, type CueFlow } from '../../features/task/CueAssign';
import { MIME, movable } from '../../features/task/assign-utils';
import { TaskCard } from '../../features/task/TaskCard';
import { Empty, Tabs, TaskStatusChip } from '../../shared/ui/atoms';
import report from '../../shared/ui/report.module.css';
import { AppliedChanges } from './AppliedChanges';
import { PlanEditPanel } from './WebPlanDraft';
import { DemoPlanWorkspace } from '../../features/plan-workflow/DemoWorkspace';
import styles from './web.module.css';

type Tab = 'overview' | 'table' | 'changes' | 'edit';
const TABS: readonly Tab[] = ['overview', 'table', 'changes', 'edit'];

/**
 * W01 공연 계획(WEB 배치): 탭 + 2열 카드 + 곡별 큐시트.
 * 총괄은 큐시트에서 행을 부서 상자로 끌거나 '배정' 단추로 부서·사람을 배정한다(업무 조정 탭은 두지 않는다).
 * 개요·단계·안내 부품은 APP "행사" 탭과 같고, 배치와 업무표(표 vs 목록)만 다르다.
 * 계획 전체 버전·숙지 확인·자료함은 두지 않는다. 바뀐 업무는 본인 확인으로만 끝난다.
 */
export function WebPlan() {
    const navigate = useNavigate();
    const { pathname } = useLocation();
    // 업무 상세 주소(/e/tasks(/:taskId))로 들어오면 큐시트 탭에서 그 업무를 고른 채로 연다
    const { taskId } = useParams();
    const onTasks = pathname.startsWith('/e/tasks');
    // 고른 탭은 주소(?tab=)에 두어 새로고침해도 같은 탭으로 연다. 업무 주소의 기본은 큐시트다
    const [tab, setTab] = useUrlState<Tab>('tab', onTasks ? 'table' : 'overview', TABS);
    const ev = useEvent();
    // 실서버 업무 id는 UUID라 화면 코드로 보이지 않는다(#373)
    const live = !!useDemoState().live;
    const d = useData();
    const v = useViewer();
    const { me } = useSession();
    const [sel, setSel] = useState<string | null>(taskId ?? null);
    // 이미 열린 화면에서 다른 업무 주소로 옮겨 와도 그 업무를 큐시트에서 고른다
    const [seenTask, setSeenTask] = useState(taskId);
    if (taskId !== seenTask) {
        setSeenTask(taskId);
        // 업무 주소(/e/tasks/:id)는 기본 탭이 큐시트라 따로 탭을 바꾸지 않는다
        if (taskId) setSel(taskId);
    }
    // 같은 업무(여러 명에게 복사 배정된 업무)는 원 업무 바로 아래에 모아 둔다
    const order = new Map(d.tasks.map((t, k) => [t.id, k]));
    const rootOf = new Map(d.tasks.map((t) => [t.id, taskRoot(d, t)]));
    const rank = (t: (typeof d.tasks)[number]) =>
        order.get(rootOf.get(t.id) ?? t.id) ?? order.get(t.id) ?? 0;
    // 삭제(취소)된 업무는 큐시트에서 빼고, 기록은 '적용된 변경'에 남긴다
    const rows = d.tasks
        .filter((t) => t.status !== 'canceled' && v.canViewTask(t))
        .sort(
            (a, b) =>
                a.start.localeCompare(b.start) ||
                rank(a) - rank(b) ||
                (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0),
        );
    // 큐시트 곡/구간은 원 업무를 따른다: 구간 정보 없이 만들어진 예전 복사본도 원 업무 밑에 모인다
    const byId = new Map(d.tasks.map((t) => [t.id, t]));
    const cueRows = rows.map((t) => {
        const program = byId.get(rootOf.get(t.id) ?? t.id)?.program;
        return !t.program && program ? { ...t, program } : t;
    });
    const picked = rows.find((t) => t.id === sel) ?? rows[0];
    // 총괄: 큐시트 행을 오른쪽 부서 상자로 끌거나 행의 '배정' 단추로 부서·사람을 배정한다
    const [cue, setCue] = useState<CueFlow | null>(null);
    const canAssign = v.isChief && ev.state !== 'ENDED';
    const cols = canAssign ? 6 : 5;
    // 계획 수정(총괄, 실서버): 작성 중인 공연은 탭 밑에서 계획 입력 화면으로 바꿔 고친다.
    // 공개한 뒤에는 서버가 계획 초안 저장을 받지 않으므로(변경안으로 고친다) 탭을 두지 않는다.
    const { eventId } = useSession();
    // 데모 공연은 서버 제약이 없어 상태와 관계없이 고친다(같은 화면 구성, 저장은 데모 데이터에).
    const canEditPlan = v.isChief && (live ? !!eventId && ev.state === 'DRAFT' : true);
    const applied = d.changes
        .filter((c) => c.status === 'APPLIED' && (v.isChief || affectedOf(c).includes(me ?? '')))
        .reverse();
    return (
        <div className="stack">
            <Tabs<Tab>
                label="계획 보기"
                value={tab}
                onChange={(t) => {
                    if (onTasks) navigate(t === 'overview' ? '/e/plan' : `/e/plan?tab=${t}`);
                    else setTab(t);
                }}
                items={[
                    ['overview', '공연 개요'],
                    ['table', v.isChief ? '큐시트' : '내 큐시트'],
                    ['changes', '적용된 변경'],
                    // 작성 중인 공연만 계획을 직접 고친다(공개 뒤에는 큐시트의 변경안으로 고친다)
                    ...(canEditPlan ? [['edit', '계획 수정'] as [Tab, string]] : []),
                ]}
            />
            {tab === 'overview' && (
                // 보고서 형식 두 단: 왼쪽 1. 행사 개요 → 2. 소속 안내 | 오른쪽 3. 전체 흐름
                <div className={`${report.report} ${styles.planReport}`}>
                    <div className={styles.planCol}>
                        <section className={styles.planSec}>
                            <h4>1. 공연 개요</h4>
                            <EventOverview />
                        </section>
                        <section className={styles.planSec}>
                            <h4>2. 소속 안내</h4>
                            <GroupGuides />
                        </section>
                    </div>
                    <section className={styles.planSec}>
                        <h4>3. 공연 순서</h4>
                        <PhaseTable />
                    </section>
                    {/* 4. 공연 시간과 업무 시간·배정을 한 시간축에(APP 계획 탭의 준비·전환과 같은 범위) */}
                    <section className={`${styles.planSec} ${styles.planWide}`}>
                        <h4>4. {v.isChief ? '전체' : '소속'} 준비/전환 타임라인</h4>
                        <PlanTimeline />
                    </section>
                </div>
            )}
            {tab === 'table' && (
                <div className={styles.planTable}>
                    <section>
                        <table className={styles.table}>
                            <thead>
                                <tr>
                                    <th>담당</th>
                                    <th>시간</th>
                                    <th>업무</th>
                                    <th>구역</th>
                                    <th>상태</th>
                                    {canAssign && <th aria-label="배정" />}
                                </tr>
                            </thead>
                            <tbody>
                                {!rows.length && (
                                    <tr>
                                        <td colSpan={cols}>
                                            {v.isChief
                                                ? '발행된 계획에 업무가 없습니다. 초안에서 업무를 추가하세요.'
                                                : '보이는 업무가 없습니다. 배정되면 여기에 나타납니다.'}
                                        </td>
                                    </tr>
                                )}
                                {cueSections(ev, cueRows).map((sec) => (
                                    <Fragment key={sec.key}>
                                        <tr className={styles.cueHead} data-kind={sec.kind}>
                                            <th colSpan={cols} scope="colgroup">
                                                <CueHead label={sec.label} program={sec.program} />
                                            </th>
                                        </tr>
                                        {sec.tasks.map((t, k) => {
                                            // 바로 위 행과 같은 업무면 업무 이름은 첫 행에만, 시간도 같으면 첫 행에만 쓴다
                                            const prev = sec.tasks[k - 1];
                                            const sameTask =
                                                !!prev && rootOf.get(prev.id) === rootOf.get(t.id);
                                            const sameTime =
                                                sameTask &&
                                                prev.start === t.start &&
                                                prev.end === t.end;
                                            return (
                                                <tr
                                                    key={t.id}
                                                    tabIndex={0}
                                                    aria-selected={t.id === picked?.id}
                                                    draggable={canAssign && movable(t)}
                                                    data-drag={
                                                        (canAssign && movable(t)) || undefined
                                                    }
                                                    onDragStart={(e) => {
                                                        e.dataTransfer.setData(MIME, t.id);
                                                        e.dataTransfer.effectAllowed = 'move';
                                                    }}
                                                    onClick={() => setSel(t.id)}
                                                    onKeyDown={(e) => {
                                                        if (e.key === 'Enter' || e.key === ' ') {
                                                            e.preventDefault();
                                                            setSel(t.id);
                                                        }
                                                    }}
                                                >
                                                    <td>
                                                        {/* 같은 업무의 복사본은 원 업무 담당 밑에 ㄴ으로 단다 */}
                                                        {sameTask ? (
                                                            <span className={styles.copyOf}>
                                                                <span aria-hidden="true">ㄴ</span>
                                                                {nameOf(d, t.assignee)}
                                                            </span>
                                                        ) : (
                                                            nameOf(d, t.assignee)
                                                        )}
                                                    </td>
                                                    <td className="mono">
                                                        {sameTime ? (
                                                            <span className="sr-only">
                                                                {t.start}–{t.end}
                                                            </span>
                                                        ) : (
                                                            `${t.start}–${t.end}`
                                                        )}
                                                    </td>
                                                    <td>
                                                        {/* 같은 업무는 이름을 첫 행에만 쓰고, 업무 번호는 행마다 단다 */}
                                                        {sameTask ? (
                                                            <>
                                                                <span className="sr-only">
                                                                    {t.title} (같은 업무)
                                                                </span>
                                                                {!live && (
                                                                    <span className="code">
                                                                        {t.id}
                                                                    </span>
                                                                )}
                                                            </>
                                                        ) : (
                                                            <>
                                                                {t.title}{' '}
                                                                {!live && (
                                                                    <span className="code">
                                                                        {t.id}
                                                                    </span>
                                                                )}
                                                            </>
                                                        )}
                                                    </td>
                                                    <td>{t.zone}</td>
                                                    <td>
                                                        {/* 상태는 둥근 표시로, 상태마다 글씨·배경색이 다르다 */}
                                                        <span className="row nw">
                                                            <TaskStatusChip status={t.status} />
                                                            {!t.ack && (
                                                                <span className={styles.unacked}>
                                                                    (미확인)
                                                                </span>
                                                            )}
                                                        </span>
                                                    </td>
                                                    {canAssign && (
                                                        <td className={styles.cueAssignCell}>
                                                            {movable(t) && (
                                                                <button
                                                                    type="button"
                                                                    className="btn sm"
                                                                    onClick={(e) => {
                                                                        e.stopPropagation();
                                                                        // 같은 업무가 여럿이면 모두 함께 바꿀지 먼저 묻는다
                                                                        setCue({
                                                                            step:
                                                                                taskFamily(d, t)
                                                                                    .length > 1
                                                                                    ? 'family'
                                                                                    : 'assign',
                                                                            taskId: t.id,
                                                                        });
                                                                    }}
                                                                >
                                                                    배정
                                                                </button>
                                                            )}
                                                            {movable(t) && (
                                                                <button
                                                                    type="button"
                                                                    className={`btn sm ${styles.cueDelete}`}
                                                                    aria-label={`${t.title} ${nameOf(d, t.assignee)} 업무 삭제`}
                                                                    title="업무 삭제"
                                                                    onClick={(e) => {
                                                                        e.stopPropagation();
                                                                        setCue({
                                                                            step: 'delete',
                                                                            taskId: t.id,
                                                                        });
                                                                    }}
                                                                >
                                                                    X
                                                                </button>
                                                            )}
                                                        </td>
                                                    )}
                                                </tr>
                                            );
                                        })}
                                    </Fragment>
                                ))}
                            </tbody>
                        </table>
                    </section>
                    {/* 표에서 고른 업무의 상세(체크리스트·시작/완료·빠른 보고)를 오른쪽에 바로 보인다 */}
                    <aside className={styles.planDetail} aria-label="업무 상세">
                        {/* 맨 위: 부서 상자. 큐시트 행을 끌어 놓아 그 부서에 배정한다 */}
                        {canAssign && <CueGroupBoxes onFlow={setCue} />}
                        {picked ? (
                            <TaskCard key={picked.id} task={picked} />
                        ) : (
                            <Empty icon="task">보이는 업무가 없습니다</Empty>
                        )}
                    </aside>
                </div>
            )}
            {cue && <CueDialogs flow={cue} setFlow={setCue} />}
            {tab === 'changes' && <AppliedChanges changes={applied} />}
            {tab === 'edit' &&
                canEditPlan &&
                (live && eventId ? <PlanEditPanel eventId={eventId} /> : <DemoPlanWorkspace />)}
        </div>
    );
}

/** 큐시트 곡/구간 머리: 최신 승인 계획 시각과 확인된 실제 시각·차이(초 단위)를 따로 적는다 */
function CueHead({ label, program: p }: { label: string; program?: Program }) {
    if (!p) return <b>{label}</b>;
    const startDelay = programDelaySec(p, 'start');
    const endDelay = programDelaySec(p, 'end');
    return (
        <span className="row">
            <b>{label}</b>
            <span className="mono xs">
                계획 {p.start}–{p.end}
            </span>
            {startDelay != null && (
                <span className="mono xs">
                    실제 시작 {p.actualStart} ({formatDelay(startDelay)})
                </span>
            )}
            {endDelay != null && (
                <span className="mono xs">
                    실제 종료 {p.actualEnd} ({formatDelay(endDelay)})
                </span>
            )}
            {startDelay == null && endDelay == null && (
                <span className="xs muted">실제 시각 미확인</span>
            )}
        </span>
    );
}
