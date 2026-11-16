import { useData, useEvent } from '../../domain/hooks';
import { diffRows, groupName, isValidTimeRange, type DiffRow } from '../../domain/selectors';
import type { Change, Slot } from '../../domain/types';
import { ChangeTable } from './ChangeDiff';
import { StepsText } from './StepsText';
import { canEdit, opsFromDraft, type ChangeDraft } from './change-doc';
import styles from './change.module.css';

/**
 * 확정 전 변경안 편집(X01·총괄): 전후 비교표의 "변경 후" 칸에서 시간·담당·절차를 바로 고친다.
 * 고치는 대로 비교표가 다시 계산되어 현재 값과 수정 내역을 나란히 보여 준다. 담당은 여러 명 둘 수 있다.
 */
export function ChangeEditor({
    change,
    draft,
    onChange,
}: {
    change: Change;
    draft: ChangeDraft;
    onChange: (next: Partial<ChangeDraft>) => void;
}) {
    const d = useData();
    const ev = useEvent();
    const workers = d.members.filter((m) => m.role === 'WORKER');
    const rows = diffRows(d, ev, { ...change, ops: opsFromDraft(d, draft) });
    const setSlot = (k: number, patch: Partial<Slot>) =>
        onChange({
            ops: draft.ops.map((o, i) =>
                i !== k || o.kind === 'cancel'
                    ? o
                    : ({ ...o, after: { ...o.after, ...patch } } as typeof o),
            ),
        });

    const peopleCell = (k: number, after: Slot) => {
        // 추가 담당을 받지 않는 연산(실서버)은 담당 한 명만 고른다
        const more = canEdit(draft.ops[k], '추가 담당');
        const extras = more ? (after.extraAssignees ?? []) : [];
        const people = [after.assignee ?? '', ...extras];
        return (
            <div className={styles.people}>
                {people.map((who, i) => (
                    <div key={i} className={styles.person}>
                        <select
                            className="inp"
                            aria-label={`${rows[k].target} 담당 ${i + 1}`}
                            value={who}
                            onChange={(e) =>
                                setSlot(
                                    k,
                                    i === 0
                                        ? { assignee: e.target.value || null }
                                        : {
                                              extraAssignees: extras.map((x, j) =>
                                                  j === i - 1 ? e.target.value : x,
                                              ),
                                          },
                                )
                            }
                        >
                            <option value="">{i === 0 ? '미배정' : '담당 선택'}</option>
                            {workers
                                .filter((m) => who === m.id || !people.includes(m.id))
                                .map((m) => (
                                    <option key={m.id} value={m.id}>
                                        {m.name} / {groupName(d, m.groupId)}
                                        {m.available ? '' : ' (지금 불가)'}
                                    </option>
                                ))}
                        </select>
                        {i > 0 && (
                            <button
                                type="button"
                                className="btn sm ghost"
                                aria-label={`담당 ${i + 1} 빼기`}
                                onClick={() =>
                                    setSlot(k, {
                                        extraAssignees: extras.filter((_, j) => j !== i - 1),
                                    })
                                }
                            >
                                ✕
                            </button>
                        )}
                    </div>
                ))}
                {more && (
                    <button
                        type="button"
                        className={`btn sm ${styles.addPerson}`}
                        disabled={people.filter(Boolean).length >= workers.length}
                        onClick={() => setSlot(k, { extraAssignees: [...extras, ''] })}
                    >
                        + 담당 추가
                    </button>
                )}
            </div>
        );
    };

    const editAfter = (k: number, label: DiffRow['detail'][number]['label']) => {
        const o = draft.ops[k];
        if (o.kind === 'cancel') return null;
        if (label === '시간' && canEdit(o, '시간'))
            return (
                <>
                    <div className={styles.times}>
                        <input
                            type="time"
                            className="inp"
                            aria-label={`${rows[k].target} 시작`}
                            value={o.after.start}
                            onChange={(e) => setSlot(k, { start: e.target.value })}
                        />
                        <span>~</span>
                        <input
                            type="time"
                            className="inp"
                            aria-label={`${rows[k].target} 종료`}
                            value={o.after.end}
                            onChange={(e) => setSlot(k, { end: e.target.value })}
                        />
                    </div>
                    {!isValidTimeRange(o.after.start, o.after.end) && (
                        <span className="xs urgent">
                            시작/종료 시간을 입력하고 종료를 시작보다 늦게 정해주세요
                        </span>
                    )}
                </>
            );
        if (o.kind === 'program') return null;
        if (label === '담당' && canEdit(o, '담당')) return peopleCell(k, o.after);
        if (label === '절차' && canEdit(o, '절차'))
            return (
                <StepsText
                    label={`${rows[k].target} 절차`}
                    value={draft.steps[k]}
                    onChange={(next) =>
                        onChange({ steps: draft.steps.map((s, i) => (i === k ? next : s)) })
                    }
                />
            );
        return null;
    };

    return <ChangeTable rows={rows} editAfter={editAfter} />;
}
