import { type ReactNode, useEffect, useState } from 'react';
import { EVENT_DATE, NOW } from '../../domain/seed';
import { TASK_STATUS_LABEL, isLate, toMin } from '../../domain/selectors';
import type { Task } from '../../domain/types';
import { useShell } from '../../layouts/shell-actions';
import { useUrlState } from '../../shared/navigation/url-state';
import { TaskStatusChip } from '../../shared/ui/atoms';
import { DateStrip } from './DateStrip';
import styles from './task.module.css';

/** 그림 전체 폭. 시계는 가운데, 업무 상자는 양옆 칸에 둔다. */
const W = 940;
/** 좁은 화면(APP) 그림 폭: 문자판만 그리고 업무 상자는 시계 아래 목록으로 둔다. */
const R = 128;
const W_COMPACT = 2 * R + 64;
/** 숫자 위치 반지름 */
const NUM_R = R - 22;
const BOX_W = 300;
const BOX_H = 44;
const GAP = 12;
/** 업무 색: 정렬된 순서대로 고정 배정(넘치면 마지막 색) */
const FILLS = ['#4c8df6', '#f2994a', '#2fb57c', '#9b6ef3', '#ef5f6b', '#22b8cf', '#e8b931'];

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토'];
/** "2026년 11월 14일 토요일" */
const nowLabel = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return `${y}년 ${m}월 ${d}일 ${WEEKDAY[new Date(Date.UTC(y, m - 1, d)).getUTCDay()]}요일`;
};

/** 24시간 문자판 각도: 0시(자정)가 위(0°), 한 시간에 15°. */
const clockDeg = (min: number) => ((min / 60) % 24) * 15;

type Placed = {
    t: Task;
    i: number;
    a1: number;
    a2: number;
    mid: number;
    right: boolean;
    y: number;
};

/** 한쪽 칸 상자들을 이음선 끝 높이 순으로 놓고, 겹치면 아래로 밀었다가 넘치면 위로 당긴다. */
function stack(items: Placed[], anchorY: (p: Placed) => number, h: number) {
    items.sort((a, b) => anchorY(a) - anchorY(b));
    let prev = -Infinity;
    for (const p of items) {
        p.y = Math.max(anchorY(p) - BOX_H / 2, prev + BOX_H + GAP, 0);
        prev = p.y;
    }
    let next = h;
    for (let k = items.length - 1; k >= 0; k--) {
        items[k].y = Math.min(items[k].y, next - BOX_H);
        next = items[k].y - GAP;
    }
}

/**
 * 내 업무 시계: 위쪽 날짜 띠에서 고른 날의 업무 시간대를 0~23시 24시간 문자판 위 부채꼴로 칠하고,
 * 각 부채꼴에서 이음선으로 양옆 업무 상자(이름·시간·상태)를 잇는다. 상자가 같은 내용을 글자로 보여
 * 색만으로 구분하지 않는다. 시침·분침은 지금 시각(NOW), 초침은 실제 초를 가리킨다.
 * 오른쪽 위 전환 단추로 같은 날 업무를 시계 대신 시간순 목록으로도 볼 수 있다.
 * compact(APP): 날짜 띠·윗줄(header·보기 전환) 없이 오늘(없으면 가까운 업무일) 시계만 보이고,
 * 업무 상자는 이음선 대신 시계 아래에 같은 색으로 늘어놓는다.
 */
