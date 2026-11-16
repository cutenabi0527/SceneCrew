import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import {
    useData,
    useEvent,
    useMe,
    useMyTasks,
    useResponseQueues,
    useRooms,
    useSession,
} from '../../domain/hooks';
import { NOW_MIN, fmtMin, toMin } from '../../domain/selectors';
import { EVENT_DATE, NOW } from '../../domain/seed';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { Member, Task } from '../../domain/types';
import { useShell } from '../../layouts/shell-actions';
import { Icon } from '../../shared/ui/Icon';
import { Empty, Note } from '../../shared/ui/atoms';
import { ReportIssueList } from './AppIssues';
import { M3Switch } from './M3Switch';
import { AppTaskActions, LateChip, ProgressBar, StatusChip } from './AppTaskParts';
import { dateLabel, isClosed } from './app-task-utils';
import s from './appTask.module.css';

type Tab = 'today' | 'all' | 'reports';
const dayOf = (t: Task) => t.date ?? EVENT_DATE;
/** 근무 시간 '17:00–21:30'을 [시작, 끝] 분으로. 형식이 다르면 null */
const shiftOf = (m: Member | undefined): [number, number] | null => {
    const hit = m?.avail?.match(/(\d{1,2}:\d{2})\s*[–~-]\s*(\d{1,2}:\d{2})/);
    return hit ? [toMin(hit[1]), toMin(hit[2])] : null;
};

/**
 * 지금 카드: 진행 중 업무(없으면 지금 시간대의 대기 업무, 그것도 없으면 다음 업무)와 남은 시간·진행 막대,
 * 완료/시작·빠른 보고 버튼, 그다음 업무를 한 카드에 둔다. 제목을 누르면 업무 상세로 간다.
 */
function NowCard({ open, member }: { open: Task[]; member: Member | undefined }) {
    const shell = useShell();
    const ev = useEvent();
    const shift = shiftOf(member);
    const workEnd = shift ? `근무 ${fmtMin(shift[1])}까지` : null;
    const current =
        open.find((t) => t.status === 'doing') ??
        open.find((t) => toMin(t.start) <= NOW_MIN && NOW_MIN < toMin(t.end));
    const task = current ?? open[0];
    const stamp = `${dateLabel(EVENT_DATE)} · ${NOW}`;
    if (!task)
        return (
            <section aria-label="지금 할 일" className={s.card}>
                <div className={s.row}>
                    <span className={s.label}>지금</span>
                    <span className={s.sub}>{stamp}</span>
                </div>
                <div className={s.nowTitle}>
                    <span className={s.title}>오늘 남은 업무 없음</span>
                    {workEnd && <span className={s.meta}>{workEnd}</span>}
                </div>
            </section>
        );
    const after = open.filter((t) => t !== task && t.start >= task.start)[0];
    const program = ev.programs.find((p) => p.id === task.program);
    // 진행 중이면 끝까지 남은 분, 시작 전이면 시작까지 남은 분
    const doing = task.status === 'doing';
    const left = doing ? toMin(task.end) - NOW_MIN : toMin(task.start) - NOW_MIN;
    const leftLabel = doing ? (left >= 0 ? '남음' : '지남') : left >= 0 ? '뒤 시작' : '늦음';
    return (
        <section aria-label="지금 할 일" className={`${s.card} ${current ? s.now : s.standby}`}>
            <div className={s.row}>
                <div className={s.chips}>
                    <span className={s.label}>{current ? '지금' : '곧 시작'}</span>
                    <StatusChip task={task} />
                    <LateChip task={task} />
                </div>
                <span className={s.sub}>{stamp}</span>
            </div>
            <div className={s.nowMain}>
                <button
                    type="button"
                    className={s.titleBtn}
                    onClick={() => shell.openTask(task.id)}
                >
                    <span className={s.title}>{task.title}</span>
                    <span className={s.meta}>
                        {task.zone}
                        {program && ` · ${program.name}`}
                    </span>
                </button>
                <div className={s.left}>
                    <b>
                        {Math.abs(left)}
                        <small> 분</small>
                    </b>
                    <span>{leftLabel}</span>
                </div>
            </div>
            {doing ? (
                <div className={s.progress}>
                    <ProgressBar task={task} />
                    <div className={s.ends}>
                        <span>{task.start}</span>
                        <span>{task.end}</span>
                    </div>
                </div>
            ) : (
                <span className={s.sub}>
                    {task.start}–{task.end}
                </span>
            )}
            {task.changed && !task.ack && <div className={s.changed}>변경됨 · {task.changed}</div>}
            <AppTaskActions task={task} />
            <div className={s.next}>
                <span className={s.label}>{current ? '다음' : '그다음'}</span>
                <span>
                    {after
                        ? `${after.start} ${after.title}`
                        : ['남은 업무 없음', workEnd].filter(Boolean).join(' · ')}
                </span>
            </div>
        </section>
    );
}

