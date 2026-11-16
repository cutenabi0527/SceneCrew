import type { ReactNode } from 'react';
import type { DiffField, DiffRow } from '../../domain/selectors';
import { Icon, type IconName } from '../../shared/ui/Icon';
import { Chip } from '../../shared/ui/atoms';
import styles from './change.module.css';

const KIND_LABEL: Record<DiffRow['kind'], string> = {
    program: '프로그램 시간',
    update: '업무 수정',
    cancel: '업무 취소',
    add: '새 업무',
};
const FIELD_ICON: Record<DiffField['label'], IconName> = {
    시간: 'calendar',
    담당: 'people',
    부서: 'org',
    구역: 'info',
    절차: 'task',
    상태: 'warn',
};

/**
 * WEB 변경 검토: operation마다 업무 전체 항목을 현재 | 변경 후 두 열로 같은 행에 맞춰 적는다.
 * 바뀐 항목만 노란 바탕에 현재(파랑)·변경 후(빨강) 글자색으로 표시하고 나머지는 검정으로 둔다.
 * editAfter가 칸을 돌려주면 "변경 후" 값 대신 그 편집 칸을 보여 준다.
 */
export function ChangeTable({
    rows,
    editAfter,
}: {
    rows: DiffRow[];
    editAfter?: (index: number, label: string) => ReactNode;
}) {
    return (
        <div className="stack" aria-label="변경 항목">
            {rows.map((r, k) => (
                <div key={r.key} className={styles.op}>
                    <p>
                        <b>
                            {r.kind === 'program' ? '프로그램 이름' : '업무 이름'}:{' '}
                            {r.detail[0].after}
                        </b>{' '}
                        ({r.code}, {KIND_LABEL[r.kind]})
                    </p>
                    <div className={styles.compare} role="table">
                        <div role="row" className={styles.compareHead}>
                            <span role="columnheader">항목</span>
                            <span role="columnheader">현재</span>
                            <span role="columnheader">변경 후</span>
                        </div>
                        {r.detail.map((f) => {
                            const changed = f.before !== f.after;
                            return (
                                <div key={f.label} role="row">
                                    <b role="rowheader">{f.label}</b>
                                    <span role="cell" className={changed ? styles.chgBefore : ''}>
                                        {f.before}
                                    </span>
                                    <span role="cell" className={changed ? styles.chgAfter : ''}>
                                        {editAfter?.(k, f.label) ?? f.after}
                                    </span>
                                </div>
                            );
                        })}
                    </div>
                </div>
            ))}
        </div>
    );
}

/** 확인 필요 사항처럼 글 전체를 고치는 곳: 원본 | 수정 내역 두 열 */
export function TextCompare({
    original,
    edited,
    children,
}: {
    original: string;
    edited: string;
    /** 편집 중이면 수정 내역 칸에 넣을 글 칸 */
    children?: ReactNode;
}) {
    const changed = original.trim() !== edited.trim();
    return (
        <div className={`${styles.compare} ${styles.compareTwo}`} role="table">
            <div role="row" className={styles.compareHead}>
                <span role="columnheader">원본</span>
                <span role="columnheader">수정 내역</span>
            </div>
            <div role="row">
                <span role="cell" className={changed ? styles.chgBefore : ''}>
                    {original}
                </span>
                <span role="cell" className={changed ? styles.chgAfter : ''}>
                    {children ?? edited}
                </span>
            </div>
        </div>
    );
}

/** APP 본인 확인: operation마다 현재를 위, 변경 후를 아래에 둔다. */
export function ChangeCards({ rows }: { rows: DiffRow[] }) {
    return (
        <div className="stack s6" aria-label="변경 항목">
            {rows.map((r) => (
                <article key={r.key} className={styles.card}>
                    <div className="row nw">
                        <span className="code">{r.code}</span>
                        <b className="grow small">{r.target}</b>
                        <Chip
                            kind={r.kind === 'add' ? 'ok' : r.kind === 'cancel' ? 'bad' : 'accent'}
                        >
                            {KIND_LABEL[r.kind]}
                        </Chip>
                    </div>
                    <div className="row" aria-label="바뀐 필드">
                        {r.fields.map((f) => (
                            <Chip key={f.label}>
                                <Icon name={FIELD_ICON[f.label]} /> {f.label}
                            </Chip>
                        ))}
                    </div>
                    {r.fields.length ? (
                        <div className={styles.cardDiff}>
                            <b className="xs muted">현재</b>
                            {r.fields.map((f) => (
                                <p key={f.label} className={styles.before}>
                                    {f.label}: {f.before}
                                </p>
                            ))}
                            <span className={styles.arrow}>현재 → 변경 후</span>
                            <b className="xs">변경 후</b>
                            {r.fields.map((f) => (
                                <p
                                    key={f.label}
                                    className={r.kind === 'add' ? styles.added : styles.after}
                                >
                                    {f.label}: {f.after}
                                </p>
                            ))}
                        </div>
                    ) : (
                        <p className="small muted">변경되는 필드가 없습니다.</p>
                    )}
                </article>
            ))}
        </div>
    );
}
