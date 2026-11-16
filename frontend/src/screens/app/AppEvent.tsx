import type { ReactNode } from 'react';
import { useData, useEvent, useMe, useSession, useViewer } from '../../domain/hooks';
import { EVENT_STATE_LABEL, NOW_MIN, TASK_STATUS_LABEL, toMin } from '../../domain/selectors';
import { NOW } from '../../domain/seed';
import type { Task } from '../../domain/types';
import styles from './AppEvent.module.css';

type Row =
    | { key: string; start: string; end: string; kind: 'program'; title: string }
    | { key: string; start: string; end: string; kind: 'task'; title: string; task: Task };

/**
 * M02 계획(APP): 그룹 안내 한 상자 → 공연 순서와 내 업무를 한 시간표로 → 그룹의 다른 업무·공연 정보는 접기.
 * 현장 팀원이 "다음 순서와 내 차례"를 한눈에 보도록 공연 순서 사이에 내 업무를 끼워 넣고 지금 시각 선을 긋는다.
 * 총괄은 모든 그룹, 팀원은 소속 그룹의 준비·전환만 보이며, 전체 업무표·장비/안전 대시보드는 APP에 두지 않는다.
 */
export function AppEvent() {
    const ev = useEvent();
    const d = useData();
    const v = useViewer();
    const { me } = useSession();
    const { group } = useMe();
    // 총괄은 모든 그룹, 팀원은 자기 소속 그룹의 준비·전환 흐름
    const tasks = d.tasks
        .filter((t) => t.status !== 'canceled' && (v.isChief || t.groupId === group?.id))
        .sort((a, b) => toMin(a.start) - toMin(b.start));
    const mine = tasks.filter((t) => t.assignee === me);
    const others = tasks.filter((t) => t.assignee !== me);
    const rows: Row[] = [
        ...ev.programs.map((p) => ({
            key: p.id,
            start: p.start,
            end: p.end,
            kind: 'program' as const,
            title: p.name,
        })),
        ...mine.map((t) => ({
            key: t.id,
            start: t.start,
            end: t.end,
            kind: 'task' as const,
            title: t.title,
            task: t,
        })),
    ].sort((a, b) => toMin(a.start) - toMin(b.start) || (a.kind === 'task' ? -1 : 1));
    // 지금 시각 선은 아직 시작하지 않은 첫 줄 앞에 긋는다
    const nextIdx = rows.findIndex((r) => toMin(r.start) > NOW_MIN);
    const guides = v.isChief ? d.groups : group ? [group] : [];
    const people = d.members.filter((m) => !m.isReserve).length;
    const reserves = d.members.filter((m) => m.isReserve).length;
    const nowLine = (
        <div className={styles.nowLine} role="note" aria-label={`지금 ${NOW}`}>
            <span>{NOW}</span>
            <i aria-hidden="true" />
        </div>
    );
    return (
        <>
            {(ev.notice || guides.length > 0) && (
                <section
                    className={styles.guide}
                    aria-label={v.isChief ? '공통 안내' : `${group?.name ?? '소속'} 안내`}
                >
                    {v.isChief ? (
                        <>
                            <span className={styles.guideHead}>공통 안내</span>
                            {ev.notice && <span>{ev.notice}</span>}
                            {guides.map((g) => (
                                <span key={g.id}>
                                    <b>{g.name}</b> {g.guide}
                                </span>
                            ))}
                        </>
                    ) : (
                        <>
                            <span className={styles.guideHead}>{group?.name ?? '소속'} 안내</span>
                            {group?.guide && <span>{group.guide}</span>}
                            {ev.notice && <span>{ev.notice}</span>}
                        </>
                    )}
                </section>
            )}

            <section className={styles.flow} aria-labelledby="plan-flow-h">
                <div className={styles.flowHead}>
                    <h2 id="plan-flow-h">공연 순서와 내 업무</h2>
                    <span>시간은 바뀔 수 있음</span>
                </div>
                {rows.map((r, k) => (
                    <div key={r.key}>
                        {k === nextIdx && nowLine}
                        <FlowRow row={r} />
                    </div>
                ))}
                {nextIdx === -1 && rows.length > 0 && nowLine}
                {!rows.length && <p className={styles.empty}>공연 순서와 내 업무가 없습니다.</p>}
            </section>

            <div className={styles.folds}>
                <details className={styles.fold}>
                    <summary>
                        <span className={styles.foldTitle}>
                            {v.isChief ? '전체 업무' : `${group?.name ?? '소속'} 다른 업무`}{' '}
                            <span className={styles.count}>{others.length}</span>
                        </span>
                        <Chevron />
                    </summary>
                    {others.length ? (
                        <ul className={styles.otherList}>
                            {others.map((t) => {
                                const p = ev.programs.find((x) => x.id === t.program);
                                return (
                                    <li key={t.id}>
                                        <span className={styles.time}>{t.start}</span>
                                        <span className={styles.rowText}>
                                            <span>{t.title}</span>
                                            <span className={styles.sub}>
                                                {t.start}–{t.end} · {t.zone}
                                                {p && ` · ${p.name}`}
                                                {t.status === 'done' && ' · 완료'}
                                            </span>
                                        </span>
                                    </li>
                                );
                            })}
                        </ul>
                    ) : (
                        <p className={styles.empty}>그룹의 다른 업무가 없습니다.</p>
                    )}
                </details>
                <details className={styles.fold}>
                    <summary>
                        <span className={styles.rowText}>
                            <span className={styles.foldTitle}>공연 정보</span>
                            <span className={styles.sub}>
                                {ev.date} {ev.time} · {ev.place}
                            </span>
                        </span>
                        <Chevron />
                    </summary>
                    <dl className={styles.overview}>
                        <dt>행사</dt>
                        <dd>{ev.name}</dd>
                        <dt>일시</dt>
                        <dd>
                            {ev.date} {ev.time}
                        </dd>
                        <dt>장소</dt>
                        <dd>{ev.place}</dd>
                        <dt>구역</dt>
                        <dd>{ev.zones.join(' / ')}</dd>
                        <dt>시간</dt>
                        <dd>
                            준비 {ev.prep} / 개장 {ev.open} / 폐장 {ev.close} / 철수 {ev.teardown}
                        </dd>
                        <dt>인원</dt>
                        <dd>
                            총 {ev.capacity}명 목표 / 참여 {people}명 / 예비 {reserves}명 / 그룹{' '}
                            {d.groups.length}개
                        </dd>
                        <dt>상태</dt>
                        <dd>{EVENT_STATE_LABEL[ev.state]}</dd>
                    </dl>
                </details>
            </div>
        </>
    );
}

