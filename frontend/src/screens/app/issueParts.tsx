import { ISSUE_STATUS_LABEL } from '../../domain/selectors';
import type { IssueStatus } from '../../domain/types';
import styles from './appIssue.module.css';

const STEPS: IssueStatus[] = ['RECEIVED', 'RESPONDING', 'RESOLUTION_REPORTED', 'CLOSED'];

/** 처리 상태마다 현장 직원이 기다릴 일을 한 줄로 알린다(상태 라벨의 풀이, 데이터가 아님) */
const NOW_TEXT: Record<IssueStatus, string> = {
    RECEIVED: '총괄이 확인하고 있습니다. 대응이 정해지면 알림이 옵니다.',
    RESPONDING: '대응하고 있습니다. 나에게 할 일이 생기면 내 업무에 나타납니다.',
    RESOLUTION_REPORTED: '해결 보고가 들어왔습니다. 총괄이 확인하면 종료됩니다.',
    CLOSED: '처리가 끝난 보고입니다.',
};

/** 이슈 상태 칩(앱 라벨 그대로) */
export function IssueChip({ status }: { status: IssueStatus }) {
    return (
        <span className={styles.chip} data-status={status}>
            {ISSUE_STATUS_LABEL[status]}
        </span>
    );
}

/** 접수됨 → 대응 중 → 해결 보고 → 종료 4단계 막대. 지금 단계까지 칠한다 */
export function IssueSteps({ status }: { status: IssueStatus }) {
    const at = STEPS.indexOf(status);
    return (
        <ol
            className={styles.steps}
            data-status={status}
            aria-label={`처리 단계: 4단계 중 ${at + 1}단계`}
        >
            {STEPS.map((s, k) => (
                <li
                    key={s}
                    data-on={k <= at ? '' : undefined}
                    aria-current={k === at ? 'step' : undefined}
                >
                    {ISSUE_STATUS_LABEL[s]}
                </li>
            ))}
        </ol>
    );
}

/** 지금 상황 한 줄 */
export function IssueNow({
    status,
    className,
    closedAt,
}: {
    status: IssueStatus;
    className: string;
    closedAt?: string;
}) {
    return (
        <span className={className}>
            {NOW_TEXT[status]}
            {status === 'CLOSED' && closedAt ? ` (${closedAt} 종료)` : ''}
        </span>
    );
}

/** 보고를 보내면 거치는 단계 안내(빈 상태) */
export function IssueFlow() {
    return (
        <ol className={`${styles.steps} ${styles.flow}`}>
            {STEPS.map((s) => (
                <li key={s}>{ISSUE_STATUS_LABEL[s]}</li>
            ))}
        </ol>
    );
}
