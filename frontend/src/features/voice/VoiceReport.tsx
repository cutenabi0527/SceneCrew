import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLeaveProtection } from '../../shared/navigation/leave-guard-context';
import type { Result } from '../response/client';
import { JOB_LABEL, resultText, useJob } from '../response/ui';
import { voiceDesk, type Accepted, type Recording } from './client';
import { nsProbeText } from './ns-probe';
import { useRecorder } from './useRecorder';
import type { IdentitySession } from '../identity/session';
import type { ParticipationScope } from '../../shared/scope/scope';

// 음성으로 보고 내용 채우기(#306, 팀원 APP). 녹음 → 전사 접수(060) → Job 조회(063) → 보고 내용에 채움.
// 전사문은 본인이 확인·수정한 뒤 보고로 보낸다(V9-D18: 계획이 바뀌어도 전사문을 버리지 않음).
// 재생은 원음만 한다. 전사는 항상 원음으로 하므로(V9-D41) 전사 경로 표시와 재전사 버튼을 두지 않는다.

const FAIL_TEXT: Record<string, string> = {
    NO_SPEECH: '말소리가 들리지 않았습니다. 다시 녹음하거나 글로 적어 주세요.',
    AUDIO_INVALID: '녹음 파일을 읽지 못했습니다. 다시 녹음하거나 글로 적어 주세요.',
    AI_TIMEOUT: '전사가 오래 걸려 멈췄습니다. 다시 녹음하거나 글로 적어 주세요.',
};

const TRANSCRIBING = '전사하고 있습니다.';