export function TaskClock({
    tasks,
    header,
    compact = false,
}: {
    tasks: Task[];
    header?: ReactNode;
    compact?: boolean;
}) {
    const shell = useShell();
    const [picked, setDay] = useState<string | null>(null);
    // 시계/목록 보기는 주소(?clock=list)에 두어 새로고침해도 그대로 연다
    const [view, setView] = useUrlState<'clock' | 'list'>('clock', 'clock', ['clock', 'list']);
    const [sec, setSec] = useState(() => new Date().getSeconds());
    useEffect(() => {
        const id = window.setInterval(() => setSec(new Date().getSeconds()), 1000);
        return () => window.clearInterval(id);
    }, []);

    const live = tasks.filter((t) => t.status !== 'canceled');
    const marked = new Set(live.map((t) => t.date ?? EVENT_DATE));
    // 업무 있는 날만 고른다: 고른 날이 없거나 업무가 사라졌으면 오늘(없으면 오늘 이후 첫 업무일)로
    const taskDays = [...marked].sort();
    const day =
        picked && marked.has(picked)
            ? picked
            : marked.has(EVENT_DATE)
              ? EVENT_DATE
              : (taskDays.find((d) => d >= EVENT_DATE) ?? taskDays.at(-1) ?? EVENT_DATE);
    const list = live
        .filter((t) => (t.date ?? EVENT_DATE) === day)
        .sort((a, b) => a.start.localeCompare(b.start));

    const placed: Placed[] = list.map((t, i) => {
        const a1 = clockDeg(toMin(t.start));
        // 24시간을 넘는 업무는 한 바퀴로 자른다
        const dur = Math.min(toMin(t.end) - toMin(t.start), 1439);
        const a2 = a1 + Math.max(dur * 0.25, 1.5);
        const mid = ((a1 + a2) / 2) % 360;
        return { t, i, a1, a2, mid, right: mid < 180, y: 0 };
    });
    const perSide = Math.max(
        placed.filter((p) => p.right).length,
        placed.filter((p) => !p.right).length,
    );
    const w = compact ? W_COMPACT : W;
    const H = compact ? 2 * R + 64 : Math.max(2 * R + 64, perSide * (BOX_H + GAP));
    const cx = w / 2;
    const cy = H / 2;
    const point = (a: number, r: number) => {
        const rad = ((a - 90) * Math.PI) / 180;
        return [cx + r * Math.cos(rad), cy + r * Math.sin(rad)];
    };
    // 업무 시간대 부채꼴: 가운데에서 문자판 가장자리까지 칠한다
    const sector = (a1: number, a2: number) => {
        const [x1, y1] = point(a1, R);
        const [x2, y2] = point(a2, R);
        return `M${cx} ${cy} L${x1} ${y1} A${R} ${R} 0 ${a2 - a1 > 180 ? 1 : 0} 1 ${x2} ${y2} Z`;
    };
    const elbow = (p: Placed) => point(p.mid, R + 26);
    stack(
        placed.filter((p) => p.right),
        (p) => elbow(p)[1],
        H,
    );
    stack(
        placed.filter((p) => !p.right),
        (p) => elbow(p)[1],
        H,
    );

    // WEB 팀원은 업무 상세(업무 조정)를 열 수 없어 상자·부채꼴을 누를 수 없게 둔다
    const openTask = shell.canOpenTask ? shell.openTask : undefined;
    const nowMin = toMin(NOW);
    // 바늘: [각도, 길이, 반대쪽 꼬리 길이, 클래스]
    const hands: [number, number, number, string][] = [
        [clockDeg(nowMin), R * 0.42, 10, styles.hourHand],
        [(nowMin % 60) * 6, R * 0.64, 12, styles.minuteHand],
        [sec * 6, R * 0.74, 18, styles.secondHand],
    ];
    /** 끝난 업무는 회색, 나머지는 정렬 순서대로 고정 색 */
    const DONE_FILL = '#c4c9d1';
    const fill = (p: Placed) =>
        p.t.status === 'done' ? DONE_FILL : FILLS[Math.min(p.i, FILLS.length - 1)];
    const isDone = (p: Placed) => p.t.status === 'done';

    // compact(APP): 시계 아래 업무 상자 목록. 진행·예정은 지금 시각 위, 끝난 업무는 아래에 둔다
    const clockItems = (items: Placed[]) =>
        items.length > 0 && (
            <ul className={styles.clockItems}>
                {items.map((p) => (
                    <li key={p.t.id}>
                        <ClockItem task={p.t} color={fill(p)} onOpen={openTask} />
                    </li>
                ))}
            </ul>
        );

    return (
        <div className={compact ? styles.clockWrapCompact : styles.clockWrap}>
            {/* 왼쪽: 날짜 고르기(세로 띠) / 오른쪽: 보기 전환 + 시계·목록 */}
            {!compact && (
                <DateStrip value={day} onChange={setDay} marked={marked} today={EVENT_DATE} />
            )}
            <div className={styles.clockMain}>
                {/* 맨 위 줄: (왼쪽 달력 제목 옆) 감싸는 쪽이 넘긴 내용 | 보기 전환 */}
                {/* APP(compact)은 소속 안내·보기 전환 없이 시계만 보인다. 시계 켜기/끄기는 설정 탭에서 한다 */}
                {!compact && (
                    <div className={styles.clockTop}>
                        <div className={styles.clockHeader}>{header}</div>
                        <div
                            className={styles.viewToggle}
                            role="group"
                            aria-label="업무 시간 보기 방식"
                        >
                            <button
                                type="button"
                                className={styles.viewBtn}
                                aria-pressed={view === 'clock'}
                                onClick={() => setView('clock')}
                            >
                                시계
                            </button>
                            <button
                                type="button"
                                className={styles.viewBtn}
                                aria-pressed={view === 'list'}
                                onClick={() => setView('list')}
                            >
                                목록
                            </button>
                        </div>
                    </div>
                )}
                {!compact && view === 'list' ? (
                    <TaskTimeList day={day} list={list} onOpen={openTask} />
                ) : (
                    <>
                        <svg
                            viewBox={`0 0 ${w} ${H}`}
                            className={compact ? styles.clockCompact : styles.clock}
                            role="group"
                            aria-label={`내 업무 시계, 지금 ${NOW}, 고른 날 업무 ${list.length}건`}
                        >
                            <circle cx={cx} cy={cy} r={R} className={styles.dial} />
                            {/* 15분마다 작은 점(정시 자리는 숫자가 대신한다) */}
                            {Array.from({ length: 96 }, (_, k) => {
                                if (k % 4 === 0) return null;
                                const [x, y] = point(k * 3.75, R - 4);
                                return (
                                    <circle
                                        key={k}
                                        cx={x}
                                        cy={y}
                                        r={0.9}
                                        className={styles.minuteDot}
                                    />
                                );
                            })}
                            {placed.map((p) => (
                                <path
                                    key={p.t.id}
                                    d={sector(p.a1, p.a2)}
                                    fill={fill(p)}
                                    className={styles.sector}
                                    onClick={() => openTask?.(p.t.id)}
                                >
                                    <title>
                                        {p.t.start}–{p.t.end} {p.t.title} (
                                        {TASK_STATUS_LABEL[p.t.status]})
                                    </title>
                                </path>
                            ))}
                            {/* 숫자는 0·6·12·18시만, 나머지 정시는 짧은 눈금으로 */}
                            {Array.from({ length: 24 }, (_, n) => {
                                if (n % 6 === 0) {
                                    const [tx, ty] = point(n * 15, NUM_R);
                                    return (
                                        <text key={n} x={tx} y={ty} className={styles.hour}>
                                            {n}
                                        </text>
                                    );
                                }
                                const [x1, y1] = point(n * 15, R - 3);
                                const [x2, y2] = point(n * 15, R - 11);
                                return (
                                    <line
                                        key={n}
                                        x1={x1}
                                        y1={y1}
                                        x2={x2}
                                        y2={y2}
                                        className={styles.hourTick}
                                    />
                                );
                            })}
                            {/* 문자판 가장자리(띠 바로 바깥) → 꺾임점 → 상자 안쪽 가장자리로 잇는다 */}
                            {!compact &&
                                placed.map((p) => {
                                    const [sx, sy] = point(p.mid, R);
                                    const [ex, ey] = elbow(p);
                                    const bx = p.right ? W - BOX_W : BOX_W;
                                    const by = p.y + BOX_H / 2;
                                    return (
                                        <g key={p.t.id}>
                                            <polyline
                                                points={`${sx},${sy} ${ex},${ey} ${bx},${by}`}
                                                className={styles.leader}
                                            />
                                            <circle
                                                cx={sx}
                                                cy={sy}
                                                r={3.5}
                                                fill={fill(p)}
                                                className={styles.leaderDot}
                                            />
                                        </g>
                                    );
                                })}
                            {hands.map(([a, len, tail, cls]) => {
                                const [x1, y1] = point(a + 180, tail);
                                const [x2, y2] = point(a, len);
                                return (
                                    <line
                                        key={cls}
                                        x1={x1}
                                        y1={y1}
                                        x2={x2}
                                        y2={y2}
                                        className={cls}
                                    />
                                );
                            })}
                            <circle cx={cx} cy={cy} r={6} className={styles.pivot} />
                            {!compact &&
                                placed.map((p) => (
                                    <foreignObject
                                        key={p.t.id}
                                        x={p.right ? W - BOX_W : 0}
                                        y={p.y}
                                        width={BOX_W}
                                        height={BOX_H}
                                    >
                                        <ClockItem task={p.t} color={fill(p)} onOpen={openTask} />
                                    </foreignObject>
                                ))}
                        </svg>
                        {compact && clockItems(placed.filter((p) => !isDone(p)))}
                        {/* 시계 바로 밑: 오늘 날짜, 줄 바꿔 지금 시각을 크게(초는 작게) */}
                        <p className={styles.nowLine} aria-live="off">
                            <span className={styles.nowDate}>{nowLabel(EVENT_DATE)}</span>
                            <span className={styles.nowTime}>
                                {NOW}
                                <small>:{String(sec).padStart(2, '0')}</small>
                            </span>
                        </p>
                        {/* 끝난 업무는 지금 시각 아래로 내려 진행·예정 업무와 나눈다 */}
                        {compact && clockItems(placed.filter(isDone))}
                    </>
                )}
            </div>
        </div>
    );
}

