import { useState, type CSSProperties, type DragEvent } from 'react';
import { useData, useEvent } from '../../domain/hooks';
import {
    assignRefusal,
    familyAssignRefusal,
    groupName,
    nameOf,
    sameTaskHolders,
    taskFamily,
} from '../../domain/selectors';
import { newId, useDispatch } from '../../domain/store-context';
import type { Task } from '../../domain/types';
import { Tabs } from '../../shared/ui/atoms';
import { Icon } from '../../shared/ui/Icon';
import styles from './assign.module.css';
import { groupColor } from './group-color';
import { MIME, memberSub, movable, overlaps } from './assign-utils';

type Target = { groupId: string; people: string[] };
export type CueFlow =
    /** 이미 다른 부서에 있는 업무를 놓았을 때: 옮기기 / 두 부서 모두 / 잘못 놓음 */
    | { step: 'choice'; taskId: string; to: string }
    /** 한 부서에 배정한 뒤 그 부서 사람 고르기(여러 명) */
    | { step: 'pick'; taskId: string; groupId: string; keep: boolean }
    /** 행의 '배정' 단추: 부서 탭을 오가며 여러 부서의 사람을 함께 고른다. all이면 같은 업무를 한꺼번에 */
    | { step: 'assign'; taskId: string; all?: boolean }
    /** 같은 업무(여러 명에게 복사 배정된 업무)의 '배정' 단추: 이 업무만 / 모두 함께 바꿀지 먼저 묻는다 */
    | { step: 'family'; taskId: string }
    /** 행의 X 단추: 업무 삭제 확인(같은 업무가 여럿이면 이 업무만 / 모두) */
    | { step: 'delete'; taskId: string };

/**
 * 공연 계획 큐시트의 배정 흐름(총괄 WEB, 데모).
 *  - 큐시트 행을 오른쪽 부서 상자에 끌어 놓으면: 부서가 없거나 같은 부서면 바로 그 부서 사람 고르기,
 *    다른 부서에 있던 업무면 "옮기기 / 두 부서 모두 / 잘못 놓음"을 먼저 묻는다.
 *  - 행의 '배정' 단추: 부서 탭 + 여러 명 고르기. 고른 사람은 위에 '부서-이름 ×'로 모인다.
 * 배정은 변경안을 만들어 총괄 확정 경로로 적용한다(바뀐 사람에게 변경 확인 알림).
 */
/** 큐시트 오른쪽 칸 맨 위: 부서 상자. 큐시트 행을 끌어 놓을 자리 */
export function CueGroupBoxes({ onFlow }: { onFlow: (f: CueFlow) => void }) {
    const d = useData();
    // 부서가 없거나 같은 부서면 바로 사람 고르기, 다른 부서에 있던 업무면 먼저 어떻게 할지 묻는다
    const onDrop = (taskId: string, groupId: string) => {
        const t = d.tasks.find((x) => x.id === taskId);
        if (!t || !movable(t)) return;
        const hasGroup = d.groups.some((g) => g.id === t.groupId);
        onFlow(
            hasGroup && t.groupId !== groupId
                ? { step: 'choice', taskId, to: groupId }
                : { step: 'pick', taskId, groupId, keep: false },
        );
    };
    const ev = useEvent();
    const [over, setOver] = useState<string | null>(null);
    const editable = ev.state !== 'ENDED';
    const workers = (gid: string) =>
        d.members.filter((m) => m.role === 'WORKER' && m.groupId === gid);
    return (
        <section className={styles.cueBoxes} aria-label="부서 배정 상자">
            {d.groups.map((g) => (
                <Box
                    key={g.id}
                    title={g.name}
                    color={groupColor(d.groups, g.id)}
                    sub={`인원 ${workers(g.id).length}`}
                    tasks={d.tasks.filter((t) => t.groupId === g.id)}
                    showWho
                    data-over={over === g.id || undefined}
                    onDragOver={(e: DragEvent) => {
                        if (!editable || !e.dataTransfer.types.includes(MIME)) return;
                        e.preventDefault();
                        e.dataTransfer.dropEffect = 'move';
                        if (over !== g.id) setOver(g.id);
                    }}
                    onDragLeave={() => setOver((k) => (k === g.id ? null : k))}
                    onDrop={(e: DragEvent) => {
                        e.preventDefault();
                        setOver(null);
                        const id = e.dataTransfer.getData(MIME);
                        if (id) onDrop(id, g.id);
                    }}
                />
            ))}
        </section>
    );
}

