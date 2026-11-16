import { useData } from '../../domain/hooks';
import { ISSUE_STATUS_LABEL, ago, nameOf } from '../../domain/selectors';
import type { Change, Issue, IssueReport } from '../../domain/types';
import styles from './issue.module.css';

/**
 * 처리 상태(WEB 보고·대응 통합 목록): 카드 왼쪽 색 바로 보인다.
 * 주황 신규 접수 / 보라 확정 대기 변경 / 초록 해결 확인 / 회색 종료. 주지 않으면 회색 바다.
 */
type Tone = 'new' | 'approve' | 'resolve' | 'closed';
type Common = { selected?: boolean; onSelect: () => void; tone?: Tone };
type Props =
    ({ issue: Issue; change?: never } & Common) | ({ change: Change; issue?: never } & Common);

/** 보고 성격 표시(카드 제목 오른쪽): 하늘 장비 / 노랑 안전 / 파랑 대기 / 회색 일반(긴급도는 폐지) */
type Tag = 'equip' | 'safety' | 'approve' | 'normal';
const TAG_LABEL: Record<Tag, string> = {
    equip: '장비',
    safety: '안전',
    approve: '대기',
    normal: '일반',
};

/** 이슈가 해당하는 표시를 모두 고른다(대표·연관 상황 모두 본다). 하나도 없으면 일반 */
function issueTags(i: Issue): Tag[] {
    const kinds = i.situation ? [i.situation.primary, ...i.situation.related] : [];
    const tags: Tag[] = [];
    if (kinds.includes('EQUIPMENT_ANOMALY')) tags.push('equip');
    if (kinds.includes('AUDIENCE_SAFETY')) tags.push('safety');
    return tags.length ? tags : ['normal'];
}

/** 제목 줄: 제목(길면 …) + 오른쪽 표시들 */
function Head({ title, tags }: { title: string; tags: Tag[] }) {
    return (
        <span className={styles.rowHead}>
            <b>{title}</b>
            <span className={styles.tags}>
                {tags.map((t) => (
                    <i key={t} data-tag={t}>
                        {TAG_LABEL[t]}
                    </i>
                ))}
            </span>
        </span>
    );
}

/** 대응 큐의 한 항목(이슈 또는 확정 대기 변경). WEB 왼쪽 목록, APP 대응 탭 목록이 같다. */
export function IssueRow({ issue, change, selected, onSelect, tone }: Props) {
    const d = useData();
    if (change) {
        return (
            <button
                type="button"
                className={styles.row}
                aria-current={selected}
                data-tone={tone}
                onClick={onSelect}
            >
                <Head title={change.title} tags={['approve']} />
                <span className="xs muted">
                    확정 대기 / {change.mode === 'AI' ? 'AI 대응안' : '직접 변경'} /{' '}
                    {nameOf(d, change.by)} / {ago(change.at)}
                </span>
            </button>
        );
    }
    const i = issue!;
    return (
        <button
            type="button"
            className={styles.row}
            aria-current={selected}
            data-tone={tone}
            onClick={onSelect}
        >
            <Head title={i.title} tags={issueTags(i)} />
            <span className="xs muted">
                {i.zone} / {ISSUE_STATUS_LABEL[i.status]} / {ago(i.at)}
            </span>
        </button>
    );
}

/**
 * 보고에 붙은 추가 보고 한 건(WEB 보고·대응 목록에서 원 보고 아래 시간 순으로 잇는다).
 * 누르면 원 보고를 고른다. 왼쪽 색 바는 원 보고의 처리 상태를 따른다.
 */
export function FollowupRow({
    report: r,
    tone,
    onSelect,
}: {
    report: IssueReport;
    tone?: Tone;
    onSelect: () => void;
}) {
    const d = useData();
    return (
        <button
            type="button"
            className={`${styles.row} ${styles.followRow}`}
            data-tone={tone}
            onClick={onSelect}
        >
            <span className={styles.rowHead}>
                <b>{r.text}</b>
                <span className={styles.tags}>
                    <i data-tag="followup">추가 보고</i>
                </span>
            </span>
            <span className="xs muted">
                {nameOf(d, r.by)} / {r.via} / {r.at}
            </span>
        </button>
    );
}
