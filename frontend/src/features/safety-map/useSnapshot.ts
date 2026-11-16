import { useEffect, useState } from 'react';
import { classifyFailure } from '../../shared/api/failure';
import type { Frame, SafetyReader } from './client';

// 카메라 현재 프레임 반복 조회(Q2, V9-D16).
// - 한 번에 한 요청만 보내고, 화면이 보이는 동안만 intervalMs마다 다시 받는다.
// - 화면을 닫거나 숨기거나 카메라를 바꾸면 요청을 취소하고, 늦은 응답은 버리며, 이미지 주소를 해제한다.
// - 503 SNAPSHOT_UNAVAILABLE은 "프레임 없음"이다. 이전 이미지를 현재값으로 남기지 않는다.
// - 403·404·401 또는 409(종료·비활성)는 조회를 멈추고 이미지를 지운다. 다시 열면 새로 요청한다.

export interface ShownFrame extends Omit<Frame, 'blob'> {
    url: string;
}

export function useSnapshot(reader: SafetyReader, cameraId: string, intervalMs = 2000) {
    const [frame, setFrame] = useState<ShownFrame | null>(null);
    const [status, setStatus] = useState('현재 프레임을 불러오고 있습니다.');
    const [stopped, setStopped] = useState(false);

    useEffect(() => {
        let live = true;
        let timer: number | undefined;
        let inFlight: AbortController | null = null;
        let url: string | null = null;
        const drop = () => {
            if (url) URL.revokeObjectURL(url);
            url = null;
            setFrame(null);
        };
        const schedule = (ms: number) => {
            window.clearTimeout(timer);
            timer = window.setTimeout(tick, ms);
        };
        function tick() {
            if (!live || inFlight) return;
            if (document.visibilityState !== 'visible') return;
            const mine = new AbortController();
            inFlight = mine;
            reader.snapshot(cameraId, mine.signal).then(
                (f) => {
                    // 취소된 이전 요청의 늦은 응답이 새 요청의 표시를 지우지 않게 한다
                    if (inFlight === mine) inFlight = null;
                    if (!live || mine.signal.aborted) return;
                    const next = URL.createObjectURL(f.blob);
                    if (url) URL.revokeObjectURL(url);
                    url = next;
                    setFrame({
                        url: next,
                        capturedAt: f.capturedAt,
                        receivedAt: f.receivedAt,
                        freshness: f.freshness,
                    });
                    setStatus('');
                    schedule(intervalMs);
                },
                (error: unknown) => {
                    // 취소된 이전 요청의 늦은 응답이 새 요청의 표시를 지우지 않게 한다
                    if (inFlight === mine) inFlight = null;
                    if (!live || mine.signal.aborted) return;
                    const f = classifyFailure(error);
                    if (f.kind === 'cancelled') return;
                    drop();
                    if (f.code === 'SNAPSHOT_UNAVAILABLE') {
                        setStatus('지금 받을 수 있는 프레임이 없습니다(연결 끊김 또는 시각 불명).');
                        schedule(intervalMs);
                        return;
                    }
                    if (
                        f.kind === 'access-denied' ||
                        f.kind === 'unauthenticated' ||
                        (f.status === 409 && f.code === 'INVALID_STATE')
                    ) {
                        setStopped(true);
                        setStatus(
                            f.kind === 'unauthenticated'
                                ? '로그인이 필요합니다. 미리보기를 멈췄습니다.'
                                : '이 카메라의 미리보기를 볼 수 없습니다(권한 회수/공연 종료/카메라 비활성). 미리보기를 멈췄습니다.',
                        );
                        return;
                    }
                    setStatus('프레임을 받지 못했습니다. 잠시 뒤 다시 시도합니다.');
                    schedule(intervalMs * 2);
                },
            );
        }
        const onVisibility = () => {
            if (document.visibilityState === 'visible') schedule(0);
            else {
                window.clearTimeout(timer);
                inFlight?.abort();
                inFlight = null;
                drop();
            }
        };
        document.addEventListener('visibilitychange', onVisibility);
        schedule(0);
        return () => {
            live = false;
            window.clearTimeout(timer);
            inFlight?.abort();
            document.removeEventListener('visibilitychange', onVisibility);
            if (url) URL.revokeObjectURL(url);
        };
    }, [reader, cameraId, intervalMs]);

    return { frame, status, stopped };
}