export function VoiceReport({
    session,
    scope,
    onTranscript,
}: {
    session: IdentitySession;
    scope: ParticipationScope;
    // 전사문과 음성 ID. 녹음을 버리면 null로 알린다(보고에 audioId를 붙이지 않게).
    onTranscript: (value: { text: string; audioId: string } | null) => void;
}) {
    const desk = useMemo(() => voiceDesk(session, scope), [session, scope]);
    // 녹음이 끝나면 바로 전사를 요청한다
    const { state, start, stop, reset, probe } = useRecorder((rec) => void send(rec));
    const [accepted, setAccepted] = useState<Accepted | null>(null);
    const [last, setLast] = useState<Result<Accepted> | null>(null);
    const [message, setMessage] = useState('');
    const [busy, setBusy] = useState(false);
    // 이 녹음으로 서버에 올라간 음성. 버릴 때 서버 사본도 지운다.
    const uploaded = useRef(new Set<string>());
    const generation = useRef(0);
    useEffect(
        () => () => {
            generation.current++;
        },
        [desk],
    );
    const read = useCallback((id: string, signal: AbortSignal) => desk.job(id, signal), [desk]);
    const { job, error: jobError, retry: retryJob } = useJob(accepted?.jobId ?? null, read);
    const recording = state.kind === 'done' ? state.recording : null;
    // '전사하고 있습니다.'는 작업이 끝나면 지운다. 남기면 성공·실패 문구와 함께 보인다(#356 휴대폰 확인).
    const finished = job?.state === 'SUCCEEDED' || job?.state === 'FAILED';
    const shownMessage = finished && message === TRANSCRIBING ? '' : message;
    const recordingActive =
        state.kind === 'requesting' || state.kind === 'recording' || state.kind === 'stopping';
    useLeaveProtection(state.kind === 'recording' || state.kind === 'stopping', 'recording');

    async function send(rec: Recording | null) {
        if (!rec || busy) return;
        const sendingGeneration = generation.current;
        setBusy(true);
        setMessage('');
        try {
            const result = await desk.transcribe(rec);
            if (sendingGeneration !== generation.current) {
                if (result.kind === 'done' && scope.isCurrent())
                    void desk.remove(result.data.audioId).catch(() => 'kept');
                return;
            }
            setLast(result);
            if (result.kind === 'done') {
                uploaded.current.add(result.data.audioId);
                setAccepted(result.data);
                setMessage(TRANSCRIBING);
            } else setMessage(resultText(result, '전사 요청'));
        } catch (error) {
            if (sendingGeneration !== generation.current) return;
            setMessage(
                error instanceof Error ? error.message.replace(/^.*?: /, '') : '보내지 못했습니다.',
            );
        } finally {
            if (sendingGeneration === generation.current) setBusy(false);
        }
    }

    useEffect(() => {
        if (job?.state === 'SUCCEEDED' && accepted)
            onTranscript({ text: job.result.text, audioId: accepted.audioId });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [job?.state, accepted?.jobId]);

    const discard = () => {
        const discardedGeneration = ++generation.current;
        setBusy(false);
        if (recording) desk.forget(recording);
        setAccepted(null);
        setLast(null);
        setMessage('');
        onTranscript(null);
        reset();
        // 서버 사본도 지운다(061). 실패해도 서버가 24시간 안에 지우므로 화면을 막지 않는다.
        const ids = [...uploaded.current];
        uploaded.current.clear();
        if (!ids.length) return;
        void Promise.all(ids.map((id) => desk.remove(id).catch(() => 'kept' as const))).then(
            (results) => {
                if (
                    results.includes('kept') &&
                    scope.isCurrent() &&
                    generation.current === discardedGeneration
                )
                    setMessage('서버의 녹음을 지우지 못했습니다. 24시간 안에 자동으로 지워집니다.');
            },
        );
    };

    return (
        <section aria-label="음성 보고" className="stack">
            {state.kind === 'unsupported' && (
                <p className="small">
                    이 기기에서는 음성 녹음을 쓸 수 없습니다. 보고 내용을 글로 적어 주세요.
                </p>
            )}
            {state.kind === 'denied' && (
                <p role="status">
                    마이크를 쓸 수 없습니다. 마이크 권한을 허용하거나 글로 적어 주세요.
                </p>
            )}
            {state.kind === 'too-large' && (
                <p role="status">녹음이 비었거나 너무 큽니다(최대 10MB). 다시 녹음해 주세요.</p>
            )}
            {state.kind === 'failed' && (
                <p role="status">
                    녹음을 시작하거나 계속할 수 없습니다. 다시 시도하거나 글로 적어 주세요.
                </p>
            )}
            {(state.kind === 'idle' ||
                state.kind === 'denied' ||
                state.kind === 'too-large' ||
                state.kind === 'failed') && (
                <button className="btn" type="button" onClick={() => void start()}>
                    음성으로 말하기(최대 30초)
                </button>
            )}
            {state.kind === 'idle' && shownMessage && <p role="status">{shownMessage}</p>}
            {state.kind === 'requesting' && <p role="status">마이크 권한을 확인하고 있습니다.</p>}
            {state.kind === 'stopping' && <p role="status">녹음을 마무리하고 있습니다.</p>}
            {state.kind === 'recording' && (
                <>
                    <p role="status">녹음 중입니다. 30초가 되면 자동으로 멈춥니다.</p>
                    <button className="btn primary" type="button" onClick={stop}>
                        말하기 끝
                    </button>
                </>
            )}
            {recordingActive && (
                <button className="btn" type="button" onClick={reset}>
                    녹음 취소
                </button>
            )}
            {probe && (state.kind === 'recording' || state.kind === 'done') && (
                <p className="xs mono">{nsProbeText(probe)}</p>
            )}
            {state.kind === 'done' && (
                <>
                    {/* 재생은 원음만 한다 */}
                    <audio controls src={state.url} aria-label="녹음 다시 듣기" />
                    {probe && (
                        <a className="btn" href={state.url} download={probe.fileName}>
                            녹음 파일 받기
                        </a>
                    )}
                    {shownMessage && <p role="status">{shownMessage}</p>}
                    {last?.kind === 'retry' && (
                        <button
                            className="btn"
                            type="button"
                            disabled={busy}
                            onClick={() => void send(recording)}
                        >
                            같은 녹음으로 다시 보내기
                        </button>
                    )}
                    {job && job.state !== 'SUCCEEDED' && job.state !== 'FAILED' && (
                        <p className="xs">전사 {JOB_LABEL[job.state]}</p>
                    )}
                    {jobError && (
                        <p role="status">
                            {jobError}{' '}
                            <button className="btn" type="button" onClick={retryJob}>
                                전사 결과 다시 조회
                            </button>
                        </p>
                    )}
                    {job?.state === 'FAILED' && (
                        <p role="status">
                            {FAIL_TEXT[job.error.code] ??
                                `전사하지 못했습니다(${job.error.code}). 글로 적어 주세요.`}
                        </p>
                    )}
                    {job?.state === 'SUCCEEDED' && (
                        <p className="xs" role="status">
                            전사문을 보고 내용에 채웠습니다. 확인하고 고친 뒤 보내 주세요.
                        </p>
                    )}
                    <div className="row">
                        <button className="btn" type="button" onClick={discard}>
                            녹음 버리기
                        </button>
                    </div>
                </>
            )}
        </section>
    );
}
