import type { CSSProperties } from 'react';
import { useData } from '../../domain/hooks';
import { fmtMin, nameOf, toMin } from '../../domain/selectors';
import { EVENT_DATE, NOW } from '../../domain/seed';
import type { RecoveryOption } from '../../domain/types';
import { Empty } from '../../shared/ui/atoms';
import { axisSlots, useTimeZoom } from '../../shared/ui/useTimeZoom';
import { ZoomButtons } from '../../shared/ui/ZoomButtons';
import styles from './compare.module.css';

type Bar = {
    key: string;
    start: string;
    end: string;
    label: string;
    /** 예약 종류(준비·공연·회수·예약) 또는 업무 */
    kind: string;
    /** 이 대안으로 새로 생기는 것 */
    added?: boolean;
    /** 이 대안으로 해제되는 기존 것(변경 후 줄에 지운 모양으로 남긴다) */
    removed?: boolean;
    /** 같은 장비·사람의 다른 것과 시간이 겹침(해제되는 것은 빼고 판단한다) */
    clash?: boolean;
};

const overlap = (a: { start: string; end: string }, b: { start: string; end: string }) =>
    toMin(a.start) < toMin(b.end) && toMin(b.start) < toMin(a.end);

const markClashes = (bars: Bar[]) =>
    bars.map((b) => ({
        ...b,
        clash: !b.removed && bars.some((x) => x !== b && !x.removed && overlap(x, b)),
    }));

/**
 * 복구 대안 비교(D03): 고른 대안이 기존 시간표를 어떻게 바꾸는지 장비·담당자마다 "현재 / 변경 후"를 위아래로 놓는다.
 * 변경 후에서 새로 생기는 예약·업무는 강조하고, 해제되는 기존 예약은 지운 모양으로 남긴다.
 * 시간이 겹치는 것은 양쪽 모두 "충돌"로 표시하고, 변경 후의 충돌은 표 아래에 빠짐없이 적는다. 아직 적용 전이다.
 */
export function RecoveryCompare({ option }: { option: RecoveryOption | null }) {
    if (!option)
        return (
            <Empty>대안의 &quot;미리 보기&quot;를 누르면 현재와 변경 후를 나란히 비교합니다</Empty>
        );
    return <CompareTable option={option} />;
}

