import { createPortal } from 'react-dom';
import { useId, useState, type ReactNode } from 'react';
import type { Draft, Ref } from './contract';
import { codePoints, refId } from './contract';
import { fromLocalInput, toLocalInput } from '../event-admin/time';
import { Icon } from '../../shared/ui/Icon';
import styles from './workspace.module.css';
import report from './report.module.css';

/** 수행 단계 칸은 한 줄이 한 단계다 */
const LINE_BREAK = /\r?\n/;

/**
 * 문서 안 입력 칸. 이름표는 숨기고(읽기 도구에는 남는다) 점선 칸만 보인다.
 * max가 있으면 비어 있을 때 칸 안 오른쪽 아래에 제한 글자 수를 흐리게 보이고, 넘치면 칸 테두리를
 * 빨간 점선으로 바꾸고 칸 바깥 오른쪽 아래에 '현재/제한'을 빨갛게 보인다(저장 검사와 같은 code point 기준).
 * perLine이면 한 줄(수행 단계 하나)마다 제한하고 가장 긴 줄을 센다.
 * compact인 여러 줄 칸은 한 줄 높이에서 시작해 3줄까지 늘고, 그 뒤로는 칸 안에서 스크롤한다.
 */
function Text({
    label,
    value,
    change,
    multiline = false,
    compact = false,
    max,
    perLine = false,
}: {
    label: string;
    value: string;
    change: (value: string) => void;
    multiline?: boolean;
    compact?: boolean;
    max?: number;
    perLine?: boolean;
}) {
    const id = useId();
    const count = perLine
        ? Math.max(0, ...value.split(LINE_BREAK).map(codePoints))
        : codePoints(value);
    const over = max !== undefined && count > max;
    return (
        <label
            className={styles.paperField}
            htmlFor={id}
            data-over={over || undefined}
            data-compact={compact || undefined}
        >
            <span className="sr-only">{label}</span>
            {multiline ? (
                <textarea
                    id={id}
                    aria-label={label}
                    aria-invalid={over || undefined}
                    value={value}
                    onChange={(e) => change(e.target.value)}
                />
            ) : (
                <input
                    id={id}
                    aria-label={label}
                    aria-invalid={over || undefined}
                    value={value}
                    onChange={(e) => change(e.target.value)}
                />
            )}
            {max !== undefined && !value && (
                <span className={styles.paperLimit} aria-hidden="true">
                    {perLine ? `한 줄 ${max}자` : `${max}자`}
                </span>
            )}
            {over && (
                <span className={styles.paperOver} role="alert">
                    {count}/{max}
                </span>
            )}
        </label>
    );
}
/** 시각은 날짜·시각 선택기로 받고 이 기기 시간대 오프셋을 붙여 저장한다(#387). 덜 고르면 빈 값이다. */
function When({
    label,
    value,
    change,
}: {
    label: string;
    value: string;
    change: (value: string) => void;
}) {
    const id = useId();
    return (
        <label className={styles.paperField} htmlFor={id}>
            <span className="sr-only">{label}</span>
            <input
                id={id}
                type="datetime-local"
                aria-label={label}
                value={toLocalInput(value)}
                onChange={(e) => change(fromLocalInput(e.target.value) ?? '')}
            />
        </label>
    );
}
function Select({
    label,
    value,
    items,
    change,
    nullable = false,
}: {
    label: string;
    value: string | null;
    items: (Ref & { name?: string; title?: string })[];
    change: (v: string) => void;
    nullable?: boolean;
}) {
    const id = useId();
    return (
        <label className={styles.paperField} htmlFor={id}>
            <span className="sr-only">{label}</span>
            <select
                id={id}
                aria-label={label}
                value={value ?? ''}
                onChange={(e) => change(e.target.value)}
            >
                <option value="" disabled={!nullable}>
                    {nullable ? '없음' : '선택해주세요'}
                </option>
                {items.map((item) => (
                    <option key={refId(item)} value={refId(item)}>
                        {item.name ?? item.title}
                    </option>
                ))}
            </select>
        </label>
    );
}

