import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { useLeaveProtection } from '../../shared/navigation/leave-guard-context';
import { EventAccess } from '../plan-workflow/Access';
import type { Workflow } from '../plan-workflow/client';
import type { Plan } from '../plan-workflow/contract';
import { VoiceReport } from '../voice/VoiceReport';
import { ChangeSetPanel } from './ChangeSetPanel';
import { RecoveryPanel } from './RecoveryPanel';
import { responder as responderOf, type Responder, type Result } from './client';
import {
    ISSUE_STATUS_LABEL,
    REPORT_KIND_LABEL,
    type ChangeSet,
    type Issue,
    type Report,
} from './contract';
import { SituationPanel } from './SituationPanel';
import { JOB_LABEL, readText, resultText, useJob } from './ui';

// 실제 서버의 상황 보고·대응(#287). 총괄은 WEB에서 보고를 검토하고 대응안을 승인하며,
// 팀원은 APP에서 보고를 작성하고 본인 보고에 추가·해결 보고를 남긴다.
// 녹음·전사(#306), 채팅·알림(#307), 복구 계산(#308)은 각 이슈에서 연결한다.

export function LiveResponseRoute() {
    return (
        <EventAccess>
            {(client, event) =>
                event.myParticipation?.eventRole === 'CHIEF' ? (
                    <ResponseBoard client={client} />
                ) : (
                    <ReportDesk client={client} />
                )
            }
        </EventAccess>
    );
}

type Context = { revision: string; plan: Plan };

const readContext = async (client: Workflow): Promise<Context> => {
    const { event, plan } = await client.read();
    return { revision: event.revision, plan };
};

/** 공연 revision과 구역·그룹. 조회는 effect 밖의 함수로 두고 결과만 반영한다. */
function usePlanContext(client: Workflow) {
    const [context, setContext] = useState<Context | null>(null);
    const [error, setError] = useState('');
    useEffect(() => {
        let active = true;
        readContext(client).then(
            (value) => {
                if (active) {
                    setContext(value);
                    setError('');
                }
            },
            (e: unknown) => {
                if (active) setError(readText(e));
            },
        );
        return () => {
            active = false;
        };
    }, [client]);
    return { context, error };
}

/** 보고 원문. 작성 당시 참여 회차가 현재와 다르면 함께 알린다. */
function ReportList({
    reports,
    scope,
}: {
    reports: Report[];
    scope: { memberId: string; participationRevision: string };
}) {
    return (
        <ol aria-label="보고 원문" className="stack small">
            {reports.map((r) => (
                <li key={r.id}>
                    <b>{REPORT_KIND_LABEL[r.kind]}</b> {r.text}
                    <span className="xs">
                        {' '}
                        / 작성 당시 참여 회차 {r.participationRevision}
                        {r.authorMemberId === scope.memberId &&
                            r.participationRevision !== scope.participationRevision &&
                            ` (현재 회차 ${scope.participationRevision} 이전에 작성)`}
                    </span>
                </li>
            ))}
            {!reports.length && <li>볼 수 있는 보고 원문이 없습니다.</li>}
        </ol>
    );
}

// 총괄 WEB ------------------------------------------------------------------