/** 근무 시간 가로 타임라인: 오늘 내 업무 막대와 지금 시각 선. 근무 시간 밖 업무가 있으면 그만큼 넓힌다 */
function Timeline({ tasks, member }: { tasks: Task[]; member: Member | undefined }) {
    const shift = shiftOf(member);
    const from = Math.min(shift?.[0] ?? Infinity, ...tasks.map((t) => toMin(t.start)));
    const to = Math.max(shift?.[1] ?? -Infinity, ...tasks.map((t) => toMin(t.end)));
    if (!(to > from)) return null;
    const pct = (v: number) => `${((v - from) / (to - from)) * 100}%`;
    // 양 끝 시각을 적고, 그 사이 정각은 끝과 너무 가깝지 않을 때만 적는다
    const step = to - from > 360 ? 120 : 60;
    const ticks = [from];
    for (let h = Math.ceil(from / step) * step; h < to; h += step)
        if (h - from >= 40 && to - h >= 40) ticks.push(h);
    ticks.push(to);
    const showNow = NOW_MIN >= from && NOW_MIN <= to;
    return (
        <section aria-label="오늘 내 근무 시간표" className={s.timeline}>
            <div className={s.track}>
                {tasks.map((t) => (
                    <div
                        key={t.id}
                        className={s.block}
                        data-tone={t.status}
                        title={`${t.start}–${t.end} ${t.title}`}
                        style={{
                            left: pct(toMin(t.start)),
                            width: `${((toMin(t.end) - toMin(t.start)) / (to - from)) * 100}%`,
                        }}
                    />
                ))}
                {showNow && (
                    <div aria-hidden="true" className={s.nowLine} style={{ left: pct(NOW_MIN) }} />
                )}
            </div>
            <div className={s.ticks} aria-hidden="true">
                {ticks.map((v) => (
                    <span key={v} style={{ left: pct(v) }}>
                        {fmtMin(v)}
                    </span>
                ))}
            </div>
        </section>
    );
}

/** 업무 목록 카드: 누르면 업무 상세. 끝난 업무는 흐린 바탕에 '완료 · 체크 n/n' */
function TaskItem({ task: t }: { task: Task }) {
    const shell = useShell();
    const live = !!useDemoState().live;
    return (
        <article>
            <button
                type="button"
                className={s.item}
                data-tone={t.status}
                onClick={() => shell.openTask(t.id)}
            >
                <span className={s.itemText}>
                    <span className={s.sub}>
                        {t.start}–{t.end} · {t.zone}
                    </span>
                    <span className={s.itemTitle}>
                        {t.title}
                        {/* 실서버 업무 id는 UUID라 보이지 않는다(#373) */}
                        {!live && <small> {t.id}</small>}
                    </span>
                    {t.changed && !t.ack && <span className={s.sub}>변경됨 · {t.changed}</span>}
                </span>
                <span className={s.itemChips}>
                    <StatusChip task={t} withChecks />
                    <LateChip task={t} />
                </span>
            </button>
        </article>
    );
}

