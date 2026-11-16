import { Fragment, useState } from 'react';
import { useData, useEvent, useViewer } from '../../domain/hooks';
import { diffRows, nameOf, restoreRefusal, type DiffRow } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { Change } from '../../domain/types';
import { Chip, Empty } from '../../shared/ui/atoms';
import { Icon } from '../../shared/ui/Icon';
import styles from './web.module.css';

type Act = { label: '생성' | '삭제' | '배정' | '수정'; tone: 'ok' | 'bad' | 'accent' };
/**
 * 이 줄이 한 일: 새 업무면 생성, 업무를 없앴으면 삭제, 그 밖에는 변경안 출처로 배정 /
 * 수정(보고 대응·복구 대안·안전 지원·프로그램 시간 등)
 */
const actionOf = (c: Change, r: DiffRow): Act =>
    r.kind === 'add'
        ? { label: '생성', tone: 'ok' }
        : r.kind === 'cancel'
          ? { label: '삭제', tone: 'bad' }
          : c.source?.includes('배정')
            ? { label: '배정', tone: 'accent' }
            : { label: '수정', tone: 'accent' };

type Row = { change: Change; row: DiffRow; key: string };

/**
 * 적용된 변경(W01 탭): 큐시트처럼 바뀐 업무를 한 줄씩 보이고, 변경안마다 머리 줄로 묶는다.
 * 줄을 누르면 오른쪽에 변경 전 / 변경 후 두 열로 보인다.
 * 삭제된 업무는 이름에 빨간 취소선을 긋고, 누르면 복원 / 영구 삭제 / 취소를 고른다(총괄).
 */
export function AppliedChanges({ changes }: { changes: Change[] }) {
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const dispatch = useDispatch();
    const live = !!useDemoState().live;
    const [sel, setSel] = useState<string | null>(null);
    const [deleted, setDeleted] = useState<string | null>(null);

    // 영구 삭제한 업무는 변경 기록에서도 모든 줄을 뺀다. 줄이 하나도 남지 않은 변경안은 머리 줄째 뺀다
    const sections = changes
        .map((c) => ({
            change: c,
            rows: diffRows(d, ev, c)
                .filter((row) => !d.purgedTasks?.[row.code])
                .map((row): Row => ({ change: c, row, key: `${c.id}:${row.key}` })),
        }))
        .filter((s) => s.rows.length);
    const all = sections.flatMap((s) => s.rows);
    const picked = all.find((r) => r.key === sel) ?? all[0];
    if (!sections.length) return <Empty>확정된 변경이 없습니다</Empty>;

    // 업무 줄의 상태: 삭제된 채로 남음 / 복원됨(영구 삭제한 업무는 위에서 이미 뺐다)
    const stateOf = (r: DiffRow) => {
        if (r.kind !== 'cancel') return null;
        const t = d.tasks.find((x) => x.id === r.code);
        if (!t) return null;
        return t.status === 'canceled' ? 'deleted' : 'restored';
    };
    const field = (r: DiffRow, label: string) => {
        const f = r.detail.find((x) => x.label === label);
        if (!f) return '';
        // 삭제는 삭제 전 값을, 나머지는 바뀐 뒤 값을 보인다
        return r.kind === 'cancel' ? f.before : f.after;
    };

    return (
        <div className={styles.planTable}>
            <section>
                <table className={styles.table}>
                    <thead>
                        <tr>
                            <th>담당</th>
                            <th>시간</th>
                            <th>업무</th>
                            <th>구역</th>
                            <th>작업</th>
                            <th>작성</th>
                            <th>적용</th>
                        </tr>
                    </thead>
                    <tbody>
                        {sections.map(({ change: c, rows }) => (
                            <Fragment key={c.id}>
                                {rows.map(({ change: c, row: r, key }) => {
                                    const st = stateOf(r);
                                    const open = () => {
                                        setSel(key);
                                        if (st === 'deleted' && v.isChief) setDeleted(r.code);
                                    };
                                    return (
                                        <tr
                                            key={key}
                                            tabIndex={0}
                                            aria-selected={key === picked?.key}
                                            onClick={open}
                                            onKeyDown={(e) => {
                                                if (e.key === 'Enter' || e.key === ' ') {
                                                    e.preventDefault();
                                                    open();
                                                }
                                            }}
                                        >
                                            <td>{field(r, '담당')}</td>
                                            <td className="mono">{field(r, '시간')}</td>
                                            <td>
                                                <span
                                                    className={
                                                        st === 'deleted'
                                                            ? styles.deletedName
                                                            : undefined
                                                    }
                                                >
                                                    {r.target}
                                                </span>{' '}
                                                {!live && <span className="code">{r.code}</span>}
                                            </td>
                                            <td>{field(r, '구역')}</td>
                                            <td>
                                                <span className="row nw">
                                                    <Chip kind={actionOf(c, r).tone}>
                                                        {actionOf(c, r).label}
                                                    </Chip>
                                                    {st === 'restored' && (
                                                        <Chip kind="ok">복원됨</Chip>
                                                    )}
                                                </span>
                                            </td>
                                            {/* 작성·적용은 변경안 단위 값이라 그 변경안의 줄마다 같다 */}
                                            <td className={styles.changeMeta}>
                                                {nameOf(d, c.by)}{' '}
                                                <span className="mono">{c.at}</span>
                                            </td>
                                            <td className={`mono ${styles.changeMeta}`}>
                                                {c.appliedAt}
                                            </td>
                                        </tr>
                                    );
                                })}
                            </Fragment>
                        ))}
                    </tbody>
                </table>
            </section>
            <aside className={styles.planDetail} aria-label="변경 내용">
                {picked && (
                    <BeforeAfter
                        row={picked.row}
                        act={actionOf(picked.change, picked.row)}
                        live={live}
                    />
                )}
            </aside>
            {deleted && (
                <DeletedAlert
                    taskId={deleted}
                    onClose={() => setDeleted(null)}
                    onRestore={() => {
                        const refused = restoreRefusal(d, deleted);
                        if (refused) return refused;
                        dispatch({ type: 'restoreTask', taskId: deleted });
                        setDeleted(null);
                        return null;
                    }}
                    onPurge={() => {
                        dispatch({ type: 'purgeTask', taskId: deleted });
                        setDeleted(null);
                    }}
                />
            )}
        </div>
    );
}

