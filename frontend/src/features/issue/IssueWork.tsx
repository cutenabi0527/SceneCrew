import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useData, useEvent, useViewer } from '../../domain/hooks';
import { ISSUE_STATUS_LABEL, affectedOf, ago, nameOf } from '../../domain/selectors';
import { SituationEditor, SituationLine } from './SituationParts';
import { newId, useDemoState, useDispatch } from '../../domain/store-context';
import type { Issue } from '../../domain/types';

import { useShell } from '../../layouts/shell-actions';
import { Empty, TaskStatusChip } from '../../shared/ui/atoms';
import { Icon } from '../../shared/ui/Icon';
import changeStyles from '../change/change.module.css';
import { IssueForwardLog, IssueForwardPanel } from './IssueForward';
import { IssueHistory } from './IssueHistory';
import report from '../../shared/ui/report.module.css';
import styles from './issue.module.css';

/**
 * 이슈 작업 영역: 상황 요약 → 관련 업무 → 현재 대응 → 다음 행동 → 주 행동.
 * WEB은 보고·대응(W04) 오른쪽 칸, APP은 보고 상세(M07) 페이지 본문으로 같은 컴포넌트를 쓴다.
 * 대응안 만들기·확정·종료 같은 총괄 관리 행동은 WEB에서만 한다(APP은 보고만).
 */
/**
 * 실서버 총괄 화면의 보고 분석 상태(#358). 분석 중·실패·결과 없음·낡음을 구분해 보이고, 필요하면 다시 분석을 요청한다.
 * 데모 이슈에는 상태가 없어 아무것도 그리지 않는다.
 */
function AnalysisNotice({ issue: i, canRequest }: { issue: Issue; canRequest: boolean }) {
    const dispatch = useDispatch();
    const s = i.analysisStatus;
    const stale = i.analysis?.validity === 'STALE' || i.analysis?.validity === 'PARTIAL_STALE';
    const text =
        s?.kind === 'running'
            ? '가장 최근 보고를 분석하고 있습니다. 잠시 뒤 결과가 보입니다.'
            : s?.kind === 'failed' && s.code === 'STALE_CONTEXT'
              ? '분석하는 동안 공연 정보가 바뀌어 결과를 반영하지 않았습니다. 지금 기준으로 다시 분석할 수 있습니다.'
              : s?.kind === 'failed'
                ? `가장 최근 보고의 분석에 실패했습니다${s.code ? `(${s.code})` : ''}. 다시 분석을 요청할 수 있습니다.`
                : s?.kind === 'missing'
                  ? '가장 최근 보고의 분석 결과가 없습니다. 분석이 실패했거나 그사이 공연 정보가 바뀌어 반영하지 않았습니다.'
                  : stale
                    ? '분석한 뒤 공연 정보가 바뀌어 낡은 결과입니다. 다시 분석하면 지금 기준으로 봅니다.'
                    : i.analysis?.validity === 'CURRENT'
                      ? '지금 공연 정보 기준의 최신 분석입니다.'
                      : '';
    if (!text) return null;
    const retry =
        canRequest &&
        i.latestReportId &&
        (s?.kind === 'failed' || s?.kind === 'missing' || (!s && stale));
    return (
        <li>
            <span role="status">{text}</span>
            {retry && (
                <>
                    {' '}
                    <button
                        className="btn sm"
                        type="button"
                        onClick={() => dispatch({ type: 'reanalyze', issueId: i.id })}
                    >
                        다시 분석
                    </button>
                </>
            )}
        </li>
    );
}

