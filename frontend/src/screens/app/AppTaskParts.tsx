import { useEvent, useSession } from '../../domain/hooks';
import {
    TASK_STATUS_LABEL,
    canStartTask,
    checklistReady,
    isLate,
    toMin,
} from '../../domain/selectors';
import { useDispatch } from '../../domain/store-context';
import type { Task } from '../../domain/types';
import { useShell } from '../../layouts/shell-actions';
import { isClosed, progressOf } from './app-task-utils';
import s from './appTask.module.css';

/** 상태 칩: 진행은 주황(지금 표시), 완료는 초록, 대기는 파랑. 끝난 업무는 체크 수를 함께 적는다 */
export function StatusChip({ task: t, withChecks = false }: { task: Task; withChecks?: boolean }) {
    const checks =
        withChecks && t.status === 'done' && t.checklist.length > 0
            ? ` · 체크 ${t.checklist.filter((c) => c.done).length}/${t.checklist.length}`
            : '';
    return (
        <span className={s.chip} data-tone={t.status}>
            {TASK_STATUS_LABEL[t.status]}
            {checks}
        </span>
    );
}

export function LateChip({ task }: { task: Task }) {
    return isLate(task) ? (
        <span className={s.chip} data-tone="late">
            지연
        </span>
    ) : null;
}

export function ProgressBar({ task: t }: { task: Task }) {
    const total = Math.max(0, toMin(t.end) - toMin(t.start));
    const ratio = progressOf(t);
    return (
        <div
            className={s.bar}
            role="progressbar"
            aria-label="업무 진행"
            aria-valuemin={0}
            aria-valuemax={total}
            aria-valuenow={Math.round(total * ratio)}
        >
            <div style={{ width: `${ratio * 100}%` }} />
        </div>
    );
}

/**
 * APP 내 업무 행동 버튼(52px): 대기면 시작, 진행 중이면 완료(필수 체크를 마쳐야 함) + 빠른 보고.
 * WEB과 함께 쓰는 TaskActions와 같은 조건·같은 액션이며, 누를 수 없는 이유는 손가락으로 볼 수 없는 title 대신 아래에 적는다.
 */
export function AppTaskActions({ task: t }: { task: Task }) {
    const ev = useEvent();
    const { me } = useSession();
    const dispatch = useDispatch();
    const shell = useShell();
    const mine = t.assignee === me && !isClosed(t);
    if (!mine || ev.state !== 'LIVE') return null;
    const ready = checklistReady(t);
    const startable = canStartTask(t);
    const hint =
        t.status === 'todo'
            ? startable
                ? null
                : '예정 시작 5분 전부터 시작할 수 있습니다'
            : ready
              ? null
              : '확인 항목을 모두 체크하면 완료할 수 있습니다';
    return (
        <div className={s.actions}>
            <div className={s.btns}>
                {t.status === 'todo' ? (
                    <button
                        type="button"
                        className={s.primary}
                        disabled={!startable}
                        onClick={() => dispatch({ type: 'taskStart', id: t.id })}
                    >
                        시작
                    </button>
                ) : (
                    <button
                        type="button"
                        className={s.primary}
                        disabled={!ready}
                        onClick={() => dispatch({ type: 'taskDone', id: t.id })}
                    >
                        완료
                    </button>
                )}
                <button
                    type="button"
                    className={s.secondary}
                    onClick={() => shell.openReport({ taskId: t.id })}
                >
                    빠른 보고
                </button>
            </div>
            {hint && <span className={s.hint}>{hint}</span>}
        </div>
    );
}
