import { useNavigate, useParams } from 'react-router';
import { useData, useEvent, useIssue, useViewer } from '../../domain/hooks';
import {
    TASK_STATUS_LABEL,
    ago,
    groupName,
    nameOf,
    receivesIssueForward,
} from '../../domain/selectors';
import { useShell } from '../../layouts/shell-actions';
import { Icon } from '../../shared/ui/Icon';
import ui from './appIssue.module.css';
import { IssueChip, IssueNow, IssueSteps } from './issueParts';

const KIND = { INITIAL: '처음 보고', FOLLOWUP: '추가 보고', RESOLUTION: '해결 보고' } as const;

/** 권한 없음·없는 보고: 이유와 다음에 갈 곳 */
function NoAccess({ title, text }: { title: string; text: string }) {
    const navigate = useNavigate();
    return (
        <div className={ui.noAccess}>
            <span className={ui.emptyIcon}>
                <Icon name="lock" />
            </span>
            <span className={ui.emptyTitle}>{title}</span>
            <span className={ui.emptyText}>{text}</span>
            <div className={ui.noAccessActions}>
                <button
                    type="button"
                    className={ui.primary}
                    onClick={() => navigate('/e/issues', { replace: true })}
                >
                    이슈 목록으로
                </button>
                <button
                    type="button"
                    className={ui.secondary}
                    onClick={() => navigate('/e/today', { replace: true })}
                >
                    내 업무로
                </button>
            </div>
        </div>
    );
}

/**
 * 보고 상세(APP, M07): 현장 직원이 보낸 보고의 처리 상태를 본다.
 * 상태 카드(칩·4단계·지금 상황) → 내가 보낸 보고 → 관련 업무, 아래 고정 추가 보고·해결 보고.
 * 모델 분석 값과 대응안 관리는 총괄 WEB에서 보므로 여기서는 숨긴다.
 */
