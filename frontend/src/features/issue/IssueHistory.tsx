import { useData, useViewer } from '../../domain/hooks';
import { nameOf } from '../../domain/selectors';
import type { Issue } from '../../domain/types';
import report from '../../shared/ui/report.module.css';
import styles from './issue.module.css';

const KIND = { INITIAL: '최초 보고', FOLLOWUP: '추가 보고', RESOLUTION: '해결 보고' } as const;

type Entry =
    | { at: string; kind: 'report'; label: string; by: string; via: string; text: string }
    | { at: string; kind: 'log'; label: string; text: string };

/**
 * 보고 원문(불변)과 처리 이력을 시간순 한 타임라인으로 보여 준다.
 * 보고 접수와 같은 시각에 남은 '접수/보고' 기록은 보고 원문과 겹치므로 뺀다.
 */
export function IssueHistory({ issue: i }: { issue: Issue }) {
    const d = useData();
    const v = useViewer();
    const reports = i.reports.filter(v.canViewReport);
    const reportTimes = new Set(reports.map((r) => r.at));
    const entries: Entry[] = [
        ...reports.map((r): Entry => ({
            at: r.at,
            kind: 'report',
            label: KIND[r.kind],
            by: nameOf(d, r.by),
            via: r.via,
            text: r.text,
        })),
        ...(v.isChief ? d.log : [])
            .filter((l) => l.ref === i.id)
            .filter((l) => !(reportTimes.has(l.at) && /접수|보고/.test(l.text)))
            .map((l): Entry => ({
                at: l.at,
                kind: 'log',
                label: `처리 (${l.type})`,
                text: l.text,
            })),
    ];
    // 같은 시각이면 보고가 처리보다 먼저 온다(sort는 안정 정렬).
    entries.sort((a, b) => a.at.localeCompare(b.at));

    return (
        <section className={report.section}>
            <h4>4. 보고 및 처리 이력</h4>
            <ol className={styles.timeline}>
                {entries.map((e, k) => (
                    <li key={k}>
                        <time className="xs mono">{e.at}</time>
                        <div>
                            <b>{e.label}</b>
                            <ul className={report.sub}>
                                {e.kind === 'report' && (
                                    <>
                                        <li>보고자: {e.by}</li>
                                        <li>방법: {e.via}</li>
                                    </>
                                )}
                                <li>내용: {e.text}</li>
                            </ul>
                        </div>
                    </li>
                ))}
            </ol>
        </section>
    );
}
