import { useMemo, useRef, useState } from 'react';
import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { classifyFailure, type Failure } from '../../shared/api/failure';
import type { EventSummary } from '../identity/contract';
import { EventAccess } from '../plan-workflow/Access';
import type { Workflow } from '../plan-workflow/client';
import type { Frame } from '../plan-workflow/frame';
import { eventCloser, type SummaryState, type SummaryText } from './client';

// 실제 서버의 공연 종료와 일회 종료 요약(A01, 총괄 WEB). 요약은 서버에 보관하지 않으므로
// 화면에서 복사하거나 로컬 파일로 저장한다. PDF·과거 요약·같은 결과 재출력은 없다.

/**
 * 운영 화면의 A01(#384)은 frame에 ShellFrame을 주고 embedded로 연다. onChanged는 종료 뒤 불린다
 * (운영 화면이 서버에서 다시 읽는다).
 */
export function LiveClosingRoute({
    eventId,
    frame = EntryFrame,
    embedded = false,
    onChanged,
}: {
    eventId?: string;
    frame?: Frame;
    embedded?: boolean;
    onChanged?: () => void;
} = {}) {
    return (
        <EventAccess eventId={eventId} frame={frame}>
            {(client, event) => (
                <LiveClosing
                    client={client}
                    event={event}
                    frame={frame}
                    embedded={embedded}
                    onChanged={onChanged}
                />
            )}
        </EventAccess>
    );
}

const STATE_LABEL = { DRAFT: '준비', RECRUITING: '모집', LIVE: '운영 중', ENDED: '종료' } as const;

// 계약(D10)이 정한 FE 문구
export const NOT_RETAINED_TEXT =
    '요약은 생성되었지만 서버에 원문을 보관하지 않아 다시 받을 수 없습니다. 필요하면 새 생성을 선택해주세요.';
export const OUTCOME_UNKNOWN_TEXT =
    '처리 결과를 확인하고 있습니다. 자동으로 다시 생성하지 않습니다.';

function rejectedText(f: Failure, action: '종료' | '요약 생성') {
    switch (f.kind) {
        case 'cancelled':
            return '';
        case 'access-denied':
            return `${action}는 현재 총괄만 할 수 있습니다. 참여 정보를 다시 확인해주세요.`;
        case 'unauthenticated':
            return '로그인이 필요합니다. 다시 로그인한 뒤 열어주세요.';
        case 'version-conflict':
            return '공연 정보가 바뀌었습니다. 현재 상태를 다시 조회한 뒤 진행해주세요.';
        case 'rate-limited':
            return '요청이 많습니다. 잠시 뒤 다시 시도해주세요.';
        default:
            return f.code === 'INVALID_STATE'
                ? `지금 공연 상태에서는 ${action}를 할 수 없습니다. 현재 상태를 다시 조회해주세요.`
                : `${action}를 처리하지 못했습니다.${f.code ? ` (${f.code})` : ''}`;
    }
}

type LogEntry = { at: string; text: string };
const now = () =>
    new Date().toLocaleTimeString('ko-KR', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
    });