export function CueDialogs({
    flow,
    setFlow,
}: {
    flow: CueFlow;
    setFlow: (f: CueFlow | null) => void;
}) {
    const d = useData();
    const dispatch = useDispatch();
    const t = d.tasks.find((x) => x.id === flow.taskId);
    if (!t) return null;
    const close = () => setFlow(null);
    /** 배정을 보낸다. 받을 수 없으면 보내지 않고 이유를 돌려준다(배정 팝업이 알림창으로 보여 준다) */
    const run = (targets: Target[], keep: boolean): string | null => {
        const refused = assignRefusal(d, t.id, targets, keep);
        if (refused) return refused;
        const [only] = targets;
        // 한 부서에 한 명(또는 미배정)으로 원 업무를 옮기는 경우는 실서버도 지원하는 재배정으로 보낸다.
        // 여러 명·여러 부서·두 부서 모두 배정은 데모에서만 된다(실서버는 '아직 연결되지 않음' 안내).
        if (!keep && targets.length === 1 && only.people.length <= 1)
            dispatch({
                type: 'reassignTask',
                id: newId('C'),
                taskId: t.id,
                groupId: only.groupId,
                assignee: only.people[0] ?? null,
            });
        else dispatch({ type: 'assignTask', id: newId('C'), taskId: t.id, targets, keep });
        close();
        return null;
    };

    if (flow.step === 'choice') {
        const a = groupName(d, t.groupId);
        const b = groupName(d, flow.to);
        return (
            <Scrim onClose={close} label="부서 배정 확인">
                <b>
                    {t.title} / {t.start}–{t.end}
                </b>
                <p className="small">
                    이 업무는 이미 <b>{a}</b>에 배정되어 있습니다. <b>{b}</b>에 어떻게 배정할까요?
                </p>
                <div className={styles.choiceList}>
                    <button
                        type="button"
                        className="btn"
                        autoFocus
                        onClick={() =>
                            setFlow({ step: 'pick', taskId: t.id, groupId: flow.to, keep: false })
                        }
                    >
                        {a} → {b}(으)로 변경
                    </button>
                    <button
                        type="button"
                        className="btn"
                        onClick={() =>
                            setFlow({ step: 'pick', taskId: t.id, groupId: flow.to, keep: true })
                        }
                    >
                        {a}와 {b}에 모두 배정
                    </button>
                    <button type="button" className="btn ghost" onClick={close}>
                        잘못 놓았어요 (취소)
                    </button>
                </div>
            </Scrim>
        );
    }

    if (flow.step === 'delete') {
        const family = taskFamily(d, t);
        const remove = (ids: string[]) => {
            dispatch({ type: 'deleteTasks', id: newId('C'), taskIds: ids });
            close();
        };
        return (
            <Scrim onClose={close} label="업무 삭제 확인">
                <b>
                    {t.title} / {t.start}–{t.end}
                </b>
                <p className="small">
                    {family.length > 1 ? (
                        <>
                            같은 업무가 <b>{family.length}건</b> 있습니다 (
                            {family.map((u) => nameOf(d, u.assignee)).join(', ')}). 이 업무만
                            삭제할까요, 모두 삭제할까요?
                        </>
                    ) : (
                        <>
                            <b>{nameOf(d, t.assignee)}</b>의 이 업무를 삭제할까요?
                        </>
                    )}
                </p>
                <p className="xs muted">
                    삭제하면 담당자에게 업무 취소 알림이 가고, 적용된 변경에 기록이 남습니다.
                </p>
                <div className={styles.choiceList}>
                    {family.length > 1 && (
                        <button
                            type="button"
                            className={`btn ${styles.dangerBtn}`}
                            onClick={() => remove(family.map((u) => u.id))}
                        >
                            같은 업무 {family.length}건 모두 삭제
                        </button>
                    )}
                    <button
                        type="button"
                        className={`btn ${styles.dangerBtn}`}
                        onClick={() => remove([t.id])}
                    >
                        {family.length > 1 ? `이 업무만 삭제 (${nameOf(d, t.assignee)})` : '삭제'}
                    </button>
                    <button type="button" className="btn ghost" autoFocus onClick={close}>
                        취소
                    </button>
                </div>
            </Scrim>
        );
    }

    if (flow.step === 'family') {
        const family = taskFamily(d, t);
        const names = family.map((u) => nameOf(d, u.assignee)).join(', ');
        return (
            <Scrim onClose={close} label="같은 업무 배정 확인">
                <b>
                    {t.title} / {t.start}–{t.end}
                </b>
                <p className="small">
                    같은 업무가 <b>{family.length}건</b> 있습니다 ({names}). 다른 업무들도 모두
                    똑같이 변경할까요?
                </p>
                <p className="xs muted">
                    모두 변경하면 변경안은 하나만 만들고, 바뀌는 업무를 모두 함께 기록합니다.
                </p>
                <div className={styles.choiceList}>
                    <button
                        type="button"
                        className="btn primary"
                        autoFocus
                        onClick={() => setFlow({ step: 'assign', taskId: t.id, all: true })}
                    >
                        같은 업무 {family.length}건 모두 변경
                    </button>
                    <button
                        type="button"
                        className="btn"
                        onClick={() => setFlow({ step: 'assign', taskId: t.id })}
                    >
                        이 업무만 변경 ({nameOf(d, t.assignee)})
                    </button>
                    <button type="button" className="btn ghost" onClick={close}>
                        취소
                    </button>
                </div>
            </Scrim>
        );
    }

    if (flow.step === 'pick') {
        // 같은 부서로 다시 놓았으면 지금 담당을 골라 둔 채로 연다
        const initial = !flow.keep && t.groupId === flow.groupId && t.assignee ? [t.assignee] : [];
        return (
            <PeopleDialog
                task={t}
                groupIds={[flow.groupId]}
                initial={initial}
                taken={sameTaskHolders(d, t)}
                note={
                    flow.keep
                        ? `${groupName(d, t.groupId)}의 업무는 그대로 두고 ${groupName(d, flow.groupId)}에도 같은 업무를 배정합니다.`
                        : undefined
                }
                onClose={close}
                onConfirm={(people) => run([{ groupId: flow.groupId, people }], flow.keep)}
            />
        );
    }

    // 고른 사람을 부서별로 묶는다. 지금 부서를 먼저 두어 기존 업무가 그 부서에 남게 한다
    const byGroup = (people: string[]) => {
        const order = [t.groupId, ...d.groups.map((g) => g.id).filter((g) => g !== t.groupId)];
        const groupOf = (p: string) => d.members.find((m) => m.id === p)?.groupId ?? '';
        return order
            .map((g) => ({ groupId: g, people: people.filter((p) => groupOf(p) === g) }))
            .filter((x) => x.people.length);
    };

    if (flow.step === 'assign' && flow.all) {
        // 같은 업무 모두: 지금 맡은 사람들을 골라 둔 채로 열고, 고른 사람마다 한 건씩 맡게 한다(변경안 하나)
        const family = taskFamily(d, t);
        return (
            <PeopleDialog
                task={t}
                groupIds={d.groups.map((g) => g.id)}
                initial={family.flatMap((u) => (u.assignee ? [u.assignee] : []))}
                note={`같은 업무 ${family.length}건을 한꺼번에 바꿉니다. 고른 사람마다 한 건씩 맡고, 뺀 사람의 업무는 취소됩니다.`}
                onClose={close}
                onConfirm={(people) => {
                    const targets = byGroup(people);
                    const refused = familyAssignRefusal(d, t.id, targets);
                    if (refused) return refused;
                    dispatch({ type: 'assignFamily', id: newId('C'), taskId: t.id, targets });
                    close();
                    return null;
                }}
            />
        );
    }

    return (
        <PeopleDialog
            task={t}
            groupIds={d.groups.map((g) => g.id)}
            initial={t.assignee ? [t.assignee] : []}
            taken={sameTaskHolders(d, t)}
            onClose={close}
            onConfirm={(people) => {
                const targets = byGroup(people);
                return run(targets.length ? targets : [{ groupId: t.groupId, people: [] }], false);
            }}
        />
    );
}