function ResponseBoard({ client }: { client: Workflow }) {
    const responder = useMemo(() => responderOf(client.session, client.scope), [client]);
    const [context, setContext] = useState<Context | null>(null);
    const [issues, setIssues] = useState<Issue[] | null>(null);
    const [selected, setSelected] = useState<string | null>(null);
    const [sets, setSets] = useState<ChangeSet[]>([]);
    const [openSet, setOpenSet] = useState<string | null>(null);
    const [message, setMessage] = useState('보고 목록을 불러오고 있습니다.');
    const [busy, setBusy] = useState(false);
    const [instruction, setInstruction] = useState('');
    const [analysisJob, setAnalysisJob] = useState<string | null>(null);
    const [changeJob, setChangeJob] = useState<string | null>(null);

    // 공연 revision·계획과 보고·변경안을 함께 다시 읽는다. 반영은 show가 한다.
    const fetchAll = useCallback(async () => {
        const [ctx, page, setPage] = await Promise.all([
            readContext(client),
            responder.issues(),
            responder.changeSets(),
        ]);
        return { ctx, page, setPage };
    }, [client, responder]);
    const show = useCallback((v: Awaited<ReturnType<typeof fetchAll>>) => {
        setContext(v.ctx);
        setIssues(v.page.items);
        setSets(v.setPage.items);
        setMessage(v.page.items.length ? '' : '접수된 보고가 없습니다.');
    }, []);
    const refresh = useCallback(async () => {
        try {
            show(await fetchAll());
        } catch (e) {
            setMessage(readText(e));
        }
    }, [fetchAll, show]);
    // 처음 열 때, 그리고 분석·대응안 생성이 끝났을 때 현재 상태를 다시 조회한다.
    // 결과 후보를 화면이 직접 적용하지 않고, 대응안은 서버가 만든 변경안을 연다.
    const readAnalysis = useCallback(
        (id: string, signal: AbortSignal) => responder.analysisJob(id, signal),
        [responder],
    );
    const readChange = useCallback(
        (id: string, signal: AbortSignal) => responder.changeJob(id, signal),
        [responder],
    );
    const analysis = useJob(analysisJob, readAnalysis);
    const change = useJob(changeJob, readChange);
    const analyzed = analysis.job?.state === 'SUCCEEDED' ? analysis.job.jobId : null;
    const createdSet = change.job?.state === 'SUCCEEDED' ? change.job.result.changeSetId : null;
    useEffect(() => {
        let active = true;
        fetchAll().then(
            (v) => {
                if (!active) return;
                show(v);
                if (createdSet) setOpenSet(createdSet);
            },
            (e: unknown) => {
                if (active) setMessage(readText(e));
            },
        );
        return () => {
            active = false;
        };
    }, [fetchAll, show, analyzed, createdSet]);

    const issue = issues?.find((i) => i.id === selected) ?? null;
    const issueSets = sets.filter((s) => s.issueId === selected);
    const shownSet = sets.find((s) => s.id === openSet) ?? null;
    const revision = context?.revision ?? issue?.eventRevision ?? '';
    const zoneName = (id: string) => context?.plan.zones.find((z) => z.id === id)?.name ?? '구역';
    const groupName = (id: string) =>
        context?.plan.workGroups.find((g) => g.id === id)?.name ?? '직무 그룹';

    const act = async (action: string, work: () => Promise<Result<unknown>>, done: string) => {
        if (busy) return;
        setBusy(true);
        try {
            const result = await work();
            if (result.kind === 'job' || result.kind === 'done') setMessage(done);
            else {
                if (result.kind !== 'retry') await refresh();
                setMessage(resultText(result, action));
            }
            return result;
        } finally {
            setBusy(false);
        }
    };

    const startAnalysis = async () => {
        const latest = issue?.reports.at(-1);
        if (!issue || !latest) return;
        const result = await act(
            '분석 요청',
            () => responder.analyze(latest.id, revision),
            '보고 분석을 요청했습니다. 결과가 나오면 상황 유형과 구역 후보가 보입니다.',
        );
        if (result?.kind === 'job') setAnalysisJob(result.jobId);
    };

    const generate = async () => {
        if (!issue || !instruction.trim()) return;
        const result = await act(
            '대응안 생성',
            () =>
                responder.generateChange({
                    issueId: issue.id,
                    instruction: instruction.trim(),
                    reportIds: issue.reports.map((r) => r.id),
                    expectedEventRevision: revision,
                }),
            '대응안 생성을 요청했습니다. 만들어지면 아래에서 현재와 비교할 수 있습니다.',
        );
        if (result?.kind === 'job') setChangeJob(result.jobId);
    };

    const setStatus = (status: 'RESPONDING' | 'CLOSED') =>
        issue &&
        act(
            '상태 변경',
            () => responder.patchIssue(issue.id, { status }, revision),
            status === 'CLOSED' ? '이슈를 종료했습니다.' : '대응을 시작했습니다.',
        ).then(() => refresh());

    return (
        <EntryFrame title="보고/대응">
            <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
            <p className="small">
                총괄: 현장 보고 원문과 분석을 확인하고, 대응안을 현재 계획과 비교한 뒤 승인합니다.
                승인 전에는 계획이 바뀌지 않습니다.
            </p>
            <button className="btn" type="button" disabled={busy} onClick={() => void refresh()}>
                보고 목록 새로고침
            </button>
            {message && <p role="status">{message}</p>}
            <ul aria-label="보고 목록" className="stack">
                {(issues ?? []).map((i) => (
                    <li key={i.id}>
                        <button
                            type="button"
                            className="btn block"
                            aria-pressed={i.id === selected}
                            onClick={() => {
                                setSelected(i.id);
                                setOpenSet(null);
                            }}
                        >
                            {i.title} / {ISSUE_STATUS_LABEL[i.status]}
                        </button>
                        <p className="xs">
                            {zoneName(i.zoneId)} / {groupName(i.workGroupId)} / 보고{' '}
                            {i.reports.length}건
                        </p>
                    </li>
                ))}
            </ul>
            {issue && (
                <section aria-label="보고 상세" className="stack">
                    <h2 className="h3">{issue.title}</h2>
                    <p className="small">
                        {ISSUE_STATUS_LABEL[issue.status]} / {zoneName(issue.zoneId)} /{' '}
                        {groupName(issue.workGroupId)}
                    </p>
                    <ReportList reports={issue.reports} scope={client.scope} />
                    <b>분석</b>
                    {issue.analysis ? (
                        <p className="small">
                            {issue.analysis.category ?? '미분류'} /{' '}
                            {issue.analysis.reportedState ?? '확인된 상황 없음'}
                            {issue.analysis.analysisValidity !== 'CURRENT' &&
                                ' / 분석 이후 공연 정보가 바뀌었습니다. 다시 분석할 수 있습니다.'}
                        </p>
                    ) : (
                        <p className="small">아직 분석 결과가 없습니다.</p>
                    )}
                    {analysis.job && (
                        <p role="status" className="xs">
                            분석 {JOB_LABEL[analysis.job.state]}
                            {analysis.job.state === 'FAILED' && ` (${analysis.job.error.code})`}
                        </p>
                    )}
                    {analysis.error && <p role="status">{analysis.error}</p>}
                    <SituationPanel
                        key={issue.id}
                        situation={issue.situation}
                        busy={busy}
                        onSubmit={async (input) => {
                            const result = await act(
                                '상황 정정',
                                () => responder.correctSituation(issue.id, input, revision),
                                '운영 상황을 기록했습니다.',
                            );
                            if (result?.kind === 'done') {
                                await refresh();
                                setMessage('운영 상황을 기록했습니다.');
                                return true;
                            }
                            return false;
                        }}
                    />
                    <div className="row">
                        <button
                            className="btn"
                            type="button"
                            disabled={busy}
                            onClick={startAnalysis}
                        >
                            보고 분석 요청
                        </button>
                        {issue.status === 'RECEIVED' && (
                            <button
                                className="btn"
                                type="button"
                                disabled={busy}
                                onClick={() => void setStatus('RESPONDING')}
                            >
                                대응 시작
                            </button>
                        )}
                        {issue.status !== 'CLOSED' && (
                            <button
                                className="btn"
                                type="button"
                                disabled={busy}
                                onClick={() => void setStatus('CLOSED')}
                            >
                                이슈 종료
                            </button>
                        )}
                    </div>
                    <label className="field">
                        <span>대응 지시</span>
                        <textarea
                            aria-label="대응 지시"
                            value={instruction}
                            maxLength={1000}
                            onChange={(e) => setInstruction(e.target.value)}
                        />
                    </label>
                    <button
                        className="btn"
                        type="button"
                        disabled={busy || !instruction.trim()}
                        onClick={generate}
                    >
                        대응안 만들기
                    </button>
                    {change.job && (
                        <p role="status" className="xs">
                            대응안 생성 {JOB_LABEL[change.job.state]}
                            {change.job.state === 'FAILED' && ` (${change.job.error.code})`}
                        </p>
                    )}
                    {change.error && <p role="status">{change.error}</p>}
                    {change.job?.state === 'SUCCEEDED' && !!change.job.result.questions.length && (
                        <ul aria-label="대응안 추가 질문" className="small">
                            {change.job.result.questions.map((q) => (
                                <li key={q.key}>{q.question}</li>
                            ))}
                        </ul>
                    )}
                    <RecoveryPanel
                        key={issue.id}
                        client={client}
                        responder={responder}
                        issueId={issue.id}
                        revision={revision}
                        onCreated={(set) => {
                            setSets((prev) => [set, ...prev.filter((s) => s.id !== set.id)]);
                            setOpenSet(set.id);
                        }}
                    />
                    <b>대응안</b>
                    <ul aria-label="이 보고의 대응안" className="stack">
                        {issueSets.map((s) => (
                            <li key={s.id}>
                                <button
                                    type="button"
                                    className="btn block"
                                    aria-pressed={s.id === openSet}
                                    onClick={() => setOpenSet(s.id)}
                                >
                                    {s.rationale || '변경안'} / 연산 {s.operations.length}개
                                </button>
                            </li>
                        ))}
                        {!issueSets.length && <li className="small">아직 대응안이 없습니다.</li>}
                    </ul>
                </section>
            )}
            {shownSet && (
                <ChangeSetPanel
                    key={shownSet.id}
                    responder={responder}
                    set={shownSet}
                    eventRevision={revision}
                    onChanged={(next) => {
                        setSets((prev) => prev.map((s) => (s.id === next.id ? next : s)));
                        if (next.status === 'APPLIED') void refresh();
                    }}
                    onRefresh={refresh}
                />
            )}
        </EntryFrame>
    );
}