function LiveClosing({
    client,
    event: initial,
    frame: Frame,
    embedded,
    onChanged,
}: {
    client: Workflow;
    event: EventSummary;
    frame: Frame;
    embedded: boolean;
    onChanged?: () => void;
}) {
    const closer = useMemo(() => eventCloser(client.session, client.scope), [client]);
    const [event, setEvent] = useState({
        name: initial.name,
        state: initial.state,
        revision: initial.revision,
    });
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const [confirmEnd, setConfirmEnd] = useState(false);
    const [result, setResult] = useState<SummaryState | null>(null);
    const [records, setRecords] = useState<LogEntry[]>([]);
    const running = useRef(false);
    const chief = initial.myParticipation.eventRole === 'CHIEF';

    const run = async (work: () => Promise<void>) => {
        if (running.current) return;
        running.current = true;
        setBusy(true);
        setMessage('');
        try {
            await work();
        } catch (error) {
            const f = classifyFailure(error);
            setMessage(
                f.kind === 'cancelled'
                    ? ''
                    : '현재 상태를 확인하지 못했습니다. 연결을 확인해주세요.',
            );
        } finally {
            running.current = false;
            setBusy(false);
        }
    };

    const refresh = () =>
        run(async () => {
            const value = await closer.read();
            setEvent({ name: value.name, state: value.state, revision: value.revision });
        });

    const end = () =>
        run(async () => {
            setConfirmEnd(false);
            const outcome = await closer.end(event.revision);
            if (outcome.kind === 'ended') {
                const e = outcome.event;
                setEvent({ name: e.name, state: e.state, revision: e.revision });
                setMessage('공연을 종료했습니다. 이제 종료 요약을 만들 수 있습니다.');
                onChanged?.();
            } else if (outcome.kind === 'version-conflict') {
                setMessage(rejectedText({ kind: 'version-conflict', intent: 'finished' }, '종료'));
            } else if (outcome.kind === 'rejected') {
                setMessage(rejectedText(outcome.failure, '종료'));
            } else {
                // 결과 불명: 다시 보내지 않고 현재 상태로 판단한다
                const value = await closer.read();
                setEvent({ name: value.name, state: value.state, revision: value.revision });
                if (value.state === 'ENDED') onChanged?.();
                setMessage(
                    value.state === 'ENDED'
                        ? '종료 응답을 받지 못했지만 현재 상태는 종료입니다.'
                        : '종료 응답을 받지 못했습니다. 현재 상태가 종료가 아니므로 필요하면 다시 종료하세요.',
                );
            }
        });

    const summarize = (fresh: boolean) =>
        run(async () => {
            const state = fresh
                ? await closer.startOver(event.revision)
                : await closer.summarize(event.revision);
            setResult(state);
            if (state.kind === 'text') setRecords([]);
        });

    const back = !embedded && (
        <Link to={`/account/events/${client.scope.eventId}`}>공연 참여 정보</Link>
    );
    if (!chief)
        return (
            <Frame title="공연 종료/요약">
                {back}
                <p role="status">공연 종료와 종료 요약은 총괄만 할 수 있습니다.</p>
            </Frame>
        );

    const ended = event.state === 'ENDED';
    return (
        <Frame title="공연 종료/요약">
            {back}
            <p>
                <b>{event.name}</b> / 현재 상태 {STATE_LABEL[event.state]}
            </p>
            <button className="btn" type="button" disabled={busy} onClick={refresh}>
                현재 상태 다시 조회
            </button>
            {message && <p role="status">{message}</p>}

            {event.state === 'DRAFT' && (
                <p className="small">
                    준비 중인 공연은 종료할 수 없습니다. 모집이나 운영 중일 때 종료합니다.
                </p>
            )}
            {(event.state === 'RECRUITING' || event.state === 'LIVE') && (
                <section className="stack" aria-label="공연 종료">
                    <p className="small">
                        종료하면 업무/보고/채팅 쓰기가 닫히고 읽기만 할 수 있습니다. 종료 요약은
                        종료한 뒤 만들 수 있습니다.
                    </p>
                    {confirmEnd ? (
                        <div className="row">
                            <button
                                className="btn primary"
                                type="button"
                                disabled={busy}
                                onClick={end}
                            >
                                종료 확정
                            </button>
                            <button
                                className="btn ghost"
                                type="button"
                                onClick={() => setConfirmEnd(false)}
                            >
                                취소
                            </button>
                        </div>
                    ) : (
                        <button className="btn" type="button" onClick={() => setConfirmEnd(true)}>
                            공연 종료
                        </button>
                    )}
                </section>
            )}

            {ended && (
                <section className="stack" aria-label="종료 요약">
                    <p className="small">
                        요약은 서버에 보관하지 않습니다. 이 화면을 떠나면 사라지며, 다시 만들면
                        문장이 달라질 수 있습니다. 필요하면 복사하거나 파일로 저장하세요.
                    </p>
                    <SummaryResult
                        result={result}
                        busy={busy}
                        pending={closer.hasIntent}
                        eventName={event.name}
                        onGenerate={() => summarize(false)}
                        onCheck={() => summarize(false)}
                        onStartOver={() => summarize(true)}
                        onRecord={(text) => setRecords((r) => [...r, { at: now(), text }])}
                    />
                    {records.length > 0 && (
                        <ul aria-label="로컬 보관 기록" className="small">
                            {records.map((r, i) => (
                                <li key={i}>
                                    {r.at} {r.text}
                                </li>
                            ))}
                        </ul>
                    )}
                </section>
            )}
        </Frame>
    );
}

