import { useState } from 'react';
import { useData } from '../../domain/hooks';
import { Empty, Segmented } from '../../shared/ui/atoms';

const TYPES = ['전체', '행사', '계획', '배정', '변경', '이슈'] as const;
type LogType = (typeof TYPES)[number];

/** 최소 운영 이력 타임라인(날짜별) */
export function ArchiveTimeline() {
    const d = useData();
    const [type, setType] = useState<LogType>('전체');
    const list = d.log.filter((l) => type === '전체' || l.type === type);
    const dates = [...new Set(list.map((l) => l.date))].reverse();
    return (
        <div className="stack">
            <Segmented
                label="기록 종류"
                value={type}
                onChange={setType}
                items={TYPES.map((t) => [t, t])}
            />
            {dates.length ? (
                dates.map((date) => (
                    <div key={date} className="stack s6">
                        <b className="small">{date}</b>
                        <div className="tline">
                            {list
                                .filter((l) => l.date === date)
                                .reverse()
                                .map((l, k) => (
                                    <div key={k}>
                                        <time>{l.at}</time>
                                        <span className="tag" data-type={l.type}>
                                            {l.type}
                                        </span>
                                        <span>{l.text}</span>
                                    </div>
                                ))}
                        </div>
                    </div>
                ))
            ) : (
                <Empty>이 날짜에는 기록이 없습니다</Empty>
            )}
        </div>
    );
}

/** 종료 현황 집계(현재 사실) */
export function ReportSummary() {
    const d = useData();
    const tasks = d.tasks.filter((t) => t.status !== 'canceled');
    const done = tasks.filter((t) => t.status === 'done').length;
    const closed = d.issues.filter((i) => i.status === 'CLOSED').length;
    return (
        <dl className="kv" style={{ gridTemplateColumns: '1fr auto' }}>
            <dt>전체 업무</dt>
            <dd className="mono">{tasks.length} 건</dd>
            <dt>완료 업무</dt>
            <dd className="mono">{done} 건</dd>
            <dt>미완료 업무</dt>
            <dd className="mono">{tasks.length - done} 건</dd>
            <dt>전체 이슈</dt>
            <dd className="mono">{d.issues.length} 건</dd>
            <dt>종료 이슈</dt>
            <dd className="mono">{closed} 건</dd>
            <dt>열린 이슈</dt>
            <dd className="mono">{d.issues.length - closed} 건</dd>
        </dl>
    );
}