/** 'YYYY-MM-DD HH:mm'(이 기기 시간대). 비었거나 읽을 수 없으면 '미정' */
function when(iso: string) {
    if (!iso) return '미정';
    const local = toLocalInput(iso);
    return local.includes('NaN') ? '미정' : local.replace('T', ' ');
}
function span(start: string, end: string) {
    if (!start && !end) return '미정';
    const a = when(start);
    const b = when(end);
    // 같은 날이면 끝 시각은 시:분만
    return a.slice(0, 10) === b.slice(0, 10) ? `${a} ~ ${b.slice(11)}` : `${a} ~ ${b}`;
}

/** 지우기(✕) */
function Remove({ label, onClick }: { label: string; onClick: () => void }) {
    return (
        <button type="button" className={styles.paperRemove} aria-label={label} onClick={onClick}>
            ✕
        </button>
    );
}
/** '+추가'(편집 중에만). 구획 제목 줄 오른쪽 끝에 둘 때는 short로 '+추가'만 보인다 */
function Add({
    label,
    disabled,
    short = false,
    onClick,
}: {
    label: string;
    disabled?: boolean;
    short?: boolean;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            className={styles.paperAdd}
            aria-label={label}
            disabled={disabled}
            onClick={onClick}
        >
            {short ? '+추가' : `+ ${label}`}
        </button>
    );
}

/**
 * 계획 초안을 A4 문서처럼 보인다: 가운데 공연 이름, 오른쪽 총괄자, 1. 직무 그룹 / 2. 공연 순서 / 3. 업무 /
 * 4. 공통 안내. 아래 고정 바의 '편집'을 누르면 각 자리가 점선 입력 칸이 되어 더하고 고치고 지울 수 있다.
 * 구역은 평면도에 올린 구역을 따르므로 여기서 고치지 않는다(문서 위 평면도 오른쪽 표에서 본다).
 * 고친 내용은 같은 바의 저장(부모가 actions로 넘긴다)으로 저장한다.
 */