/** 예비 대기: 대기 장소·투입 가능 스위치(본인 가용) → 가능 업무·근무 시간 → 그룹 안내 → 그룹 채팅 */
function ReserveCard({ member: m }: { member: Member }) {
    const ev = useEvent();
    const dispatch = useDispatch();
    const shell = useShell();
    const rooms = useRooms();
    const { group } = useMe();
    // 실서버에는 가능 업무·대기 장소 API가 없다(#373, 기획에 없음)
    const live = !!useDemoState().live;
    const wait = live ? undefined : m.wait;
    const skills = live ? undefined : m.skills;
    const room = rooms.find((r) => r.room.kind === 'GROUP' && r.room.groupId === m.groupId)?.room;
    const title = !m.available ? '지금 투입 불가' : wait ? `${wait}에서 대기` : '투입 대기 중';
    return (
        <>
            <section
                aria-label="지금 상태"
                className={`${s.card} ${m.available ? s.standby : s.off}`}
            >
                <div className={s.row}>
                    <div className={s.chips}>
                        <span className={s.label}>지금</span>
                        <span className={s.chip} data-tone={m.available ? 'standby' : undefined}>
                            {m.available ? '예비 대기' : '투입 불가'}
                        </span>
                    </div>
                    <span className={s.sub}>
                        {dateLabel(EVENT_DATE)} · {NOW}
                    </span>
                </div>
                <div className={s.standbyText}>
                    <span className={s.title}>{title}</span>
                    <span className={s.meta}>
                        {m.available
                            ? '투입되면 알림과 함께 여기에 업무가 나타납니다'
                            : '다시 켜면 총괄에게 투입 가능으로 보입니다'}
                    </span>
                </div>
                {ev.state === 'LIVE' && (
                    <div className={s.switchRow}>
                        <label id="reserve-available-label">
                            <b>지금 투입 가능</b>
                            <span id="reserve-available-desc">
                                끄면 총괄에게 투입 불가로 보입니다
                            </span>
                        </label>
                        <M3Switch
                            checked={m.available}
                            labelledBy="reserve-available-label"
                            describedBy="reserve-available-desc"
                            onChange={(available) => dispatch({ type: 'setAvailable', available })}
                        />
                    </div>
                )}
            </section>
            {(skills || m.avail || (!m.available && wait)) && (
                <section aria-label="내 예비 정보" className={s.infoCard}>
                    {skills && (
                        <div className={s.infoRow}>
                            <span>가능 업무</span>
                            <span>{skills}</span>
                        </div>
                    )}
                    {!m.available && wait && (
                        <div className={s.infoRow}>
                            <span>대기 장소</span>
                            <span>{wait}</span>
                        </div>
                    )}
                    {m.avail && (
                        <div className={s.infoRow}>
                            <span>근무 시간</span>
                            <span>{m.avail}</span>
                        </div>
                    )}
                </section>
            )}
            {group?.guide && (
                <section aria-labelledby="reserve-guide" className={s.guide}>
                    <b id="reserve-guide">{group.name} 안내</b>
                    <span>{group.guide}</span>
                </section>
            )}
            {room && (
                <button type="button" className={s.linkRow} onClick={() => shell.openChat(room.id)}>
                    {room.name} 채팅 열기
                    <Icon name="chev" />
                </button>
            )}
        </>
    );
}

/**
 * M01 내 업무(APP 첫 탭): 확인할 변경 → 지금 카드(남은 시간·완료/빠른 보고·다음 업무) → 근무 시간 타임라인 →
 * 오늘(진행 중 먼저, 끝난 업무는 아래에 흐리게) · 전체(날짜별) · 진행 보고(내가 보고했거나 내 업무와 연결된 이슈).
 * 업무가 없는 예비 인원은 지금 카드 대신 대기 상태와 투입 가능 스위치를 본다.
 * 탭은 주소(?tab=)에 남겨 보고 제출 뒤나 이슈 상세에서 돌아와도 같은 탭을 연다.
 */