function CompareTable({ option: o }: { option: RecoveryOption }) {
    const d = useData();
    // 대상: 고장 장비 + 대안이 쓰는 장비, 그리고 새 업무를 맡을 사람
    const assetIds = [
        ...new Set([
            ...d.equipment.filter((a) => a.tech === 'FAULT').map((a) => a.id),
            ...o.assets,
            ...o.reservations.map((r) => r.assetId),
            ...d.reservations.filter((r) => o.releases?.includes(r.id)).map((r) => r.assetId),
        ]),
    ];
    const released = new Set(o.releases ?? []);
    const rows = assetIds.map((id) => {
        const asset = d.equipment.find((a) => a.id === id);
        const current: Bar[] = d.reservations
            .filter((r) => r.assetId === id)
            .map((r) => ({ key: r.id, start: r.start, end: r.end, label: r.label, kind: r.kind }));
        // 변경 후에도 남는 기존 예약 / 해제되는 기존 예약
        const kept = current.filter((b) => !released.has(b.key));
        const gone = current
            .filter((b) => released.has(b.key))
            .map((b) => ({ ...b, key: `gone-${b.key}`, removed: true }));
        const added: Bar[] = o.reservations
            .filter((r) => r.assetId === id)
            .map((r, k) => ({
                key: `new-${k}`,
                start: r.start,
                end: r.end,
                label: r.label,
                kind: r.kind,
                added: true,
            }));
        return {
            key: id,
            name: asset?.name ?? id,
            sub: asset?.kind ?? '',
            current: markClashes(current),
            after: markClashes([...kept, ...gone, ...added]),
        };
    });
    if (o.staff) {
        const today = d.tasks.filter(
            (t) =>
                t.assignee === o.staff &&
                t.status !== 'canceled' &&
                (t.date ?? EVENT_DATE) === EVENT_DATE,
        );
        const current: Bar[] = today.map((t) => ({
            key: t.id,
            start: t.start,
            end: t.end,
            label: t.title,
            kind: '업무',
        }));
        const task: Bar = {
            key: 'new-task',
            start: o.task.start,
            end: o.task.end,
            label: o.task.title,
            kind: '업무',
            added: true,
        };
        rows.push({
            key: `staff-${o.staff}`,
            name: nameOf(d, o.staff),
            sub: '담당 업무',
            current: markClashes(current),
            after: markClashes([...current, task]),
        });
    }

    // 변경 후의 충돌을 한 쌍씩
    const clashes = rows.flatMap((r) =>
        r.after.flatMap((a, i) =>
            r.after
                .slice(i + 1)
                .filter((b) => !a.removed && !b.removed && overlap(a, b))
                .map((b) => ({ key: `${r.key}-${a.key}-${b.key}`, name: r.name, a, b })),
        ),
    );

    // 시간축: 비교에 나오는 시각을 30분 단위로 감싼다(지금 시각 포함). 처음에는 지금 시각을 가운데에 둔다
    const all = rows.flatMap((r) => r.after);
    const mins = [...all.flatMap((b) => [toMin(b.start), toMin(b.end)]), toMin(NOW)];
    const t0 = Math.floor(Math.min(...mins) / 30) * 30;
    const t1 = Math.max(Math.ceil(Math.max(...mins) / 30) * 30, t0 + 30);
    const zoom = useTimeZoom({ initial: 8, t0, centerMin: toMin(NOW) });
    const { px, bindRoot, bindOrigin } = zoom;
    const slots = axisSlots(t0, t1, px);
    const pos = (m: number) => (Math.min(Math.max(m, t0), t1) - t0) * px;
    const w = (a: string, b: string) => pos(toMin(b)) - pos(toMin(a));
    const track = (bars: Bar[]) => (
        <div className={styles.track}>
            <i className={styles.now} style={{ left: pos(toMin(NOW)) }} aria-hidden="true" />
            {bars.map((b) => (
                <span
                    key={b.key}
                    className={styles.bar}
                    data-kind={b.kind}
                    data-added={b.added || undefined}
                    data-removed={b.removed || undefined}
                    data-clash={b.clash || undefined}
                    style={{ left: pos(toMin(b.start)), width: w(b.start, b.end) }}
                    title={`${b.added ? '추가 / ' : b.removed ? '해제 / ' : ''}${b.kind} ${b.start}–${b.end} ${b.label}${b.clash ? ' / 충돌' : ''}`}
                >
                    {b.added && '+ '}
                    {b.removed && '− '}
                    {b.label}
                </span>
            ))}
        </div>
    );

    return (
        <>
            <div className={styles.tools}>
                <div className={styles.legend}>
                    <span className={styles.chip}>그대로</span>
                    <span className={styles.chip} data-added>
                        + 미리 보기(미적용)
                    </span>
                    <span className={styles.chip} data-removed>
                        − 해제
                    </span>
                    <span className={styles.chip} data-clash>
                        충돌
                    </span>
                </div>
                <ZoomButtons zoom={zoom} />
            </div>
            <div
                ref={bindRoot}
                className={styles.wrap}
                role="table"
                aria-label="현재와 변경 후 비교"
                style={{ '--track-w': `${(t1 - t0) * px}px` } as CSSProperties}
            >
                <div className={styles.head} role="row">
                    <span />
                    <span />
                    <div ref={bindOrigin} className={styles.scale}>
                        {slots.map((m) => (
                            <span key={m} style={{ left: pos(m) }}>
                                {fmtMin(m)}
                            </span>
                        ))}
                    </div>
                </div>
                {rows.map((r) => (
                    <div key={r.key} className={styles.group} role="rowgroup">
                        <div className={styles.name} role="rowheader">
                            <b className="small">{r.name}</b>
                            <span className="xs muted">{r.sub}</span>
                        </div>
                        <div className={styles.pair}>
                            <div className={styles.row} role="row">
                                <span className={styles.tag}>현재</span>
                                {track(r.current)}
                            </div>
                            <div className={styles.row} role="row" data-after>
                                <span className={styles.tag}>변경 후</span>
                                {track(r.after)}
                            </div>
                        </div>
                    </div>
                ))}
            </div>
            <div className={styles.clashes}>
                <b className="small">{clashes.length ? `충돌 ${clashes.length}건` : '충돌 없음'}</b>
                {clashes.length > 0 && (
                    <ul>
                        {clashes.map(({ key, name, a, b }) => (
                            <li key={key} className="small">
                                {name}: {a.label} {a.start}–{a.end} / {b.label} {b.start}–{b.end}
                            </li>
                        ))}
                    </ul>
                )}
            </div>
        </>
    );
}