/** 고른 줄의 변경 전 / 변경 후 두 열 */
function BeforeAfter({ row: r, act, live }: { row: DiffRow; act: Act; live: boolean }) {
    return (
        <section className="card stack s6">
            <div className="row nw">
                {!live && <span className="code">{r.code}</span>}
                <b className="grow">{r.target}</b>
                <Chip kind={act.tone}>{act.label}</Chip>
            </div>
            <div className={styles.beforeAfter} role="table" aria-label="변경 전후">
                <div role="row" className={styles.baHead}>
                    <span role="columnheader">항목</span>
                    <span role="columnheader">변경 전</span>
                    <span role="columnheader">변경 후</span>
                </div>
                {r.detail.map((f) => (
                    <div
                        key={f.label}
                        role="row"
                        className={styles.baRow}
                        data-changed={f.before !== f.after || undefined}
                    >
                        <b className={styles.baLabel} role="rowheader">
                            {f.label}
                        </b>
                        <span role="cell">{f.before}</span>
                        <span role="cell">{f.after}</span>
                    </div>
                ))}
            </div>
        </section>
    );
}

/** 삭제된 업무를 눌렀을 때: 영구 삭제 / 복원. 닫기는 오른쪽 위 X */
function DeletedAlert({
    taskId,
    onClose,
    onRestore,
    onPurge,
}: {
    taskId: string;
    onClose: () => void;
    onRestore: () => string | null;
    onPurge: () => void;
}) {
    const d = useData();
    const t = d.tasks.find((x) => x.id === taskId);
    const [refused, setRefused] = useState<string | null>(null);
    return (
        <div className={styles.alertScrim} onClick={onClose}>
            <div
                className={styles.alertBox}
                role="alertdialog"
                aria-modal="true"
                aria-labelledby="deleted-title"
                aria-describedby="deleted-body"
                onClick={(e) => e.stopPropagation()}
                onKeyDown={(e) => e.key === 'Escape' && onClose()}
            >
                <div className={styles.alertTop}>
                    <b id="deleted-title" className={styles.alertHead}>
                        <Icon name="warn" /> 삭제된 업무입니다.
                    </b>
                    <button
                        type="button"
                        className={styles.alertClose}
                        aria-label="닫기"
                        autoFocus
                        onClick={onClose}
                    >
                        <Icon name="close" />
                    </button>
                </div>
                <p id="deleted-body" className="small">
                    {t?.title} ({taskId}) / 담당 {nameOf(d, t?.assignee)}
                    <br />
                    <span className="xs muted">
                        복원하면 담당자에게 다시 확인을 받습니다. 영구 삭제하면 되돌릴 수 없습니다.
                    </span>
                </p>
                {refused && (
                    <p className={`small ${styles.alertError}`} role="alert">
                        {refused}
                    </p>
                )}
                <div className="row" style={{ justifyContent: 'flex-end' }}>
                    <button type="button" className={`btn ${styles.purgeBtn}`} onClick={onPurge}>
                        영구 삭제
                    </button>
                    <button
                        type="button"
                        className="btn primary"
                        onClick={() => setRefused(onRestore())}
                    >
                        복원
                    </button>
                </div>
            </div>
        </div>
    );
}
