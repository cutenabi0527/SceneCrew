import { useData, useEvent, useMe, usePhase, useViewer } from '../../domain/hooks';
import { EVENT_STATE_LABEL, NOW_MIN, nameOf, toMin } from '../../domain/selectors';
import { EVENT_DATE, NOW } from '../../domain/seed';
import { Chip } from '../../shared/ui/atoms';
import styles from './plan.module.css';

/** 행사 개요 키-값 */
export function EventOverview() {
    const ev = useEvent();
    const d = useData();
    const people = d.members.filter((m) => !m.isReserve).length;
    const reserves = d.members.filter((m) => m.isReserve).length;
    return (
        <dl className="kv">
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
            <dd className="mono">
                준비 {ev.prep} / 개장 {ev.open} / 폐장 {ev.close} / 철수 {ev.teardown}
            </dd>
            <dt>인원</dt>
            <dd>
                총 {ev.capacity}명 목표 / 참여 {people}명 / 예비 {reserves}명 / 그룹{' '}
                {d.groups.length}개
            </dd>
            <dt>상태</dt>
            <dd>
                <Chip kind={ev.state === 'LIVE' ? 'ok' : ''}>{EVENT_STATE_LABEL[ev.state]}</Chip>
            </dd>
        </dl>
    );
}

/**
 * 행사 흐름(WEB 계획 개요): "시간 | 상태 | 단계 (담당)" 한 줄씩 시간순으로 적는다. 지금 단계 줄을 강조한다.
 * 담당 인원은 그 단계 시간과 겹치는 당일 업무의 담당자다. 팀원에게는 개인 이름 대신 인원 수만 보인다(P02).
 */
export function PhaseTable() {
    const ev = useEvent();
    const d = useData();
    const v = useViewer();
    const { now } = usePhase();
    const ps = ev.programs.slice().sort((a, b) => toMin(a.start) - toMin(b.start));
    const staffOf = (start: string, end: string) => {
        const ids = d.tasks
            .filter(
                (t) =>
                    (t.date ?? EVENT_DATE) === EVENT_DATE &&
                    t.status !== 'canceled' &&
                    t.assignee &&
                    toMin(t.start) < toMin(end) &&
                    toMin(t.end) > toMin(start),
            )
            .map((t) => t.assignee as string);
        return [...new Set(ids)];
    };
    // 단계 상태: 끝났으면 완료, 지금이면 진행 중, 아니면 예정
    const stateOf = (p: { id: string; end: string }) =>
        p.id === now?.id ? '진행 중' : toMin(p.end) <= NOW_MIN ? '완료' : '예정';
    return (
        <div className={styles.phaseScroll}>
            <ol className={styles.flowList}>
                {ps.map((p) => {
                    const staff = staffOf(p.start, p.end);
                    const state = stateOf(p);
                    const who = !staff.length
                        ? '담당 없음'
                        : v.isChief
                          ? `담당 ${staff.map((id) => nameOf(d, id)).join(', ')}`
                          : `담당 ${staff.length}명`;
                    return (
                        <li key={p.id} aria-current={state === '진행 중' ? 'step' : undefined}>
                            <time className="mono">
                                {p.start}–{p.end}
                            </time>
                            <span className={styles.flowTag} data-state={state}>
                                {state}
                            </span>
                            <span>
                                <b>{p.name}</b> ({who})
                            </span>
                        </li>
                    );
                })}
            </ol>
        </div>
    );
}

/** 전체 흐름(세로 타임라인) + 현재 시각선 */
export function ProgramFlow() {
    const ev = useEvent();
    const ps = ev.programs.slice().sort((a, b) => toMin(a.start) - toMin(b.start));
    const nextIdx = ps.findIndex((p) => toMin(p.start) > NOW_MIN);
    return (
        <div className={styles.flow}>
            {ps.map((p, k) => {
                const state =
                    toMin(p.end) <= NOW_MIN
                        ? 'past'
                        : toMin(p.start) <= NOW_MIN
                          ? 'now'
                          : k === nextIdx
                            ? 'next'
                            : '';
                return (
                    <div key={p.id}>
                        {k === nextIdx && (
                            <div className={styles.nowline}>
                                <span className="mono">{NOW}</span>
                            </div>
                        )}
                        <div className={`${styles.fi} ${state ? styles[state] : ''}`}>
                            <time className="mono">{p.start}</time>
                            <i aria-hidden="true" />
                            <div>
                                <b>{p.name}</b>
                                <div className="xs muted">
                                    {p.start}–{p.end}
                                    {state === 'now' && ' / 진행 중'}
                                </div>
                            </div>
                        </div>
                    </div>
                );
            })}
        </div>
    );
}

/**
 * 공통·소속 안내. 총괄은 모든 그룹 안내, 팀원은 소속 그룹 안내만 본다(P02).
 * 별도 자료함·첨부는 두지 않는다. 최초 입력 문서는 계획 확정(publish) 뒤 닫힌다.
 */
export function GroupGuides() {
    const ev = useEvent();
    const d = useData();
    const v = useViewer();
    const { group } = useMe();
    const list = v.isChief ? d.groups : group ? [group] : [];
    return (
        <table className={styles.guideTable}>
            <colgroup>
                <col className={styles.guideName} />
                <col />
            </colgroup>
            <thead>
                <tr>
                    <th>구분</th>
                    <th>안내</th>
                </tr>
            </thead>
            <tbody>
                {ev.notice && (
                    <tr>
                        <th scope="row">공통</th>
                        <td>{ev.notice}</td>
                    </tr>
                )}
                {list.map((g) => (
                    <tr key={g.id}>
                        <th scope="row">{g.name}</th>
                        <td>{g.guide}</td>
                    </tr>
                ))}
            </tbody>
        </table>
    );
}