/** 사람 여러 명 고르기. 부서가 여럿이면 탭으로 오가고, 고른 사람은 맨 위에 '부서-이름 ×'로 모인다 */
function PeopleDialog({
    task: t,
    groupIds,
    initial,
    taken,
    note,
    onClose,
    onConfirm,
}: {
    task: Task;
    groupIds: string[];
    initial: string[];
    /** 같은 업무를 이미 맡은 사람 → 그 업무 id. 고를 수 없다 */
    taken?: Map<string, string>;
    note?: string;
    onClose: () => void;
    /** 배정을 보낸다. 받을 수 없으면 이유를 돌려준다 */
    onConfirm: (people: string[]) => string | null;
}) {
    const d = useData();
    // 배정을 받을 수 없을 때의 이유. 알림창을 닫으면 고른 사람을 그대로 둔 채 배정 팝업으로 돌아온다
    const [refused, setRefused] = useState<string | null>(null);
    const [tab, setTab] = useState(groupIds.includes(t.groupId) ? t.groupId : (groupIds[0] ?? ''));
    const [picked, setPicked] = useState<string[]>(initial);
    const people = d.members.filter((m) => m.role === 'WORKER' && m.groupId === tab);
    const toggle = (id: string) =>
        setPicked((xs) => (xs.includes(id) ? xs.filter((x) => x !== id) : [...xs, id]));
    const label = (id: string) => {
        const m = d.members.find((x) => x.id === id);
        return `${groupName(d, m?.groupId)}-${nameOf(d, id)}`;
    };
    return (
        <Scrim onClose={onClose} label="담당 배정" fixed>
            <b>
                {t.title} / {t.start}–{t.end}
            </b>
            {/* 고른 사람: 부서-이름, ×로 바로 뺀다 */}
            <div className={styles.picked} aria-label="고른 사람">
                {picked.length ? (
                    picked.map((id) => (
                        <span key={id} className={styles.pickedChip}>
                            {label(id)}
                            <button
                                type="button"
                                className={styles.pickedX}
                                aria-label={`${label(id)} 빼기`}
                                onClick={() => toggle(id)}
                            >
                                <Icon name="close" />
                            </button>
                        </span>
                    ))
                ) : (
                    <span className="xs muted">아직 고른 사람이 없습니다 (그대로 두면 미배정)</span>
                )}
            </div>
            {groupIds.length > 1 ? (
                <Tabs<string>
                    label="부서"
                    value={tab}
                    onChange={setTab}
                    items={groupIds.map((g) => [g, groupName(d, g)])}
                />
            ) : (
                <span className="small">부서: {groupName(d, tab)}</span>
            )}
            <fieldset className={styles.people}>
                <legend className="sr-only">{groupName(d, tab)} 인원</legend>
                {people.map((m) => {
                    const clash = overlaps(d.tasks, t, m.id);
                    // 배정할 수 없는 사람: 지금 불가이거나 같은 업무를 이미 맡았다
                    const has = taken?.get(m.id);
                    const blocked = !m.available || !!has;
                    return (
                        <label
                            key={m.id}
                            className={styles.person}
                            data-unavailable={blocked || undefined}
                        >
                            <input
                                type="checkbox"
                                checked={picked.includes(m.id)}
                                disabled={blocked && !picked.includes(m.id)}
                                onChange={() => toggle(m.id)}
                            />
                            <span className="grow">
                                <b className="small">{m.name}</b>{' '}
                                <span className="xs muted">{memberSub(m)}</span>
                                {has && (
                                    <span className="xs" style={{ display: 'block' }}>
                                        이미 같은 업무를 맡고 있습니다 ({has})
                                    </span>
                                )}
                                {clash.length > 0 && (
                                    <span className="xs" style={{ display: 'block' }}>
                                        시간 겹침: {clash.map((x) => x.title).join(', ')}
                                    </span>
                                )}
                            </span>
                        </label>
                    );
                })}
                {!people.length && <p className="xs muted">이 부서에 팀원이 없습니다.</p>}
            </fieldset>
            {note && <p className="xs">{note}</p>}
            <div className="row" style={{ justifyContent: 'flex-end' }}>
                <button type="button" className="btn ghost" onClick={onClose}>
                    취소
                </button>
                <button
                    type="button"
                    className="btn primary"
                    onClick={() => setRefused(onConfirm(picked))}
                >
                    {picked.length ? `배정 (${picked.length}명)` : '미배정으로 두기'}
                </button>
            </div>
            {refused && <RefusedAlert reason={refused} onClose={() => setRefused(null)} />}
        </Scrim>
    );
}