export function DraftEditor({
    value,
    change,
    disabled,
    title,
    owner,
    note,
    actions,
    barSlot,
}: {
    value: Draft;
    change: (value: Draft) => void;
    disabled: boolean;
    /** 문서 제목(공연 이름) */
    title: string;
    /** 총괄자 이름 */
    owner?: string | null;
    /** 아래 고정 바 왼쪽의 안내(저장 거절 사유 등) */
    note?: ReactNode;
    /** 아래 고정 바에서 편집 버튼 뒤에 둘 버튼(편집 취소·저장 등) */
    actions?: ReactNode;
    /** 주면 편집·저장 바를 카드 위가 아니라 이 자리(예: 등록 화면 탭 줄 오른쪽)에 그린다 */
    barSlot?: HTMLElement | null;
}) {
    // 편집을 시작할 때의 초안. '취소'하면 이 상태로 돌린다
    const [snapshot, setSnapshot] = useState<Draft | null>(null);
    const editing = snapshot !== null && !disabled;
    // 업무는 이름만 나열하고, 누른 업무만 상세 표를 펼친다(편집 중에도 같다)
    const [openTasks, setOpenTasks] = useState<Set<string>>(() => new Set());
    const toggleTask = (id: string) =>
        setOpenTasks((old) => {
            const next = new Set(old);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
    const edit = (work: (draft: Draft) => void) => {
        const next = structuredClone(value);
        work(next);
        change(next);
    };
    const tempId = () => `tmp-${crypto.randomUUID()}`;
    const nameOf = (items: (Ref & { name: string })[], id: string | null) =>
        (id && items.find((x) => refId(x) === id)?.name) || '없음';

    // 편집·저장 바: 안내 | 편집(편집 중에는 편집 완료·취소) | 부모가 넘긴 버튼(편집 취소·저장).
    // 기본은 카드 맨 위에 두고, barSlot을 받으면 그 자리에 그린다
    const bar = (
        <div className={barSlot ? report.barInline : report.reportBar}>
            {note}
            {editing ? (
                <>
                    <button
                        type="button"
                        className={report.barIcon}
                        data-primary=""
                        aria-label="편집 완료"
                        title="편집 완료"
                        onClick={() => setSnapshot(null)}
                    >
                        <Icon name="check" />
                    </button>
                    <button
                        type="button"
                        className={report.barIcon}
                        aria-label="편집 내용 되돌리기"
                        title="편집 내용 되돌리기"
                        onClick={() => {
                            if (snapshot) change(snapshot);
                            setSnapshot(null);
                        }}
                    >
                        <Icon name="close" />
                    </button>
                </>
            ) : (
                <button
                    type="button"
                    className={report.barIcon}
                    aria-label="편집"
                    title="편집"
                    disabled={disabled}
                    onClick={() => setSnapshot(structuredClone(value))}
                >
                    <Icon name="edit" />
                </button>
            )}
            {actions}
        </div>
    );

    return (
        <article className={styles.paper} aria-label="계획 초안">
            {barSlot ? createPortal(bar, barSlot) : bar}
            <div className={styles.paperScroll}>
                <header className={styles.paperHead}>
                    <h2 className={styles.paperTitle}>{title}</h2>
                </header>
                {owner && <p className={styles.paperOwner}>총괄자: {owner}</p>}

                <fieldset
                    disabled={disabled}
                    className={styles.paperBody}
                    data-editing={editing || undefined}
                >
                    <legend className="sr-only">계획 초안 편집</legend>

                    <section className={styles.paperSection} aria-label="직무 그룹">
                        <div className={styles.paperSectionHead}>
                            <h3>1. 직무 그룹</h3>
                            {editing && (
                                <Add
                                    label="그룹 추가"
                                    short
                                    disabled={value.workGroups.length >= 31}
                                    onClick={() =>
                                        edit((d) => {
                                            d.workGroups.push({
                                                tempId: tempId(),
                                                name: '',
                                                instructions: '',
                                            });
                                        })
                                    }
                                />
                            )}
                        </div>
                        {value.workGroups.length ? (
                            <table className={styles.paperTable}>
                                <thead>
                                    <tr>
                                        <th scope="col">그룹</th>
                                        <th scope="col">안내</th>
                                        {editing && <th scope="col" aria-label="지우기" />}
                                    </tr>
                                </thead>
                                <tbody>
                                    {value.workGroups.map((g, i) => (
                                        <tr key={refId(g)}>
                                            <td className={styles.paperKey}>
                                                {editing ? (
                                                    <Text
                                                        label="그룹 이름"
                                                        max={20}
                                                        value={g.name}
                                                        change={(v) =>
                                                            edit((d) => {
                                                                d.workGroups[i].name = v;
                                                            })
                                                        }
                                                    />
                                                ) : (
                                                    g.name || '이름 없음'
                                                )}
                                            </td>
                                            <td>
                                                {editing ? (
                                                    <Text
                                                        label="그룹 안내"
                                                        value={g.instructions}
                                                        multiline
                                                        compact
                                                        change={(v) =>
                                                            edit((d) => {
                                                                d.workGroups[i].instructions = v;
                                                            })
                                                        }
                                                    />
                                                ) : (
                                                    <span className={styles.paperText}>
                                                        {g.instructions || '-'}
                                                    </span>
                                                )}
                                            </td>
                                            {editing && (
                                                <td className={styles.paperRemoveCell}>
                                                    <Remove
                                                        label="그룹 삭제"
                                                        onClick={() =>
                                                            edit((d) => {
                                                                d.workGroups.splice(i, 1);
                                                            })
                                                        }
                                                    />
                                                </td>
                                            )}
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        ) : (
                            <p className={styles.paperEmpty}>없음</p>
                        )}
                    </section>

                    <section className={styles.paperSection} aria-label="공연 순서">
                        <div className={styles.paperSectionHead}>
                            <h3>2. 공연 순서</h3>
                            {editing && (
                                <Add
                                    label="공연 순서 추가"
                                    short
                                    disabled={value.programs.length >= 20}
                                    onClick={() =>
                                        edit((d) => {
                                            d.programs.push({
                                                tempId: tempId(),
                                                name: '',
                                                zoneId: null,
                                                startAt: '',
                                                endAt: '',
                                            });
                                        })
                                    }
                                />
                            )}
                        </div>
                        {value.programs.length ? (
                            <table className={styles.paperTable}>
                                <thead>
                                    <tr>
                                        <th scope="col">순서</th>
                                        <th scope="col">구역</th>
                                        <th scope="col">시간</th>
                                        {editing && <th scope="col" aria-label="지우기" />}
                                    </tr>
                                </thead>
                                <tbody>
                                    {value.programs.map((p, i) => (
                                        <tr key={refId(p)}>
                                            <td className={styles.paperKey}>
                                                {editing ? (
                                                    <Text
                                                        label="공연 순서 이름"
                                                        max={20}
                                                        value={p.name}
                                                        change={(v) =>
                                                            edit((d) => {
                                                                d.programs[i].name = v;
                                                            })
                                                        }
                                                    />
                                                ) : (
                                                    p.name || '이름 없음'
                                                )}
                                            </td>
                                            <td>
                                                {editing ? (
                                                    <Select
                                                        label="공연 구역"
                                                        value={p.zoneId}
                                                        items={value.zones}
                                                        nullable
                                                        change={(v) =>
                                                            edit((d) => {
                                                                d.programs[i].zoneId = v || null;
                                                            })
                                                        }
                                                    />
                                                ) : (
                                                    nameOf(value.zones, p.zoneId)
                                                )}
                                            </td>
                                            <td>
                                                {editing ? (
                                                    <div className={styles.paperRange}>
                                                        <When
                                                            label="공연 시작 시각"
                                                            value={p.startAt}
                                                            change={(v) =>
                                                                edit((d) => {
                                                                    d.programs[i].startAt = v;
                                                                })
                                                            }
                                                        />
                                                        <span aria-hidden="true">~</span>
                                                        <When
                                                            label="공연 종료 시각"
                                                            value={p.endAt}
                                                            change={(v) =>
                                                                edit((d) => {
                                                                    d.programs[i].endAt = v;
                                                                })
                                                            }
                                                        />
                                                    </div>
                                                ) : (
                                                    <span className="mono">
                                                        {span(p.startAt, p.endAt)}
                                                    </span>
                                                )}
                                            </td>
                                            {editing && (
                                                <td className={styles.paperRemoveCell}>
                                                    <Remove
                                                        label="공연 순서 삭제"
                                                        onClick={() =>
                                                            edit((d) => {
                                                                d.programs.splice(i, 1);
                                                            })
                                                        }
                                                    />
                                                </td>
                                            )}
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        ) : (
                            <p className={styles.paperEmpty}>없음</p>
                        )}
                    </section>

                    <section className={styles.paperSection} aria-label="업무">
                        <div className={styles.paperSectionHead}>
                            <h3>3. 업무</h3>
                            {editing && (
                                <Add
                                    label="업무 추가"
                                    short
                                    disabled={value.tasks.length >= 200}
                                    onClick={() => {
                                        // 새 업무는 바로 펼쳐 칸을 채울 수 있게 한다
                                        const id = tempId();
                                        setOpenTasks((old) => new Set(old).add(id));
                                        edit((d) => {
                                            d.tasks.push({
                                                tempId: id,
                                                workGroupId: refId(d.workGroups[0] ?? {}),
                                                zoneId: refId(d.zones[0] ?? {}),
                                                programId: null,
                                                bundleKey: '',
                                                title: '',
                                                startAt: '',
                                                endAt: '',
                                                steps: [''],
                                                checklist: [],
                                            });
                                        });
                                    }}
                                />
                            )}
                        </div>
                        {!value.tasks.length && <p className={styles.paperEmpty}>없음</p>}
                        {value.tasks.map((t, i) => (
                            <div key={refId(t)} className={styles.paperTask}>
                                <div className={styles.paperTaskHead}>
                                    <button
                                        type="button"
                                        className={styles.paperTaskToggle}
                                        aria-expanded={openTasks.has(refId(t))}
                                        onClick={() => toggleTask(refId(t))}
                                    >
                                        <span aria-hidden="true" className={styles.paperCaret}>
                                            ▸
                                        </span>
                                        업무 {i + 1}. {t.title || '제목 없음'}
                                    </button>
                                    {editing && (
                                        <Remove
                                            label="업무 삭제"
                                            onClick={() =>
                                                edit((d) => {
                                                    d.tasks.splice(i, 1);
                                                })
                                            }
                                        />
                                    )}
                                </div>
                                {openTasks.has(refId(t)) && (
                                    <table className={styles.taskTable}>
                                        <thead>
                                            <tr>
                                                <th scope="col">항목</th>
                                                <th scope="col">내용</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            <tr>
                                                <th scope="row">업무</th>
                                                <td>
                                                    {editing ? (
                                                        <Text
                                                            label="업무 제목"
                                                            max={100}
                                                            value={t.title}
                                                            change={(v) =>
                                                                edit((d) => {
                                                                    d.tasks[i].title = v;
                                                                })
                                                            }
                                                        />
                                                    ) : (
                                                        t.title || '제목 없음'
                                                    )}
                                                </td>
                                            </tr>
                                            <tr>
                                                <th scope="row">그룹</th>
                                                <td>
                                                    {editing ? (
                                                        <Select
                                                            label="업무 그룹"
                                                            value={t.workGroupId}
                                                            items={value.workGroups}
                                                            change={(v) =>
                                                                edit((d) => {
                                                                    d.tasks[i].workGroupId = v;
                                                                })
                                                            }
                                                        />
                                                    ) : (
                                                        nameOf(value.workGroups, t.workGroupId)
                                                    )}
                                                </td>
                                            </tr>
                                            <tr>
                                                <th scope="row">구역</th>
                                                <td>
                                                    {editing ? (
                                                        <Select
                                                            label="업무 구역"
                                                            value={t.zoneId}
                                                            items={value.zones}
                                                            change={(v) =>
                                                                edit((d) => {
                                                                    d.tasks[i].zoneId = v;
                                                                })
                                                            }
                                                        />
                                                    ) : (
                                                        nameOf(value.zones, t.zoneId)
                                                    )}
                                                </td>
                                            </tr>
                                            <tr>
                                                <th scope="row">공연 순서</th>
                                                <td>
                                                    {editing ? (
                                                        <Select
                                                            label="연결 공연 순서"
                                                            value={t.programId}
                                                            items={value.programs}
                                                            nullable
                                                            change={(v) =>
                                                                edit((d) => {
                                                                    d.tasks[i].programId =
                                                                        v || null;
                                                                })
                                                            }
                                                        />
                                                    ) : (
                                                        nameOf(value.programs, t.programId)
                                                    )}
                                                </td>
                                            </tr>
                                            <tr>
                                                <th scope="row">묶음</th>
                                                <td>
                                                    {editing ? (
                                                        <Text
                                                            label="업무 묶음 이름"
                                                            value={t.bundleKey}
                                                            change={(v) =>
                                                                edit((d) => {
                                                                    d.tasks[i].bundleKey = v;
                                                                })
                                                            }
                                                        />
                                                    ) : (
                                                        t.bundleKey || '-'
                                                    )}
                                                </td>
                                            </tr>
                                            <tr>
                                                <th scope="row">시간</th>
                                                <td>
                                                    {editing ? (
                                                        <div className={styles.paperRange}>
                                                            <When
                                                                label="업무 시작 시각"
                                                                value={t.startAt}
                                                                change={(v) =>
                                                                    edit((d) => {
                                                                        d.tasks[i].startAt = v;
                                                                    })
                                                                }
                                                            />
                                                            <span aria-hidden="true">~</span>
                                                            <When
                                                                label="업무 종료 시각"
                                                                value={t.endAt}
                                                                change={(v) =>
                                                                    edit((d) => {
                                                                        d.tasks[i].endAt = v;
                                                                    })
                                                                }
                                                            />
                                                        </div>
                                                    ) : (
                                                        <span className="mono">
                                                            {span(t.startAt, t.endAt)}
                                                        </span>
                                                    )}
                                                </td>
                                            </tr>
                                            <tr>
                                                <th scope="row">절차</th>
                                                <td>
                                                    {editing ? (
                                                        <Text
                                                            label="수행 단계 (한 줄에 한 단계, 최대6개)"
                                                            max={120}
                                                            perLine
                                                            multiline
                                                            value={t.steps.join('\n')}
                                                            change={(v) =>
                                                                edit((d) => {
                                                                    d.tasks[i].steps =
                                                                        v.split('\n');
                                                                })
                                                            }
                                                        />
                                                    ) : t.steps.some((s) => s.trim()) ? (
                                                        <ul className={styles.paperList}>
                                                            {t.steps
                                                                .filter((s) => s.trim())
                                                                .map((s, si) => (
                                                                    <li key={si}>{s}</li>
                                                                ))}
                                                        </ul>
                                                    ) : (
                                                        '-'
                                                    )}
                                                </td>
                                            </tr>
                                            <tr>
                                                <th scope="row">점검 항목</th>
                                                <td>
                                                    {!editing &&
                                                        (t.checklist.length ? (
                                                            <ul className={styles.paperList}>
                                                                {t.checklist.map((c) => (
                                                                    <li key={c.key}>
                                                                        {c.text}
                                                                        {c.required && (
                                                                            <span
                                                                                className={
                                                                                    styles.paperReq
                                                                                }
                                                                            >
                                                                                {' '}
                                                                                (필수)
                                                                            </span>
                                                                        )}
                                                                    </li>
                                                                ))}
                                                            </ul>
                                                        ) : (
                                                            '-'
                                                        ))}
                                                    {editing && (
                                                        <div className={styles.paperChecks}>
                                                            {t.checklist.map((c, ci) => (
                                                                <div
                                                                    className={styles.checkRow}
                                                                    key={c.key}
                                                                >
                                                                    <Text
                                                                        label="점검 항목"
                                                                        max={80}
                                                                        value={c.text}
                                                                        change={(v) =>
                                                                            edit((d) => {
                                                                                d.tasks[
                                                                                    i
                                                                                ].checklist[
                                                                                    ci
                                                                                ].text = v;
                                                                            })
                                                                        }
                                                                    />
                                                                    <label className="check">
                                                                        <input
                                                                            type="checkbox"
                                                                            checked={c.required}
                                                                            onChange={(e) =>
                                                                                edit((d) => {
                                                                                    d.tasks[
                                                                                        i
                                                                                    ].checklist[
                                                                                        ci
                                                                                    ].required =
                                                                                        e.target.checked;
                                                                                })
                                                                            }
                                                                        />{' '}
                                                                        필수 확인
                                                                    </label>
                                                                    <Remove
                                                                        label="점검 항목 삭제"
                                                                        onClick={() =>
                                                                            edit((d) => {
                                                                                d.tasks[
                                                                                    i
                                                                                ].checklist.splice(
                                                                                    ci,
                                                                                    1,
                                                                                );
                                                                            })
                                                                        }
                                                                    />
                                                                </div>
                                                            ))}
                                                            <Add
                                                                label="점검 항목 추가"
                                                                disabled={t.checklist.length >= 6}
                                                                onClick={() =>
                                                                    edit((d) => {
                                                                        d.tasks[i].checklist.push({
                                                                            key: crypto.randomUUID(),
                                                                            text: '',
                                                                            required: false,
                                                                        });
                                                                    })
                                                                }
                                                            />
                                                        </div>
                                                    )}
                                                </td>
                                            </tr>
                                        </tbody>
                                    </table>
                                )}
                            </div>
                        ))}
                    </section>

                    <section className={styles.paperSection} aria-label="공통 안내">
                        <div className={styles.paperSectionHead}>
                            <h3>4. 공통 안내</h3>
                            {editing && (
                                <Add
                                    label="공통 안내 추가"
                                    short
                                    onClick={() =>
                                        edit((d) => {
                                            d.instructions.push({
                                                scope: 'EVENT',
                                                workGroupId: null,
                                                body: '',
                                            });
                                        })
                                    }
                                />
                            )}
                        </div>
                        {!value.instructions.length && <p className={styles.paperEmpty}>없음</p>}
                        {value.instructions.length > 0 && (
                            <ol className={styles.paperNotes}>
                                {value.instructions.map((item, i) => (
                                    <li key={i}>
                                        {editing ? (
                                            <div className={styles.paperNoteEdit}>
                                                <Text
                                                    label="공통 안내"
                                                    value={item.body}
                                                    multiline
                                                    change={(v) =>
                                                        edit((d) => {
                                                            d.instructions[i].body = v;
                                                        })
                                                    }
                                                />
                                                <Remove
                                                    label="공통 안내 삭제"
                                                    onClick={() =>
                                                        edit((d) => {
                                                            d.instructions.splice(i, 1);
                                                        })
                                                    }
                                                />
                                            </div>
                                        ) : (
                                            <span className={styles.paperText}>
                                                {item.body || '-'}
                                            </span>
                                        )}
                                    </li>
                                ))}
                            </ol>
                        )}
                    </section>
                </fieldset>
            </div>
        </article>
    );
}
