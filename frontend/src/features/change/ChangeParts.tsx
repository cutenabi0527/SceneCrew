import { useEvent, useViewer } from '../../domain/hooks';
import { affectedOf } from '../../domain/selectors';
import { useDispatch } from '../../domain/store-context';
import type { Change } from '../../domain/types';
import { Icon } from '../../shared/ui/Icon';
import { Chip, Note } from '../../shared/ui/atoms';
import styles from './change.module.css';

const STATUS: Record<Change['status'], [string, '' | 'blue' | 'ok']> = {
    DRAFT: ['초안', ''],
    READY: ['검토됨 / 확정 대기', 'blue'],
    APPLIED: ['적용됨', 'ok'],
    DISCARDED: ['폐기', ''],
};

export function ChangeStatusChip({ change, stale }: { change: Change; stale?: boolean }) {
    if (stale) {
        return (
            <Chip kind="warn">
                <Icon name="warn" /> 낡음 / 다시 확인 필요
            </Chip>
        );
    }
    const [label, kind] = STATUS[change.status];
    return (
        <Chip kind={kind}>
            {change.status === 'APPLIED' && change.appliedAt
                ? `${label} / ${change.appliedAt}`
                : label}
        </Chip>
    );
}

/** 확정·편집·폐기를 할 수 있는 상태인가(총괄, 운영 중, 아직 확정·폐기 전) */
function useCanAct(c: Change) {
    const v = useViewer();
    const ev = useEvent();
    return c.status !== 'APPLIED' && c.status !== 'DISCARDED' && v.isChief && ev.state !== 'ENDED';
}

/**
 * 변경안 상태 안내: 확정 완료·폐기·권한 없음·종료된 행사, 또는 낡은 변경안 경고 + 다시 확인.
 * 보고서 제목 바로 아래에 둔다. 안내할 것이 없으면 아무것도 그리지 않는다.
 */
export function ChangeNotice({ change: c, reasons }: { change: Change; reasons: string[] }) {
    const v = useViewer();
    const ev = useEvent();
    const dispatch = useDispatch();
    if (c.status === 'APPLIED') {
        return (
            <Note kind="">
                확정 완료{c.appliedAt && ` / ${c.appliedAt} 적용`} /{' '}
                {affectedOf(c).length
                    ? '영향 받는 사람에게 변경 확인 알림을 보냈습니다.'
                    : '업무/담당 변경이 없어 변경 확인 알림은 없습니다.'}
            </Note>
        );
    }
    if (c.status === 'DISCARDED') return <Note kind="warn">폐기된 변경안입니다.</Note>;
    if (!v.isChief) return <Note>변경 확정은 총괄만 할 수 있습니다.</Note>;
    if (ev.state === 'ENDED')
        return <Note kind="warn">종료된 행사입니다. 변경을 적용할 수 없습니다.</Note>;
    if (!reasons.length) return null;
    return (
        <div className={styles.staleRow}>
            <Note kind="warn" icon="warn">
                <b>낡은 변경안입니다</b> / {reasons.join(' ')} &apos;다시 확인&apos;은 바꿀 값을
                그대로 두고 &apos;현재&apos; 값과 근거를 다시 읽어 비교합니다. 다른 후보로 바꾸지
                않습니다.
            </Note>
            <button
                type="button"
                className="btn"
                onClick={() => dispatch({ type: 'rebaseChange', id: c.id })}
            >
                다시 확인
            </button>
        </div>
    );
}

/**
 * 편집(파랑) / 변경 확정(초록) / 폐기(빨강) 아이콘 버튼 한 줄. 보고서 오른쪽 위에 둔다.
 * 이름은 툴팁·스크린리더로 읽힌다. 할 수 없는 상태면 그리지 않는다(안내는 ChangeNotice).
 */
export function ChangeActions({
    change: c,
    reasons,
    onDone,
    onEdit,
}: {
    change: Change;
    /** 승인 직전 검사에서 나온 이유(비어 있으면 확정 가능) */
    reasons: string[];
    onDone?: () => void;
    /** 있으면 확정 전 변경 후 값을 고치는 "편집" 단추를 보인다 */
    onEdit?: () => void;
}) {
    const stale = reasons.length > 0;
    const dispatch = useDispatch();
    if (!useCanAct(c)) return null;
    return (
        <div className={styles.actionIcons}>
            {onEdit && (
                <button
                    type="button"
                    className={styles.actionIcon}
                    data-kind="edit"
                    aria-label="편집"
                    title="편집"
                    onClick={onEdit}
                >
                    <Icon name="edit" />
                </button>
            )}
            <button
                type="button"
                className={styles.actionIcon}
                data-kind="apply"
                aria-label="변경 확정"
                disabled={stale}
                title={stale ? `확정할 수 없음: ${reasons.join(' ')}` : '변경 확정'}
                onClick={() => dispatch({ type: 'applyChange', id: c.id })}
            >
                <Icon name="check" />
            </button>
            <button
                type="button"
                className={styles.actionIcon}
                data-kind="discard"
                aria-label="폐기"
                title="폐기"
                onClick={() => {
                    dispatch({ type: 'discardChange', id: c.id });
                    onDone?.();
                }}
            >
                <Icon name="trash" />
            </button>
        </div>
    );
}