// 팀원 APP ------------------------------------------------------------------

function ReportDesk({ client }: { client: Workflow }) {
    const responder = useMemo(() => responderOf(client.session, client.scope), [client]);
    const { context, error: contextError } = usePlanContext(client);
    const [issues, setIssues] = useState<Issue[] | null>(null);
    const [zoneId, setZoneId] = useState('');
    const [text, setText] = useState('');
    const [audioId, setAudioId] = useState<string | null>(null);
    const [voiceKey, setVoiceKey] = useState(0);
    const [message, setMessage] = useState('');
    const [listMessage, setListMessage] = useState('내 보고를 불러오고 있습니다.');
    const [busy, setBusy] = useState(false);
    const [last, setLast] = useState<Result<unknown> | null>(null);
    useLeaveProtection(text.trim().length > 0, 'unsaved');

    const refresh = useCallback(async () => {
        try {
            const page = await responder.issues();
            setIssues(page.items);
            setListMessage(page.items.length ? '' : '아직 보낸 보고가 없습니다.');
        } catch (e) {
            setListMessage(readText(e));
        }
    }, [responder]);
    useEffect(() => {
        let active = true;
        responder.issues().then(
            (page) => {
                if (!active) return;
                setIssues(page.items);
                setListMessage(page.items.length ? '' : '아직 보낸 보고가 없습니다.');
            },
            (e: unknown) => {
                if (active) setListMessage(readText(e));
            },
        );
        return () => {
            active = false;
        };
    }, [responder]);

    const zones = context?.plan.zones ?? [];
    const pending = responder.pending('createIssue', 'new');

    const submit = async () => {
        if (busy || !zoneId || !text.trim()) return;
        setBusy(true);
        setMessage('');
        try {
            const result = await responder.createIssue({ zoneId, text: text.trim(), audioId });
            setLast(result);
            if (result.kind === 'done') {
                setText('');
                setAudioId(null);
                // 보낸 녹음은 화면에서 지운다
                setVoiceKey((n) => n + 1);
                setMessage('보고를 보냈습니다. 총괄이 확인하면 대응 상태가 바뀝니다.');
                await refresh();
            } else {
                if (result.kind === 'check') await refresh();
                setMessage(resultText(result, '보고'));
            }
        } finally {
            setBusy(false);
        }
    };

    const restart = () => {
        responder.forget('createIssue', 'new');
        setLast(null);
        setMessage(
            '새 보고로 다시 작성합니다. 위 목록에 이미 접수된 보고가 있는지 먼저 확인해주세요.',
        );
    };

    return (
        <EntryFrame title="상황 보고">
            <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
            <p className="small">
                현장에서 확인한 상황을 보냅니다. 보낸 원문은 고칠 수 없고, 필요하면 추가 보고를
                남깁니다.
            </p>
            {contextError && <p role="status">{contextError}</p>}
            <section aria-label="새 보고" className="stack">
                <label className="field">
                    <span>구역</span>
                    <select
                        aria-label="구역"
                        value={zoneId}
                        onChange={(e) => setZoneId(e.target.value)}
                    >
                        <option value="">구역 선택</option>
                        {zones.map((z) => (
                            <option key={z.id} value={z.id}>
                                {z.name}
                            </option>
                        ))}
                    </select>
                </label>
                <VoiceReport
                    key={voiceKey}
                    session={client.session}
                    scope={client.scope}
                    onTranscript={(value) => {
                        setAudioId(value?.audioId ?? null);
                        if (value) setText(value.text);
                    }}
                />
                <label className="field">
                    <span>보고 내용</span>
                    <textarea
                        aria-label="보고 내용"
                        value={text}
                        maxLength={1000}
                        onChange={(e) => setText(e.target.value)}
                    />
                </label>
                <p className="xs">{[...text].length}/1000</p>
                {message && <p role="status">{message}</p>}
                <div className="row">
                    <button
                        className="btn primary"
                        type="button"
                        disabled={busy || (!pending && (!zoneId || !text.trim()))}
                        onClick={submit}
                    >
                        {last?.kind === 'retry' ? '같은 내용으로 다시 보내기' : '보고 보내기'}
                    </button>
                    {last?.kind === 'check' && (
                        <button className="btn" type="button" onClick={restart}>
                            새 보고로 다시 작성
                        </button>
                    )}
                </div>
            </section>
            <button className="btn" type="button" onClick={() => void refresh()}>
                내 보고 새로고침
            </button>
            {listMessage && <p role="status">{listMessage}</p>}
            <ul aria-label="내 보고" className="stack">
                {(issues ?? []).map((i) => (
                    <MyIssue
                        key={i.id}
                        issue={i}
                        responder={responder}
                        client={client}
                        onChanged={refresh}
                    />
                ))}
            </ul>
        </EntryFrame>
    );
}