export function AppToday() {
    const d = useData();
    const { me } = useSession();
    const { all, open } = useMyTasks();
    const { member } = useMe();
    const navigate = useNavigate();
    const q = useResponseQueues();
    const [params, setParams] = useSearchParams();
    const tab = params.get('tab');
    const f: Tab = tab === 'all' || tab === 'reports' ? tab : 'today';
    // 탭을 누르면 탭 줄이 화면 맨 위에 오도록 내려 준다. 아래 내용이 짧아도 올라갈 수 있게
    // 탭 영역에 화면 높이만큼의 최소 높이를 준다(누른 뒤에만)
    const area = useRef<HTMLElement>(null);
    const [minH, setMinH] = useState<number | undefined>(undefined);
    const [jump, setJump] = useState(0);
    const setF = (t: Tab) => {
        setParams(t === 'today' ? {} : { tab: t }, { replace: true });
        const main = area.current?.closest('main');
        if (!main) return;
        const pad = parseFloat(getComputedStyle(main).paddingBottom) || 0;
        setMinH(main.clientHeight - pad);
        setJump((n) => n + 1);
    };
    // 새 탭 내용과 최소 높이가 그려진 뒤에 내린다
    useEffect(() => {
        if (jump) area.current?.scrollIntoView({ block: 'start', behavior: 'smooth' });
    }, [jump]);
    const reportsOpen = [...q.news, ...q.resolve];
    const toAck = d.notices.filter((n) => n.to === me && !n.ack);
    // tasksOf가 시작 시각순으로 정렬해 준다
    const today = all.filter((t) => dayOf(t) === EVENT_DATE);
    const todayOpen = today.filter((t) => !isClosed(t));
    const todayClosed = today.filter(isClosed);
    const days = [...new Set(all.map(dayOf))].sort();
    // 맡은 업무가 없는 예비 인원은 지금 카드 대신 대기 상태를 본다
    const standby = !!member?.isReserve && open.length === 0;
    const tabs: [Tab, string][] = [
        ['today', `오늘 ${today.length}`],
        ['all', `전체 ${all.length}`],
        ['reports', `진행 보고 ${reportsOpen.length}`],
    ];

    return (
        <div className={s.page}>
            {toAck.map((n) => (
                <Note key={n.id} kind="accent" icon={n.kind === 'deploy' ? 'bolt' : 'bell'}>
                    <b>{n.kind === 'deploy' ? '투입되었습니다' : '내 업무가 바뀌었습니다'}</b>
                    <div className="small">{n.text}</div>
                    <button
                        type="button"
                        className="btn sm primary"
                        style={{ marginTop: 6 }}
                        onClick={() => navigate(`/e/ack/${n.id}`)}
                    >
                        {n.kind === 'deploy' ? '투입 내용 확인' : '변경사항 확인'}
                    </button>
                </Note>
            ))}
            {standby && member && <ReserveCard member={member} />}
            {!standby && today.length > 0 && (
                <>
                    <NowCard open={todayOpen} member={member} />
                    <Timeline tasks={today} member={member} />
                </>
            )}
            {/* 한 번도 업무를 맡지 않은 예비 인원은 대기 화면만 본다(보고는 이슈 탭에서 본다) */}
            {!(standby && !all.length && !reportsOpen.length) && (
                <section
                    ref={area}
                    className={`${s.page} ${s.tabArea}`}
                    style={minH ? { minHeight: minH } : undefined}
                >
                    <div className={s.tabs} role="tablist" aria-label="업무 범위">
                        {tabs.map(([k, label]) => (
                            <button
                                key={k}
                                type="button"
                                className={s.tab}
                                role="tab"
                                aria-selected={f === k}
                                onClick={() => setF(k)}
                            >
                                {label}
                            </button>
                        ))}
                    </div>
                    {f !== 'reports' && !all.length && (
                        <Empty icon="task">
                            배정된 업무가 없습니다. 배정되면 알림과 함께 여기에 나타납니다.
                        </Empty>
                    )}
                    {f === 'today' && all.length > 0 && (
                        <>
                            {!today.length && (
                                <Empty icon="task">오늘 배정된 업무가 없습니다.</Empty>
                            )}
                            {today.length > 0 && (
                                <div className={s.list}>
                                    <span className={s.dayLabel}>
                                        {dateLabel(EVENT_DATE, true)}
                                    </span>
                                    {[...todayOpen, ...todayClosed].map((t) => (
                                        <TaskItem key={t.id} task={t} />
                                    ))}
                                </div>
                            )}
                        </>
                    )}
                    {f === 'all' &&
                        days.map((day) => {
                            const ts = all.filter((t) => dayOf(t) === day);
                            return (
                                <div key={day} className={s.list}>
                                    <span className={s.dayLabel}>
                                        {dateLabel(day, true)} · {ts.length}건
                                    </span>
                                    {ts.map((t) => (
                                        <TaskItem key={t.id} task={t} />
                                    ))}
                                </div>
                            );
                        })}
                    {f === 'reports' && <ReportIssueList />}
                </section>
            )}
        </div>
    );
}