/** 배정 거부 안내. 배정 팝업 위에 겹쳐 뜨고, 닫으면 배정 팝업으로 돌아간다 */
function RefusedAlert({ reason, onClose }: { reason: string; onClose: () => void }) {
    return (
        <div
            className={styles.scrim}
            onClick={(e) => {
                e.stopPropagation();
                onClose();
            }}
        >
            <div
                className={styles.alertDialog}
                role="alertdialog"
                aria-modal="true"
                aria-labelledby="assign-refused-title"
                aria-describedby="assign-refused-reason"
                onClick={(e) => e.stopPropagation()}
                onKeyDown={(e) => {
                    // Esc는 이 알림창만 닫고 배정 팝업은 그대로 둔다
                    e.stopPropagation();
                    if (e.key === 'Escape') onClose();
                }}
            >
                <b id="assign-refused-title" className={styles.alertTitle}>
                    <Icon name="warn" /> 배정할 수 없습니다
                </b>
                <p id="assign-refused-reason" className="small">
                    {reason}
                </p>
                <div className="row" style={{ justifyContent: 'flex-end' }}>
                    <button type="button" className="btn primary" autoFocus onClick={onClose}>
                        확인
                    </button>
                </div>
            </div>
        </div>
    );
}

function Scrim({
    label,
    onClose,
    fixed = false,
    children,
}: {
    label: string;
    onClose: () => void;
    /** 크기 고정(담당 배정): 내용이 늘어도 팝업 크기는 그대로 */
    fixed?: boolean;
    children: React.ReactNode;
}) {
    return (
        <div className={styles.scrim} onClick={onClose}>
            <div
                className={`${styles.dialog} ${fixed ? styles.fixedDialog : ''}`}
                role="dialog"
                aria-modal="true"
                aria-label={label}
                onClick={(e) => e.stopPropagation()}
                onKeyDown={(e) => e.key === 'Escape' && onClose()}
            >
                {children}
            </div>
        </div>
    );
}