/** 접기 표시(아래 꺾쇠). 펼치면 CSS가 뒤집는다 */
function Chevron() {
    return (
        <svg
            className={styles.chev}
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
        >
            <path d="m6 9 6 6 6-6" />
        </svg>
    );
}

/** 시간표 한 줄. 내 업무는 칩으로 표시하고 지금 하는 내 업무는 줄 전체를 강조한다. */
function FlowRow({ row }: { row: Row }) {
    const past = toMin(row.end) <= NOW_MIN;
    const current = toMin(row.start) <= NOW_MIN && !past;
    let chip: ReactNode = null;
    let now = false;
    if (row.kind === 'task') {
        const done = row.task.status === 'done';
        now = current && !done;
        chip = done ? (
            <span className={`${styles.chip} ${styles.chipDone}`}>내 업무 · 완료</span>
        ) : now ? (
            <span className={`${styles.chip} ${styles.chipNow}`}>내 업무 · 지금</span>
        ) : (
            <span className={`${styles.chip} ${styles.chipMine}`}>
                내 업무{past ? ` · ${TASK_STATUS_LABEL[row.task.status]}` : ''}
            </span>
        );
    } else if (current) {
        chip = <span className={`${styles.chip} ${styles.chipShow}`}>공연 · 진행 중</span>;
    }
    const sub =
        row.kind === 'task'
            ? `${row.start}–${row.end} · ${row.task.zone}`
            : `${row.start}–${row.end}`;
    return (
        <div
            className={`${styles.row} ${now ? styles.rowNow : ''} ${past ? styles.rowPast : ''}`}
            aria-current={now ? 'step' : undefined}
        >
            <span className={styles.time}>{row.start}</span>
            <span className={styles.rowBody}>
                <span className={styles.rowText}>
                    <span className={now ? styles.nowTitle : undefined}>{row.title}</span>
                    <span className={styles.sub}>{sub}</span>
                </span>
                {chip}
            </span>
        </div>
    );
}