export function AppIssue() {
    const { issueId } = useParams();
    const i = useIssue(issueId);
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const shell = useShell();

    if (!i)
        return (
            <NoAccess
                title="보고를 찾을 수 없습니다"
                text="지워졌거나 다른 공연의 보고입니다. 이슈 목록에서 다시 골라 주세요."
            />
        );
    // 주소로 직접 들어와도 권한 밖 이슈는 열지 않는다(WEB/APP 공통 규칙)
    if (!v.canViewIssue(i))
        return (
            <NoAccess
                title="다른 사람의 보고입니다"
                text="보고는 보낸 사람과 관련 업무 담당자만 볼 수 있습니다. 내 보고는 이슈 목록에서 확인하세요."
            />
        );

    const mine = i.reporter === v.me?.id;
    const reports = i.reports.filter(v.canViewReport);
    const related = i.tasks
        .map((id) => d.tasks.find((t) => t.id === id))
        .filter((t) => !!t)
        .filter(v.canViewTask);
    // 전달받은 사람은 원문 대신 요약과 전달 기록을 본다
    const forwards = i.forwards?.filter((f) => v.isChief || receivesIssueForward(f, v.me));
    const live = ev.state === 'LIVE';
    const canReport = live && i.status !== 'CLOSED' && v.canReportIssue(i);
    const since = ago(i.at);

    return (
        <div className={`${ui.root} ${ui.detail}`}>
            <section className={ui.card} data-status={i.status} aria-label="처리 상태">
                <div className={ui.cardTop}>
                    <IssueChip status={i.status} />
                    <span className={ui.meta}>
                        {i.at} · {since === '방금' ? since : `${since} 전`}
                    </span>
                </div>
                <div className={ui.head}>
                    <h2 className={ui.detailTitle}>{i.title}</h2>
                    <span className={ui.sub}>{i.zone}</span>
                </div>
                <IssueSteps status={i.status} />
                <IssueNow status={i.status} className={ui.nowBox} closedAt={i.closedAt} />
            </section>

            {reports.length > 0 ? (
                <section aria-labelledby="issue-hist-h" className={ui.section}>
                    <h3 id="issue-hist-h" className={ui.sectionTitle}>
                        {mine && !v.isChief ? '내가 보낸 보고' : '보고 기록'}
                    </h3>
                    <ol className={ui.history}>
                        {reports.map((r, k) => (
                            <li key={`${r.at}-${k}`}>
                                <time>{r.at}</time>
                                <div className={ui.historyBody}>
                                    <b>
                                        {KIND[r.kind]} · {r.via}
                                        {r.by !== v.me?.id && ` · ${nameOf(d, r.by)}`}
                                    </b>
                                    <p>{r.text}</p>
                                </div>
                            </li>
                        ))}
                    </ol>
                </section>
            ) : (
                <section aria-labelledby="issue-sum-h" className={ui.section}>
                    <h3 id="issue-sum-h" className={ui.sectionTitle}>
                        보고 요약
                    </h3>
                    <p className={ui.summary}>{i.summary}</p>
                </section>
            )}

            {forwards && forwards.length > 0 && (
                <section aria-labelledby="issue-fwd-h" className={ui.section}>
                    <h3 id="issue-fwd-h" className={ui.sectionTitle}>
                        전달 기록
                    </h3>
                    <ol className={ui.history}>
                        {forwards.map((f, k) => (
                            <li key={`${f.at}-${k}`}>
                                <time>{f.at}</time>
                                <div className={ui.historyBody}>
                                    <b>
                                        {f.memberId
                                            ? nameOf(d, f.memberId)
                                            : f.groupId
                                              ? `${groupName(d, f.groupId)} 전체`
                                              : '태그'}
                                        {f.mentions.length > 0 &&
                                            ` @${f.mentions.map((id) => nameOf(d, id)).join(' @')}`}
                                    </b>
                                    {f.note && <p>{f.note}</p>}
                                </div>
                            </li>
                        ))}
                    </ol>
                </section>
            )}

            {related.length > 0 && (
                <section aria-labelledby="issue-rel-h" className={ui.section}>
                    <h3 id="issue-rel-h" className={ui.sectionTitle}>
                        관련 업무
                    </h3>
                    {related.map((t) => (
                        <button
                            key={t.id}
                            type="button"
                            className={ui.task}
                            onClick={() => shell.openTask(t.id)}
                        >
                            <span>
                                <b>{t.title}</b>
                                <span className={ui.meta}>
                                    {t.start}–{t.end} · {nameOf(d, t.assignee)}
                                </span>
                            </span>
                            <span className={ui.chip}>{TASK_STATUS_LABEL[t.status]}</span>
                        </button>
                    ))}
                </section>
            )}

            <div className={ui.footer}>
                {canReport ? (
                    <div className={ui.footerPair}>
                        <button
                            type="button"
                            className={ui.primary}
                            onClick={() => shell.openReport({ issueId: i.id, kind: 'FOLLOWUP' })}
                        >
                            추가 보고
                        </button>
                        <button
                            type="button"
                            className={ui.secondary}
                            onClick={() => shell.openReport({ issueId: i.id, kind: 'RESOLUTION' })}
                        >
                            해결됐어요
                        </button>
                    </div>
                ) : (
                    <span className={ui.footerNote}>
                        {!live
                            ? '종료된 행사라 읽기만 할 수 있습니다.'
                            : i.status === 'CLOSED'
                              ? `종료된 보고입니다${i.closedAt ? ` (${i.closedAt})` : ''}.`
                              : '이 이슈는 열람만 할 수 있습니다.'}
                    </span>
                )}
                {v.canManage && live && (
                    <span className={ui.footerNote}>
                        대응안 만들기, 변경 확정, 이슈 종료는 WEB에서 할 수 있습니다.
                    </span>
                )}
            </div>
        </div>
    );
}