export function IssueWork({ issue: i }: { issue: Issue }) {
    const d = useData();
    const ev = useEvent();
    const v = useViewer();
    const dispatch = useDispatch();
    const shell = useShell();
    const navigate = useNavigate();
    const [busy, setBusy] = useState(false);
    const live = !!useDemoState().live;
    const manage = v.canManage && shell.variant === 'web';
    // 총괄 WEB에서 지금 처리할 수 있는 이슈면 보고서 머리에 작업 아이콘을 둔다
    const acting = manage && ev.state === 'LIVE' && i.status !== 'CLOSED';
    const related = i.tasks
        .map((id) => d.tasks.find((t) => t.id === id))
        .filter((t) => !!t)
        .filter(v.canViewTask);
    const changes = d.changes.filter((c) => c.issueId === i.id && c.status !== 'DISCARDED');
    const pending = changes.find((c) => c.status === 'DRAFT' || c.status === 'READY');
    const applied = changes.filter((c) => c.status === 'APPLIED');
    // 이 보고의 가장 최신 대응안(폐기 제외): 작성 시각이 가장 늦은 것, 같으면 나중에 만든 것
    const response = changes.reduce<(typeof changes)[number] | undefined>(
        (best, c) => (!best || c.at.localeCompare(best.at) >= 0 ? c : best),
        undefined,
    );
    /** WEB 보고·대응에서는 왼쪽 목록의 대응안 카드(보고 아래 들여쓰기)를 고른 채로 연다 */
    const openResponse = (id: string) =>
        shell.variant === 'web' && d.changes.find((c) => c.id === id)?.status === 'READY'
            ? navigate(`/e/response/${id}`)
            : shell.openChange(id);

    const makeAiChange = () => {
        // 실서버: 064로 요청하고, 만들어지면 확정 대기 변경 목록에 보인다(#371).
        if (live) {
            dispatch({ type: 'aiChange', issueId: i.id, id: newId('C') });
            return;
        }
        setBusy(true);
        // 데모: AI 서버 응답 대기를 흉내 낸다. 그동안 다른 화면은 계속 쓸 수 있다.
        window.setTimeout(() => {
            const id = newId('C');
            dispatch({ type: 'aiChange', issueId: i.id, id });
            setBusy(false);
            // AI 대응안은 확정 대기(READY)로 만들어지므로 WEB에서는 보고 아래 대응안 카드를 고른다
            if (shell.variant === 'web') navigate(`/e/response/${id}`);
            else shell.openChange(id);
        }, 700);
    };

    // 주소로 직접 들어와도 권한 밖 이슈는 열지 않는다(WEB/APP 공통).
    if (!v.canViewIssue(i)) return <Empty icon="lock">이 이슈를 볼 권한이 없습니다</Empty>;

    return (
        <div className={report.wrap}>
            <div className={`${report.layout} ${styles.workLayout}`}>
                <div className={`stack ${report.body} ${report.report}`}>
                    {/* 제목은 가운데, 총괄 WEB 작업(대응안·종료·추가 보고·상황 정정)은 오른쪽 위 아이콘 */}
                    <header className={`${report.head} ${changeStyles.reviewHead}`}>
                        <h3 className="h3">{i.title}</h3>
                        <div className={changeStyles.headActions}>
                            {acting && (
                                <div className={changeStyles.actionIcons}>
                                    {!pending && (
                                        <button
                                            type="button"
                                            className={changeStyles.actionIcon}
                                            data-kind="ai"
                                            aria-label={
                                                busy ? 'AI 대응안 만드는 중…' : 'AI 대응안 만들기'
                                            }
                                            title={
                                                busy ? 'AI 대응안 만드는 중…' : 'AI 대응안 만들기'
                                            }
                                            disabled={busy}
                                            onClick={makeAiChange}
                                        >
                                            <Icon name="sparkle" />
                                        </button>
                                    )}
                                    {i.status === 'RESOLUTION_REPORTED' && (
                                        <button
                                            type="button"
                                            className={changeStyles.actionIcon}
                                            data-kind="apply"
                                            aria-label="해결 확인 후 종료"
                                            title="해결 확인 후 종료"
                                            onClick={() =>
                                                dispatch({ type: 'closeIssue', id: i.id })
                                            }
                                        >
                                            <Icon name="check" />
                                        </button>
                                    )}
                                    <button
                                        type="button"
                                        className={changeStyles.actionIcon}
                                        data-kind="followup"
                                        aria-label="추가 보고"
                                        title="추가 보고"
                                        onClick={() =>
                                            shell.openReport({ issueId: i.id, kind: 'FOLLOWUP' })
                                        }
                                    >
                                        <Icon name="plus" />
                                    </button>
                                    <SituationEditor
                                        issue={i}
                                        iconClassName={changeStyles.actionIcon}
                                    />
                                </div>
                            )}
                        </div>
                        {v.canViewIssueAnalysis(i) && (
                            <p className={`small ${report.byline}`}>
                                보고자: {nameOf(d, i.reporter)}
                            </p>
                        )}
                    </header>
                    <section className={report.section}>
                        <h4>1. 개요</h4>
                        <ul className={report.items}>
                            <li>
                                <b>구역:</b> {i.zone}
                            </li>
                            <li>
                                <b>상태:</b> {ISSUE_STATUS_LABEL[i.status]}
                            </li>
                            <li>
                                <b>접수:</b> {ago(i.at)} 전
                            </li>
                        </ul>
                    </section>
                    <section className={report.section}>
                        <h4>2. 상황</h4>
                        <ul className={report.items}>
                            <li>{i.summary}</li>
                            <li>
                                <b>운영 상황</b>
                                <SituationLine issue={i} />
                            </li>
                            {live && v.isChief && i.status !== 'CLOSED' && (
                                <AnalysisNotice issue={i} canRequest={manage} />
                            )}
                            {i.analysis && v.canViewIssueAnalysis(i) && (
                                <li>
                                    <b>보고 모델 분석(참고)</b>
                                    <ul className={report.sub}>
                                        <li>모델 분류: {i.analysis.category}</li>
                                        <li>보고 상태: {i.analysis.state}</li>
                                        <li>모델 결과는 실제 이슈 상태/위험도가 아닙니다.</li>
                                        {i.analysis.items.length > 0 && (
                                            <li>물품: {i.analysis.items.join(', ')}</li>
                                        )}
                                    </ul>
                                </li>
                            )}
                        </ul>
                    </section>
                    <section className={report.section}>
                        <h4>3. 현재 대응</h4>
                        <ul className={report.items}>
                            {applied.map((c) => (
                                <li key={c.id}>
                                    <b>적용된 대응:</b> {c.title}
                                    <ul className={report.sub}>
                                        {c.appliedAt && <li>적용 시각: {c.appliedAt}</li>}
                                        <li>변경: {c.ops.length}건</li>
                                        <li>전달: {affectedOf(c).length}명</li>
                                    </ul>
                                </li>
                            ))}
                            {!applied.length && pending && (
                                <li>
                                    <b>대응안:</b> {pending.title}
                                    <ul className={report.sub}>
                                        <li>검토 중, 확정 대기</li>
                                    </ul>
                                </li>
                            )}
                            {!applied.length && !pending && (
                                <li>
                                    <b>대응안:</b> 아직 없습니다.
                                </li>
                            )}
                            {i.status !== 'CLOSED' && i.next && (
                                <li>
                                    <b>다음 행동:</b> {i.next}
                                </li>
                            )}
                        </ul>
                    </section>
                    <IssueHistory issue={i} />
                </div>

                {/* 보고서 오른쪽(좁으면 아래): 주 행동과 전달 칸은 고정, 그 사이 관련 업무만 스크롤한다 */}
                <aside className={`${report.side} ${styles.workSide}`}>
                    <div className={report.sideActions}>
                        {ev.state !== 'LIVE' ? (
                            <span className="chip">종료된 행사 (읽기 전용)</span>
                        ) : i.status === 'CLOSED' ? (
                            <span className="chip ok">종료됨 {i.closedAt ?? ''}</span>
                        ) : manage ? null : v.canReportIssue(i) ? (
                            <>
                                <button
                                    type="button"
                                    className="btn primary"
                                    onClick={() =>
                                        shell.openReport({ issueId: i.id, kind: 'FOLLOWUP' })
                                    }
                                >
                                    추가 보고
                                </button>
                                <button
                                    type="button"
                                    className="btn"
                                    onClick={() =>
                                        shell.openReport({ issueId: i.id, kind: 'RESOLUTION' })
                                    }
                                >
                                    해결 보고
                                </button>
                            </>
                        ) : (
                            <span className="small">이 이슈는 열람만 할 수 있습니다.</span>
                        )}
                        {v.canManage && !manage && ev.state === 'LIVE' && (
                            <span className="xs">
                                대응안 만들기, 변경 확정, 이슈 종료는 WEB에서 할 수 있습니다.
                            </span>
                        )}
                    </div>
                    <div className={styles.relatedScroll}>
                        {related.length > 0 && (
                            <div className={styles.relatedGroup}>
                                <h4>관련 업무</h4>
                                {related.map((t) => (
                                    <button
                                        key={t.id}
                                        type="button"
                                        className={`btn ${styles.taskBtn}`}
                                        disabled={!shell.canOpenTask}
                                        onClick={() => shell.openTask(t.id)}
                                    >
                                        <span className={styles.taskText}>
                                            <b>{t.title}</b>
                                            <span className="xs">
                                                시간:{' '}
                                                <span className="mono">
                                                    {t.start}–{t.end}
                                                </span>
                                            </span>
                                            <span className="xs">
                                                담당자: {nameOf(d, t.assignee)}
                                            </span>
                                        </span>
                                        <TaskStatusChip status={t.status} />
                                    </button>
                                ))}
                            </div>
                        )}
                        <IssueForwardLog issue={i} />
                    </div>
                    {/* 전달 칸(부서/받는 사람) 바로 위: 대응안이 생기면 이 보고의 가장 최신 대응안을 연다 */}
                    {response && v.canManage && (
                        <button
                            type="button"
                            className={`btn ${styles.responseBtn}`}
                            onClick={() => openResponse(response.id)}
                        >
                            <Icon name="file" />
                            최신 대응안 보기
                        </button>
                    )}
                    {/* 다른 부서·사람에게 전달은 서버 API가 없어 실서버에서는 보이지 않는다(#371) */}
                    {!live && <IssueForwardPanel issue={i} />}
                </aside>
            </div>
        </div>
    );
}
