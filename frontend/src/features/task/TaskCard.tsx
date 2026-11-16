import { useData, useEvent, useSession } from '../../domain/hooks';
import { canStartTask, checklistReady, isLate, nameOf } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { Task } from '../../domain/types';
import { useShell } from '../../layouts/shell-actions';
import { Icon } from '../../shared/ui/Icon';
import { Chip, TaskStatusChip } from '../../shared/ui/atoms';
import styles from './task.module.css';

type Props = {
    task: Task;
    /** 목록에서 요약만 보여주고 누르면 상세로 이동 */
    compact?: boolean;
    showWho?: boolean;
    /** 시간·회차를 한 줄씩 나눠 검은 글씨로 보이고 구역은 뺀다(구조도 팀원 펼침) */
    lines?: boolean;
    /** 시각을 제목 위에 크게 보인다(APP 내 업무) */
    timeFirst?: boolean;
    /** compact 카드에서도 시작/완료·빠른 보고 버튼을 카드 위에 바로 보인다(APP 내 업무) */
    actions?: boolean;
};

/** 업무 카드: WEB 홈 패널·업무 조정 상세, APP 현황·내 업무·업무 상세가 함께 쓴다. */
export function TaskCard({
    task: t,
    compact = false,
    showWho = true,
    lines = false,
    timeFirst = false,
    actions = false,
}: Props) {
    const d = useData();
    const ev = useEvent();
    const { me } = useSession();
    const dispatch = useDispatch();
    const shell = useShell();
    // 실서버 업무 id는 UUID라 화면 코드로 보이지 않는다(#373)
    const live = !!useDemoState().live;
    // 종료(ENDED) 뒤에는 읽기만 한다: 체크·시작/완료·수행 기록을 막는다
    const mine =
        ev.state === 'LIVE' && t.assignee === me && t.status !== 'done' && t.status !== 'canceled';
    const done = t.checklist.filter((c) => c.done).length;
    const program = ev.programs.find((p) => p.id === t.program);

    const head = (
        <div className="row nw">
            <div className="grow">
                {timeFirst && (
                    <div className={styles.time}>
                        {t.start}–{t.end}
                    </div>
                )}
                <div className={styles.title}>
                    {t.title} {!live && <span className="code">{t.id}</span>}
                </div>
                {lines ? (
                    <dl className={styles.lines}>
                        <div>
                            <dt>시간</dt>
                            <dd className="mono">
                                {t.start}–{t.end}
                            </dd>
                        </div>
                        {program && (
                            <div>
                                <dt>회차</dt>
                                <dd>{program.name}</dd>
                            </div>
                        )}
                        {showWho && (
                            <div>
                                <dt>담당</dt>
                                <dd>{nameOf(d, t.assignee)}</dd>
                            </div>
                        )}
                    </dl>
                ) : (
                    <div className="xs muted mono">
                        {!timeFirst && `${t.start}–${t.end} / `}
                        {t.zone}
                        {showWho && ` / ${nameOf(d, t.assignee)}`}
                        {program && ` / ${program.name}`}
                    </div>
                )}
            </div>
            {isLate(t) && <Chip kind="bad">지연</Chip>}
            <TaskStatusChip status={t.status} />
        </div>
    );

    if (compact) {
        const summary = (
            <>
                {head}
                {t.changed && !t.ack && <div className={styles.changed}>변경됨 / {t.changed}</div>}
                {t.checklist.length > 0 && (
                    <div className={lines ? `xs ${styles.ink}` : 'xs muted'}>
                        체크 {done}/{t.checklist.length}
                    </div>
                )}
            </>
        );
        if (actions) {
            // 버튼 안에 버튼을 둘 수 없어 요약 부분만 누르는 칸으로 두고 행동 버튼은 그 아래에 둔다
            return (
                <article className={`${styles.card} ${styles[t.status]}`}>
                    {shell.canOpenTask ? (
                        <button
                            type="button"
                            className={styles.open}
                            onClick={() => shell.openTask(t.id)}
                        >
                            {summary}
                        </button>
                    ) : (
                        <div className={styles.open}>{summary}</div>
                    )}
                    <TaskActions task={t} />
                </article>
            );
        }
        // 업무 상세를 열 수 없으면(WEB 팀원) 누를 수 없는 카드로 둔다
        if (!shell.canOpenTask)
            return <div className={`${styles.card} ${styles[t.status]}`}>{summary}</div>;
        return (
            <button
                type="button"
                className={`${styles.card} ${styles[t.status]}`}
                onClick={() => shell.openTask(t.id)}
            >
                {summary}
            </button>
        );
    }

    return (
        <article className={`${styles.card} ${styles[t.status]}`}>
            {head}
            {t.changed && !t.ack && (
                <div className={styles.changed}>
                    변경됨 / {t.changed}
                    {t.assignee !== me && <Chip kind="warn">미확인</Chip>}
                </div>
            )}
            {t.steps.length > 0 && (
                <ol className={styles.steps}>
                    {t.steps.map((s) => (
                        <li key={s}>{s}</li>
                    ))}
                </ol>
            )}
            {t.checklist.length > 0 && (
                <div className="stack s6">
                    {t.checklist.map((c, i) => (
                        <label key={c.text} className={`check ${c.done ? 'done' : ''}`}>
                            <input
                                type="checkbox"
                                checked={c.done}
                                disabled={!mine}
                                onChange={() =>
                                    dispatch({ type: 'toggleCheck', id: t.id, index: i })
                                }
                            />
                            {c.text}
                        </label>
                    ))}
                </div>
            )}
            <TaskActions task={t} />
        </article>
    );
}

/** 내 업무 행동 버튼: 대기면 시작, 진행 중이면 완료(필수 체크를 마쳐야 함) + 빠른 보고. 운영 중인 내 업무에만 보인다. */
export function TaskActions({ task: t }: { task: Task }) {
    const ev = useEvent();
    const { me } = useSession();
    const dispatch = useDispatch();
    const shell = useShell();
    const mine = t.assignee === me && t.status !== 'done' && t.status !== 'canceled';
    const ready = checklistReady(t);
    if (!mine || ev.state !== 'LIVE') return null;
    return (
        <div className="row">
            {t.status === 'todo' ? (
                <button
                    type="button"
                    className="btn primary sm"
                    disabled={!canStartTask(t)}
                    title={canStartTask(t) ? undefined : '예정 시작 5분 전부터 시작할 수 있습니다'}
                    onClick={() => dispatch({ type: 'taskStart', id: t.id })}
                >
                    시작
                </button>
            ) : (
                <button
                    type="button"
                    className="btn primary sm"
                    disabled={!ready}
                    title={ready ? undefined : '필수 체크를 완료하세요'}
                    onClick={() => dispatch({ type: 'taskDone', id: t.id })}
                >
                    완료
                </button>
            )}
            <button
                type="button"
                className="btn sm"
                onClick={() => shell.openReport({ taskId: t.id })}
            >
                <Icon name="bolt" /> 빠른 보고
            </button>
        </div>
    );
}