function SummaryResult({
    result,
    busy,
    pending,
    eventName,
    onGenerate,
    onCheck,
    onStartOver,
    onRecord,
}: {
    result: SummaryState | null;
    busy: boolean;
    pending: boolean;
    eventName: string;
    onGenerate: () => void;
    onCheck: () => void;
    onStartOver: () => void;
    onRecord: (text: string) => void;
}) {
    const [confirmNew, setConfirmNew] = useState(false);
    const startOver = confirmNew ? (
        <div className="row">
            <button
                className="btn primary"
                type="button"
                disabled={busy}
                onClick={() => {
                    setConfirmNew(false);
                    onStartOver();
                }}
            >
                새로 만들기 확정
            </button>
            <button className="btn ghost" type="button" onClick={() => setConfirmNew(false)}>
                취소
            </button>
        </div>
    ) : (
        <button
            className="btn ghost"
            type="button"
            disabled={busy}
            onClick={() => setConfirmNew(true)}
        >
            새로 만들기
        </button>
    );
    const newNote = confirmNew && (
        <p className="small" role="note">
            새 요청으로 요약을 다시 만듭니다. 이전 요약과 문장이 다를 수 있습니다.
        </p>
    );

    if (!result)
        return pending ? (
            <>
                <p role="status">이전 요약 요청의 결과를 아직 확인하지 못했습니다.</p>
                <button className="btn primary" type="button" disabled={busy} onClick={onCheck}>
                    {busy ? '확인 중…' : '결과 확인'}
                </button>
                {newNote}
                {startOver}
            </>
        ) : (
            <button className="btn primary" type="button" disabled={busy} onClick={onGenerate}>
                {busy ? '요약 만드는 중…' : '종료 요약 만들기'}
            </button>
        );

    switch (result.kind) {
        case 'text':
            return (
                <>
                    <SummaryView
                        summary={result.summary}
                        eventName={eventName}
                        onRecord={onRecord}
                    />
                    {newNote}
                    {startOver}
                </>
            );
        case 'not-retained':
            return (
                <>
                    <p role="status">{NOT_RETAINED_TEXT}</p>
                    {newNote}
                    {startOver}
                </>
            );
        case 'outcome-unknown':
            return (
                <>
                    <p role="status">{OUTCOME_UNKNOWN_TEXT}</p>
                    <p className="small">
                        서버가 이 요청의 처리 여부를 확정하지 못했습니다. 필요하면 새 생성을 직접
                        선택하세요.
                    </p>
                    {newNote}
                    {startOver}
                </>
            );
        case 'no-response':
        case 'in-progress':
            return (
                <>
                    <p role="status">
                        {result.kind === 'in-progress'
                            ? '같은 요약 요청을 서버가 처리하고 있습니다.'
                            : '요약 응답을 받지 못했습니다.'}{' '}
                        결과 확인은 같은 요청으로 처리 여부만 확인하며 새로 생성하지 않습니다.
                    </p>
                    <button className="btn primary" type="button" disabled={busy} onClick={onCheck}>
                        {busy ? '확인 중…' : '결과 확인'}
                    </button>
                    {newNote}
                    {startOver}
                </>
            );
        case 'rejected':
            return (
                <>
                    <p role="status">{rejectedText(result.failure, '요약 생성')}</p>
                    <button
                        className="btn primary"
                        type="button"
                        disabled={busy}
                        onClick={result.failure.intent === 'pending' ? onCheck : onStartOver}
                    >
                        다시 요청
                    </button>
                </>
            );
    }
}

function SummaryView({
    summary,
    eventName,
    onRecord,
}: {
    summary: SummaryText;
    eventName: string;
    onRecord: (text: string) => void;
}) {
    const markdown = summary.format === 'MARKDOWN';
    const fileName = `${eventName} 종료 요약.${markdown ? 'md' : 'txt'}`;
    const save = () => {
        const url = URL.createObjectURL(
            new Blob([summary.text], { type: markdown ? 'text/markdown' : 'text/plain' }),
        );
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        a.click();
        URL.revokeObjectURL(url);
        onRecord(`파일 저장 요청: ${fileName} (${summary.text.length}자)`);
    };
    const copy = () => {
        const clipboard = navigator.clipboard as Clipboard | undefined;
        if (!clipboard) {
            onRecord('복사 실패: 이 브라우저에서 클립보드를 쓸 수 없습니다. 파일로 저장하세요.');
            return;
        }
        clipboard.writeText(summary.text).then(
            () => onRecord(`복사함 (${summary.text.length}자)`),
            () => onRecord('복사 실패: 클립보드 권한을 확인하거나 파일로 저장하세요.'),
        );
    };
    return (
        <>
            <textarea aria-label="종료 요약 본문" readOnly rows={14} value={summary.text} />
            <div className="row">
                <button className="btn primary" type="button" onClick={copy}>
                    복사
                </button>
                <button className="btn" type="button" onClick={save}>
                    {markdown ? 'Markdown 저장' : '텍스트 저장'}
                </button>
            </div>
        </>
    );
}
