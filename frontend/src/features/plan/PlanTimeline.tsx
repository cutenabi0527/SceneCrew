import type { CSSProperties } from 'react';
import { useData, useEvent, useMe, useViewer } from '../../domain/hooks';
import { NOW_MIN, fmtMin, groupName, nameOf, toMin } from '../../domain/selectors';
import { EVENT_DATE } from '../../domain/seed';
import type { Task } from '../../domain/types';
import { useShell } from '../../layouts/shell-actions';
import { axisSlots, useTimeZoom } from '../../shared/ui/useTimeZoom';
import { ZoomButtons } from '../../shared/ui/ZoomButtons';
import styles from './timeline.module.css';

/** 막대 한 줄 높이·간격(px). 시간이 겹치는 업무는 아래 줄로 내린다 */
const BAR_H = 34;
const GAP = 6;

function lanes(items: Task[]) {
    const ends: number[] = [];
    const lane = new Map<string, number>();
    [...items]
        .sort((a, b) => toMin(a.start) - toMin(b.start))
        .forEach((t) => {
            const k = ends.findIndex((end) => end <= toMin(t.start));
            const at = k === -1 ? ends.length : k;
            ends[at] = toMin(t.end);
            lane.set(t.id, at);
        });
    return { lane, count: Math.max(ends.length, 1) };
}

/**
 * 공연·업무 타임라인(W01 공연 개요): 맨 위 공연 순서 막대와 그 아래 부서별 준비·전환·공연·철수 업무 막대를
 * 같은 시간축에 놓고, 막대마다 업무명과 담당(또는 미배정)을 적는다. 지금 시각은 세로선으로 표시한다.
 * 총괄은 모든 부서, 팀원은 소속 부서만 본다(APP 계획 탭의 "준비·전환"과 같은 범위).
 */
export function PlanTimeline() {
    const ev = useEvent();
    const d = useData();
    const v = useViewer();
    const { group } = useMe();
    const shell = useShell();
    const tasks = d.tasks.filter(
        (t) =>
            t.status !== 'canceled' &&
            (t.date ?? EVENT_DATE) === EVENT_DATE &&
            (v.isChief || t.groupId === group?.id),
    );
    const times = [
        ...ev.programs.flatMap((p) => [toMin(p.start), toMin(p.end)]),
        ...tasks.flatMap((t) => [toMin(t.start), toMin(t.end)]),
    ];
    // 30분 단위로 앞뒤를 맞춘 시간축
    const t0 = Math.floor(Math.min(...times) / 30) * 30;
    const zoom = useTimeZoom({ initial: 4, t0, centerMin: NOW_MIN });
    const { px, bindRoot, bindOrigin } = zoom;
    if (!times.length) return <p className="small muted">계획된 공연/업무가 없습니다.</p>;
    const t1 = Math.ceil(Math.max(...times) / 30) * 30;
    const span = Math.max(t1 - t0, 30);
    const pos = (m: number) => (Math.min(Math.max(m, t0), t1) - t0) * px;
    const width = (a: string, b: string) => pos(toMin(b)) - pos(toMin(a));
    const slots = axisSlots(t0, t0 + span, px);
    const groups = d.groups.filter((g) => tasks.some((t) => t.groupId === g.id));
    const nowIn = NOW_MIN >= t0 && NOW_MIN <= t1;
    const programs = ev.programs.slice().sort((a, b) => toMin(a.start) - toMin(b.start));

    return (
        <div className={styles.frame}>
            <ZoomButtons zoom={zoom} />
            <div
                ref={bindRoot}
                className={styles.wrap}
                role="table"
                aria-label="공연/업무 타임라인"
                style={{ '--track-w': `${span * px}px` } as CSSProperties}
            >
                <div className={styles.row} role="row">
                    <span className={styles.label} role="rowheader" />
                    <div ref={bindOrigin} className={styles.axis}>
                        {slots.map((m) => (
                            <span key={m} className="mono" style={{ left: pos(m) }}>
                                {fmtMin(m)}
                            </span>
                        ))}
                    </div>
                </div>
                <div className={styles.row} role="row">
                    <span className={styles.label} role="rowheader">
                        <b className="small">공연 순서</b>
                    </span>
                    <div className={styles.track} style={{ height: BAR_H + 12 }}>
                        {nowIn && <i className={styles.now} style={{ left: pos(NOW_MIN) }} />}
                        {programs.map((p) => (
                            <span
                                key={p.id}
                                className={styles.program}
                                style={{ left: pos(toMin(p.start)), width: width(p.start, p.end) }}
                                title={`${p.name} ${p.start}–${p.end}`}
                            >
                                {p.name}
                            </span>
                        ))}
                    </div>
                </div>
                {groups.map((g) => {
                    const items = tasks.filter((t) => t.groupId === g.id);
                    const { lane, count } = lanes(items);
                    return (
                        <div key={g.id} className={styles.row} role="row">
                            <span className={styles.label} role="rowheader">
                                <b className="small">{groupName(d, g.id)}</b>
                                <span className="xs muted">업무 {items.length}</span>
                            </span>
                            <div
                                className={styles.track}
                                style={{ height: 12 + count * BAR_H + (count - 1) * GAP }}
                            >
                                {nowIn && (
                                    <i className={styles.now} style={{ left: pos(NOW_MIN) }} />
                                )}
                                {items.map((t) => (
                                    <button
                                        key={t.id}
                                        type="button"
                                        className={styles.task}
                                        data-status={t.status}
                                        data-open={!t.assignee || undefined}
                                        style={{
                                            top: 6 + (lane.get(t.id) ?? 0) * (BAR_H + GAP),
                                            left: pos(toMin(t.start)),
                                            width: width(t.start, t.end),
                                        }}
                                        title={`${t.start}–${t.end} ${t.title} / ${t.assignee ? nameOf(d, t.assignee) : '미배정'}`}
                                        disabled={!v.canViewTask(t) || !shell.canOpenTask}
                                        onClick={() => shell.openTask(t.id)}
                                    >
                                        <b>{t.title}</b>
                                        <span>{t.assignee ? nameOf(d, t.assignee) : '미배정'}</span>
                                    </button>
                                ))}
                            </div>
                        </div>
                    );
                })}
            </div>
            <p className="xs muted">
                세로선은 지금 시각입니다. 막대를 누르면 업무 상세로 갑니다. 점선 막대는 미배정
                업무입니다.
            </p>
        </div>
    );
}