/** 부서 배정 상자: 큐시트 행을 끌어 놓을 자리. 부서 업무를 나열한다(끝난 업무는 줄 긋기) */
function Box({
    title,
    color,
    sub,
    tasks,
    showWho = false,
    ...drop
}: {
    title: string;
    color: string;
    sub: string;
    tasks: Task[];
    showWho?: boolean;
    'data-over'?: boolean;
    onDragOver: (e: DragEvent) => void;
    onDragLeave: () => void;
    onDrop: (e: DragEvent) => void;
}) {
    const d = useData();
    const shown = tasks.filter((t) => t.status !== 'canceled');
    return (
        <div className={styles.box} style={{ '--group': color } as CSSProperties} {...drop}>
            <div className="row between nw">
                <b className="small">{title}</b>
                <span className="xs muted">
                    {sub} / 업무 {shown.length}
                </span>
            </div>
            {shown.length ? (
                <ul className={styles.boxList}>
                    {shown.map((t) => (
                        <li
                            key={t.id}
                            data-closed={t.status === 'done' || undefined}
                            title={`${t.start}–${t.end}${showWho ? ` / ${t.assignee ? nameOf(d, t.assignee) : '미배정'}` : ''}`}
                        >
                            {t.title}
                            {showWho && !t.assignee && <span className="xs muted"> (미배정)</span>}
                        </li>
                    ))}
                </ul>
            ) : (
                <p className="xs muted">여기로 업무를 끌어 놓으세요</p>
            )}
        </div>
    );
}