/** 시계 업무 상자: 목록 보기와 같은 한 줄 형식(시간 | 상태 | 업무명(구역)), 왼쪽 띠는 부채꼴 색 */
function ClockItem({
    task: t,
    color,
    onOpen,
}: {
    task: Task;
    color: string;
    /** 없으면 업무를 누를 수 없다(WEB 팀원) */
    onOpen?: (id: string) => void;
}) {
    return (
        <button
            type="button"
            className={styles.clockItem}
            // 끝난 업무는 회색으로 흐리게 해 진행 중·예정 업무와 구별한다
            data-done={t.status === 'done' || undefined}
            style={{ borderLeftColor: color }}
            disabled={!onOpen}
            onClick={() => onOpen?.(t.id)}
        >
            <time className="mono">
                {t.start}–{t.end}
            </time>
            <TaskStatusChip status={t.status} />
            <span className={styles.clockItemTitle}>
                {t.title} ({t.zone})
            </span>
            {isLate(t) && <span className="chip bad">지연</span>}
        </button>
    );
}

/** 목록 보기: 고른 날 업무를 "시간 | 상태 | 업무명(구역)" 한 줄씩 시간순으로 적는다. */
function TaskTimeList({
    day,
    list,
    onOpen,
}: {
    day: string;
    list: Task[];
    /** 없으면 업무를 누를 수 없다(WEB 팀원) */
    onOpen?: (id: string) => void;
}) {
    return (
        <section className={styles.timeListWrap} aria-label={`${day} 업무 목록`}>
            {list.length ? (
                <ol className={styles.timeList}>
                    {list.map((t) => (
                        <li key={t.id}>
                            <button
                                type="button"
                                className={styles.timeRow}
                                data-done={t.status === 'done' || undefined}
                                disabled={!onOpen}
                                onClick={() => onOpen?.(t.id)}
                            >
                                <time className="mono">
                                    {t.start}–{t.end}
                                </time>
                                <TaskStatusChip status={t.status} />
                                <span>
                                    {t.title} ({t.zone})
                                </span>
                                {isLate(t) && <span className="chip bad">지연</span>}
                            </button>
                        </li>
                    ))}
                </ol>
            ) : (
                <p className="small">이 날은 업무가 없습니다.</p>
            )}
        </section>
    );
}
