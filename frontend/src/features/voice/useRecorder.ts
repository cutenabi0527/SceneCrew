import { useEffect, useRef, useState } from 'react';
import { AUDIO_TYPE, MAX_AUDIO_BYTES, MAX_AUDIO_SECONDS, type Recording } from './client';
import { microphoneRequest, readNsProbe, type NsProbe } from './ns-probe';

// 마이크 녹음(webm/Opus, 최대 30초). 녹음이 끝나면 마이크를 바로 놓는다.
// webm/Opus를 지원하지 않는 기기는 음성 보고를 막고 텍스트·고정 보고로 안내한다(V9-D19-1).

export type RecorderState =
    | { kind: 'idle' }
    | { kind: 'unsupported' }
    | { kind: 'denied' }
    | { kind: 'requesting' }
    | { kind: 'failed' }
    | { kind: 'recording'; startedAt: number }
    | { kind: 'stopping' }
    | { kind: 'too-large' }
    | { kind: 'done'; recording: Recording; url: string };

export function recorderSupported() {
    return (
        typeof window !== 'undefined' &&
        typeof window.MediaRecorder !== 'undefined' &&
        !!navigator.mediaDevices?.getUserMedia &&
        MediaRecorder.isTypeSupported(AUDIO_TYPE)
    );
}

interface RecorderRun {
    recorder: MediaRecorder | null;
    media: MediaStream | null;
    timer?: number;
}

function release(run: RecorderRun) {
    window.clearTimeout(run.timer);
    run.timer = undefined;
    run.media?.getTracks().forEach((track) => track.stop());
    run.media = null;
}

function detach(run: RecorderRun) {
    if (!run.recorder) return;
    run.recorder.ondataavailable = null;
    run.recorder.onstop = null;
    run.recorder.onerror = null;
}

function discard(run: RecorderRun) {
    // stop/track.stop 뒤에도 이벤트가 올 수 있어 결과 처리기를 먼저 뗀다.
    detach(run);
    try {
        if (run.recorder && run.recorder.state !== 'inactive') run.recorder.stop();
    } catch {
        // 녹음기 종료가 실패해도 마이크 트랙은 반드시 놓는다.
    } finally {
        release(run);
    }
}

export function useRecorder(onRecorded?: (recording: Recording) => void) {
    const latest = useRef(onRecorded);
    useEffect(() => {
        latest.current = onRecorded;
    });
    const [state, setState] = useState<RecorderState>(() =>
        recorderSupported() ? { kind: 'idle' } : { kind: 'unsupported' },
    );
    // 재렌더링 전 연속 클릭과 취소된 권한 응답을 요청 객체로 구분한다.
    const current = useRef<RecorderRun | null>(null);
    const [probe, setProbe] = useState<NsProbe | null>(null);
    const mounted = useRef(false);
    const url = useRef<string | null>(null);

    const dropUrl = () => {
        if (url.current) URL.revokeObjectURL(url.current);
        url.current = null;
    };

    useEffect(() => {
        mounted.current = true;
        return () => {
            mounted.current = false;
            const run = current.current;
            current.current = null;
            if (run) discard(run);
            dropUrl();
        };
    }, []);

    const fail = (run: RecorderRun) => {
        if (current.current !== run) return;
        current.current = null;
        discard(run);
        setState({ kind: 'failed' });
    };

    const finish = (run: RecorderRun) => {
        if (current.current !== run || !run.recorder || run.recorder.state === 'inactive') return;
        setState({ kind: 'stopping' });
        try {
            run.recorder.stop();
            // stop 이벤트를 기다리는 동안에도 마이크를 놓는다.
            release(run);
        } catch {
            fail(run);
        }
    };

    const start = async () => {
        if (!mounted.current || current.current || !recorderSupported()) return;
        const run: RecorderRun = { recorder: null, media: null };
        current.current = run;
        dropUrl();
        setState({ kind: 'requesting' });
        setProbe(null);
        const mode = readNsProbe();
        try {
            const media = await navigator.mediaDevices.getUserMedia(microphoneRequest(mode));
            if (current.current !== run) {
                media.getTracks().forEach((track) => track.stop());
                return;
            }
            run.media = media;
            // 브라우저가 요청을 무시할 수 있어 요청값이 아니라 실제 적용값을 보여 준다.
            if (mode)
                setProbe({
                    mode,
                    settings: media.getAudioTracks()[0]?.getSettings() ?? {},
                    fileName: `ns-${mode}-${Date.now()}.webm`,
                });
            const chunks: Blob[] = [];
            const rec = new MediaRecorder(media, { mimeType: AUDIO_TYPE });
            run.recorder = rec;
            rec.ondataavailable = (e) => {
                if (current.current === run && e.data.size) chunks.push(e.data);
            };
            rec.onerror = () => fail(run);
            rec.onstop = () => {
                if (current.current !== run) return;
                current.current = null;
                detach(run);
                release(run);
                const blob = new Blob(chunks, { type: AUDIO_TYPE });
                if (!blob.size || blob.size > MAX_AUDIO_BYTES) {
                    setState({ kind: 'too-large' });
                    return;
                }
                const next = URL.createObjectURL(blob);
                url.current = next;
                const recording = { id: crypto.randomUUID(), blob };
                setState({ kind: 'done', recording, url: next });
                latest.current?.(recording);
            };
            rec.start();
            if (current.current !== run) return;
            setState({ kind: 'recording', startedAt: Date.now() });
            run.timer = window.setTimeout(() => finish(run), MAX_AUDIO_SECONDS * 1000);
        } catch {
            if (current.current !== run) return;
            const kind = run.media ? 'failed' : 'denied';
            current.current = null;
            discard(run);
            setState({ kind });
        }
    };

    const stop = () => {
        if (current.current) finish(current.current);
    };

    const reset = () => {
        const run = current.current;
        current.current = null;
        if (run) discard(run);
        dropUrl();
        setState(recorderSupported() ? { kind: 'idle' } : { kind: 'unsupported' });
    };

    return { state, start, stop, reset, probe };
}
