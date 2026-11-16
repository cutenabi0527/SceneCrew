import { useId, useState } from 'react';
import { Dialog } from '../../shared/ui/Dialog';
import { Icon } from '../../shared/ui/Icon';
import styles from './workspace.module.css';

type Fields = Record<string, string>;

/** AI 조건 카드: 맨 앞 3개만 보이고, 누르면 전체를 팝업에서 본다 */
export function ConditionSummary({
    fields,
    labelOf,
    disabled,
    onOpen,
}: {
    fields: Fields;
    labelOf: (key: string) => string;
    disabled?: boolean;
    onOpen: () => void;
}) {
    const entries = Object.entries(fields);
    if (!entries.length)
        return (
            <button
                type="button"
                className={styles.condEmpty}
                aria-label="AI 조건 열기"
                disabled={disabled}
                onClick={onOpen}
            >
                + 조건 추가
            </button>
        );
    return (
        <div className={styles.condSummary}>
            {entries.slice(0, 3).map(([key, value]) => (
                <button
                    key={key}
                    type="button"
                    className={styles.condRow}
                    disabled={disabled}
                    onClick={onOpen}
                >
                    <b>{labelOf(key)}</b>
                    <span>{value || '내용 없음'}</span>
                </button>
            ))}
            <button
                type="button"
                className={styles.condMore}
                aria-label="AI 조건 열기"
                disabled={disabled}
                onClick={onOpen}
            >
                전체 {entries.length}개 보기
            </button>
        </div>
    );
}

/**
 * AI 조건 전체(크기 고정 팝업): 조건 이름을 누르면 내용이 펼쳐지는 목록, 맨 아래 고정된 입력 카드.
 * 더하기·고치기·지우기는 누르는 즉시 저장한다(onSave). removed는 일부러 지운 조건 이름이다.
 */
export function ConditionsDialog({
    fields,
    labelOf,
    busy,
    onSave,
    onClose,
}: {
    fields: Fields;
    labelOf: (key: string) => string;
    busy: boolean;
    onSave: (next: Fields, removed: string[]) => void;
    onClose: () => void;
}) {
    const [open, setOpen] = useState<Set<string>>(() => new Set());
    const [editing, setEditing] = useState<{ key: string; value: string } | null>(null);
    const [name, setName] = useState('');
    const [value, setValue] = useState('');
    const nameId = useId();
    const valueId = useId();
    const entries = Object.entries(fields);
    const toggle = (key: string) =>
        setOpen((old) => {
            const next = new Set(old);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            return next;
        });

    return (
        <Dialog title="AI 조건" className={styles.condDialog} onClose={onClose}>
            <div className={styles.condBody}>
                <ul className={styles.condList} aria-label="입력한 조건">
                    {!entries.length && <li className={styles.condNone}>아직 조건이 없습니다.</li>}
                    {entries.map(([key, text]) => {
                        const label = labelOf(key);
                        const expanded = open.has(key);
                        const isEditing = editing?.key === key;
                        return (
                            <li key={key} className={styles.condItem}>
                                <div className={styles.condItemHead}>
                                    <button
                                        type="button"
                                        className={styles.condToggle}
                                        aria-expanded={expanded}
                                        onClick={() => toggle(key)}
                                    >
                                        <span aria-hidden="true" className={styles.paperCaret}>
                                            ▸
                                        </span>
                                        {label}
                                    </button>
                                    <button
                                        type="button"
                                        className={styles.refEdit}
                                        aria-label={`${label} 수정`}
                                        disabled={busy}
                                        onClick={() => {
                                            setOpen((old) => new Set(old).add(key));
                                            setEditing({ key, value: text });
                                        }}
                                    >
                                        <Icon name="edit" />
                                    </button>
                                    <button
                                        type="button"
                                        className={styles.refRemove}
                                        aria-label={`${label} 삭제`}
                                        disabled={busy}
                                        onClick={() => {
                                            const next = { ...fields };
                                            delete next[key];
                                            if (isEditing) setEditing(null);
                                            onSave(next, [key]);
                                        }}
                                    >
                                        <Icon name="trash" />
                                    </button>
                                </div>
                                {expanded &&
                                    (isEditing ? (
                                        <div className={styles.condEdit}>
                                            <textarea
                                                aria-label={label}
                                                value={editing.value}
                                                onChange={(e) =>
                                                    setEditing({ key, value: e.target.value })
                                                }
                                            />
                                            <div className={styles.actions}>
                                                <button
                                                    type="button"
                                                    className="btn sm"
                                                    onClick={() => setEditing(null)}
                                                >
                                                    취소
                                                </button>
                                                <button
                                                    type="button"
                                                    className="btn sm primary"
                                                    disabled={busy}
                                                    onClick={() => {
                                                        onSave(
                                                            { ...fields, [key]: editing.value },
                                                            [],
                                                        );
                                                        setEditing(null);
                                                    }}
                                                >
                                                    저장
                                                </button>
                                            </div>
                                        </div>
                                    ) : (
                                        <p className={styles.condText}>{text || '내용 없음'}</p>
                                    ))}
                            </li>
                        );
                    })}
                </ul>
                {/* 맨 아래 고정: 새 조건 입력(같은 이름이 있으면 그 내용을 바꾼다) */}
                <form
                    className={styles.condForm}
                    onSubmit={(e) => {
                        e.preventDefault();
                        const key = name.trim();
                        if (!key || busy) return;
                        onSave({ ...fields, [key]: value }, []);
                        setOpen((old) => new Set(old).add(key));
                        setName('');
                        setValue('');
                    }}
                >
                    <label className="sr-only" htmlFor={nameId}>
                        조건 이름
                    </label>
                    <input
                        id={nameId}
                        className="inp"
                        aria-label="조건 이름"
                        placeholder="조건 이름 (예: 예상 관객 수)"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                    />
                    <label className="sr-only" htmlFor={valueId}>
                        조건 내용
                    </label>
                    <textarea
                        id={valueId}
                        className="inp"
                        aria-label="조건 내용"
                        placeholder="조건 내용 (예: 약 300명, 스탠딩 포함)"
                        value={value}
                        onChange={(e) => setValue(e.target.value)}
                    />
                    <div className={styles.actions}>
                        <button
                            type="submit"
                            className="btn sm primary"
                            disabled={!name.trim() || busy}
                        >
                            저장
                        </button>
                    </div>
                </form>
            </div>
        </Dialog>
    );
}