function MyIssue({
    issue,
    responder,
    client,
    onChanged,
}: {
    issue: Issue;
    responder: Responder;
    client: Workflow;
    onChanged: () => Promise<void>;
}) {
    const [kind, setKind] = useState<'FOLLOWUP' | 'RESOLUTION'>('FOLLOWUP');
    const [text, setText] = useState('');
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const [last, setLast] = useState<Result<unknown> | null>(null);
    useLeaveProtection(text.trim().length > 0, 'unsaved');
    const closed = issue.status === 'CLOSED';
    const target = `${issue.id}:${kind}`;

    const send = async () => {
        if (busy || (!text.trim() && !responder.pending('addReport', target))) return;
        setBusy(true);
        try {
            const result = await responder.addReport(issue.id, kind, text.trim());
            setLast(result);
            if (result.kind === 'done') {
                setText('');
                setMessage(
                    kind === 'RESOLUTION' ? '해결 보고를 보냈습니다.' : '추가 보고를 보냈습니다.',
                );
                await onChanged();
            } else {
                if (result.kind === 'check') await onChanged();
                setMessage(resultText(result, '보고'));
            }
        } finally {
            setBusy(false);
        }
    };

    return (
        <li className="stack">
            <b>
                {issue.title} / {ISSUE_STATUS_LABEL[issue.status]}
            </b>
            <ReportList reports={issue.reports} scope={client.scope} />
            {!closed && (
                <>
                    <label className="field">
                        <span>보고 종류</span>
                        <select
                            aria-label={`${issue.title} 보고 종류`}
                            value={kind}
                            onChange={(e) => setKind(e.target.value as 'FOLLOWUP' | 'RESOLUTION')}
                        >
                            <option value="FOLLOWUP">추가 보고</option>
                            <option value="RESOLUTION">해결 보고</option>
                        </select>
                    </label>
                    <label className="field">
                        <span>내용</span>
                        <textarea
                            aria-label={`${issue.title} 추가 내용`}
                            value={text}
                            maxLength={1000}
                            onChange={(e) => setText(e.target.value)}
                        />
                    </label>
                    {message && <p role="status">{message}</p>}
                    <div className="row">
                        <button className="btn" type="button" disabled={busy} onClick={send}>
                            {last?.kind === 'retry' ? '같은 내용으로 다시 보내기' : '보내기'}
                        </button>
                        {last?.kind === 'check' && (
                            <button
                                className="btn"
                                type="button"
                                onClick={() => {
                                    responder.forget('addReport', target);
                                    setLast(null);
                                    setMessage('목록에서 접수 여부를 확인한 뒤 새로 작성해주세요.');
                                }}
                            >
                                새로 작성
                            </button>
                        )}
                    </div>
                </>
            )}
        </li>
    );
}
