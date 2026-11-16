import { useNotices } from '../../domain/hooks';
import { needsAck } from '../../domain/selectors';
import type { Notice, NoticeKind } from '../../domain/types';
import { Chip, Empty, type ChipKind } from '../../shared/ui/atoms';
import styles from './notice.module.css';

const KIND: Record<NoticeKind, [string, ChipKind]> = {
    report: ['보고', 'bad'],
    assign: ['업무 배정', 'ok'],
    task: ['업무 안내', 'blue'],
    change: ['변경', 'accent'],
    deploy: ['예비 투입', 'accent'],
    profile: ['정보 변경', 'blue'],
};

/** 알림 목록. 누르면 어디로 갈지는 화면(WEB 드롭다운/APP 알림 탭)이 정한다. */
export function NoticeList({
    onOpen,
    limit,
    kinds,
}: {
    onOpen: (n: Notice) => void;
    limit?: number;
    /** 보일 알림 종류(없으면 전체) */
    kinds?: NoticeKind[];
}) {
    const list = useNotices()
        .filter((n) => !kinds || kinds.includes(n.kind))
        .slice(0, limit);
    if (!list.length) return <Empty icon="bell">알림이 없습니다</Empty>;
    return (
        <div className={styles.list}>
            {list.map((n) => (
                <button
                    key={n.id}
                    type="button"
                    className={`${styles.item} ${n.read ? '' : styles.unread}`}
                    onClick={() => onOpen(n)}
                >
                    <Chip kind={KIND[n.kind][1]}>{KIND[n.kind][0]}</Chip>
                    <span className="grow">
                        <span className="small" style={{ display: 'block' }}>
                            {n.text}
                        </span>
                        <span className="xs muted mono">{n.at}</span>
                        {needsAck(n) && (
                            <>
                                {' '}
                                <Chip kind="warn">변경 확인 필요</Chip>
                            </>
                        )}
                    </span>
                </button>
            ))}
        </div>
    );
}
