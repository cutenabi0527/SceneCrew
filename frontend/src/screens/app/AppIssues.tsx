import { useNavigate } from 'react-router';
import { useData, useEvent, useResponseQueues, useViewer } from '../../domain/hooks';
import type { Issue, ReportVia } from '../../domain/types';
import { IssueRow } from '../../features/issue/IssueRow';
import { useShell } from '../../layouts/shell-actions';
import { Empty } from '../../shared/ui/atoms';
import { Icon } from '../../shared/ui/Icon';
import styles from './app.module.css';
import ui from './appIssue.module.css';
import { IssueChip, IssueFlow, IssueNow, IssueSteps } from './issueParts';

/**
 * 보고 이슈 목록: 진행 중(새 보고·해결 보고 대기)을 위에, 종료된 보고를 아래에 흐리게 둔다.
 * 총괄은 행사 전체 이슈, 팀원은 본인이 보고했거나 본인 업무와 연결된 이슈만 본다(responseQueues).
 * 누르면 보고 상세(M07)로 간다. 내 업무의 진행 보고 탭이 쓴다(이슈 탭은 아래 AppIssues 카드 목록).
 */
export function ReportIssueList() {
    const q = useResponseQueues();
    const navigate = useNavigate();
    const open = [...q.news, ...q.resolve];
    const go = (id: string) => navigate(`/e/response/${id}`);
    return (
        <>
            {open.length > 0 ? (
                <div className="stack s6">
                    {open.map((i) => (
                        <IssueRow key={i.id} issue={i} onSelect={() => go(i.id)} />
                    ))}
                </div>
            ) : (
                <Empty icon="warn">진행 중인 보고가 없습니다.</Empty>
            )}
            {q.closed.length > 0 && (
                <div className="stack s6">
                    <div className="sec-t">
                        종료된 보고 <span className="badge gray">{q.closed.length}</span>
                    </div>
                    {q.closed.map((i) => (
                        <div key={i.id} className={styles.struck}>
                            <IssueRow issue={i} onSelect={() => go(i.id)} />
                        </div>
                    ))}
                </div>
            )}
        </>
    );
}

const VIA_LABEL: Record<ReportVia, string> = {
    음성: '음성 보고',
    텍스트: '글 보고',
    '빠른 보고': '빠른 보고',
};

/** 보고 카드: 상태 칩·보낸 시각, 제목, 구역·관련 업무, 4단계 막대, 지금 상황 한 줄 */
function IssueCard({ issue: i, onOpen }: { issue: Issue; onOpen: () => void }) {
    const d = useData();
    const v = useViewer();
    // 보낸 방법은 내가 볼 수 있는 처음 보고에서, 관련 업무는 내가 볼 수 있는 업무만 쓴다
    const first = i.reports.filter(v.canViewReport)[0];
    const task = i.tasks
        .map((id) => d.tasks.find((t) => t.id === id))
        .filter((t) => !!t)
        .find(v.canViewTask);
    const closed = i.status === 'CLOSED';
    return (
        <button
            type="button"
            className={`${ui.card} ${closed ? ui.closed : ''}`}
            data-status={i.status}
            onClick={onOpen}
        >
            <span className={ui.cardTop}>
                <IssueChip status={i.status} />
                <span className={ui.meta}>
                    {first ? `${i.at} · ${VIA_LABEL[first.via]}` : `${i.at} 접수`}
                </span>
            </span>
            <span className={ui.head}>
                <b className={ui.cardTitle}>{i.title}</b>
                <span className={ui.sub}>
                    {i.zone}
                    {task && ` · 관련 업무: ${task.title} ${task.start}`}
                </span>
            </span>
            {!closed && (
                <>
                    <IssueSteps status={i.status} />
                    <IssueNow status={i.status} className={ui.now} />
                </>
            )}
        </button>
    );
}

/**
 * 이슈 탭(APP): 현장 팀원이 보낸 보고가 어디까지 처리됐는지 본다.
 * 목록 범위는 그대로 responseQueues(팀원은 본인 보고·본인 업무와 연결된 보고, 총괄은 전체)다.
 * 보낸 보고가 없으면 보고하기 버튼과 처리 단계를 안내한다.
 */
export function AppIssues() {
    const q = useResponseQueues();
    const ev = useEvent();
    const v = useViewer();
    const shell = useShell();
    const navigate = useNavigate();
    const open = [...q.news, ...q.resolve];
    const go = (id: string) => navigate(`/e/response/${id}`);
    // 총괄은 행사 전체 보고를 보므로 '내가 보낸' 대신 보고 이슈라고 부른다
    const title = v.isChief ? '보고 이슈' : '내가 보낸 보고';

    if (!open.length && !q.closed.length) {
        return (
            <div className={ui.root}>
                <h2 className={ui.title}>{title}</h2>
                <section aria-label="보낸 보고 없음" className={ui.empty}>
                    <span className={ui.emptyIcon}>
                        <Icon name="check" />
                    </span>
                    <span className={ui.emptyTitle}>진행 중인 보고가 없습니다</span>
                    <span className={ui.emptyText}>
                        문제가 생기면 보고를 보내세요. 보낸 보고가 어디까지 처리됐는지 여기서 볼 수
                        있습니다.
                    </span>
                    <button
                        type="button"
                        className={ui.primary}
                        style={{ marginTop: 6 }}
                        onClick={() => shell.openReport()}
                    >
                        보고하기
                    </button>
                </section>
                <section aria-labelledby="issue-flow-h" className={ui.section}>
                    <h3 id="issue-flow-h" className={ui.sectionTitle}>
                        보고를 보내면
                    </h3>
                    <IssueFlow />
                    <span className={ui.guide}>
                        단계가 바뀌면 알림이 옵니다. 나에게 할 일이 생기면 내 업무에 나타납니다.
                    </span>
                </section>
            </div>
        );
    }

    return (
        <div className={ui.root}>
            <h2 className={ui.title}>{title}</h2>
            <span className={ui.count}>처리 중 {open.length}</span>
            {open.length > 0 ? (
                <div className={ui.list}>
                    {open.map((i) => (
                        <div key={i.id} className={ui.list}>
                            <IssueCard issue={i} onOpen={() => go(i.id)} />
                            {/* 같은 보고에 이어 쓰기(FOLLOWUP). 보고할 수 있는 운영 중 행사에서만 */}
                            {ev.state === 'LIVE' && v.canReportIssue(i) && (
                                <button
                                    type="button"
                                    className={ui.more}
                                    aria-label={`${i.title}에 추가로 알리기`}
                                    onClick={() =>
                                        shell.openReport({ issueId: i.id, kind: 'FOLLOWUP' })
                                    }
                                >
                                    <b>추가로 알리기</b>
                                    <span>같은 보고에 이어 쓰기</span>
                                </button>
                            )}
                        </div>
                    ))}
                </div>
            ) : (
                <span className={ui.guide}>처리 중인 보고가 없습니다.</span>
            )}
            <span className={ui.count} style={{ marginTop: 4 }}>
                끝난 보고 {q.closed.length}
            </span>
            {q.closed.length > 0 && (
                <div className={ui.list}>
                    {q.closed.map((i) => (
                        <IssueCard key={i.id} issue={i} onOpen={() => go(i.id)} />
                    ))}
                </div>
            )}
        </div>
    );
}
