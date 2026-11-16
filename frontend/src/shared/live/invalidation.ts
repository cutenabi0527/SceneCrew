import { useEffect, useRef } from 'react';
import { classifyFailure } from '../api/failure';
import type { createApi } from '../api/api';
import type { ParticipationScope } from '../scope/scope';
import { parseV9 } from '../api/v9';
import { SseParser } from './sse';

// 행사 실시간 갱신 알림(SC-API-078 SSE). 서버는 "무엇이 바뀌었는지"만 알리고 내용은 보내지 않는다.
// 화면은 알림을 받으면 해당 자원을 다시 조회한다. 알림은 놓칠 수 있으므로(끊김·재연결) 연결될 때마다
// 모든 자원을 다시 조회하라고 알린다. 알림이 없어도 화면의 기존 조회·새로고침은 그대로 동작한다.
// - 참여 회차마다 연결 하나를 듣는 화면들이 함께 쓴다. 듣는 화면이 없으면 닫는다.
// - 화면이 숨겨지면 닫고, 다시 보이면 연다.
// - 끊기면 1초부터 두 배씩 최대 30초까지 기다렸다가 다시 연다. 403/404(권한 회수)·401(로그인 필요)이면 멈춘다.
// - 다른 공연·다른 참여 회차의 알림, 계약(SseInvalidation)과 다른 알림은 버린다.

export type LiveResource = 'EVENT' | 'TASKS' | 'NOTIFICATIONS' | 'MY_CHANGES' | 'JOBS';
export const ALL_RESOURCES: LiveResource[] = [
    'EVENT',
    'TASKS',
    'NOTIFICATIONS',
    'MY_CHANGES',
    'JOBS',
];

type Api = ReturnType<typeof createApi>;
type Listener = (resources: Set<LiveResource>) => void;

/** 무효화 이벤트 본문 검사. 계약과 다르면 null(무시한다). */
export function parseInvalidation(
    data: string,
    scope: { eventId: string; participationRevision: string },
): Set<LiveResource> | null {
    let value: { eventId: string; participationRevision: string; resources: LiveResource[] };
    try {
        value = parseV9('SseInvalidation', JSON.parse(data));
    } catch {
        return null;
    }
    if (
        value.eventId !== scope.eventId ||
        value.participationRevision !== scope.participationRevision
    )
        return null;
    return new Set(value.resources);
}

export const MIN_RETRY_MS = 1000;
export const MAX_RETRY_MS = 30_000;

class LiveStream {
    private listeners = new Set<Listener>();
    private controller: AbortController | null = null;
    private timer: number | undefined;
    private retryMs = MIN_RETRY_MS;
    private stopped = false;

    constructor(
        private readonly api: Api,
        private readonly scope: ParticipationScope,
    ) {
        scope.onDispose(() => this.close());
    }

    subscribe(listener: Listener) {
        this.listeners.add(listener);
        if (this.listeners.size === 1) {
            document.addEventListener('visibilitychange', this.onVisibility);
            this.open();
        }
        return () => {
            this.listeners.delete(listener);
            if (!this.listeners.size) this.close();
        };
    }

    private emit(resources: Set<LiveResource>) {
        for (const listener of [...this.listeners]) listener(resources);
    }

    private onVisibility = () => {
        if (document.visibilityState === 'visible') this.open();
        else this.disconnect();
    };

    private disconnect() {
        window.clearTimeout(this.timer);
        this.controller?.abort();
        this.controller = null;
    }

    private close() {
        this.disconnect();
        document.removeEventListener('visibilitychange', this.onVisibility);
        this.listeners.clear();
    }

    private retry() {
        window.clearTimeout(this.timer);
        this.timer = window.setTimeout(() => this.open(), this.retryMs);
        this.retryMs = Math.min(this.retryMs * 2, MAX_RETRY_MS);
    }

    private open() {
        if (this.controller || this.stopped || !this.listeners.size) return;
        if (!this.scope.isCurrent() || document.visibilityState !== 'visible') return;
        const controller = new AbortController();
        this.controller = controller;
        void this.read(controller);
    }

    private async read(controller: AbortController) {
        let release = () => {};
        try {
            const opened = await this.api.openStream(
                this.scope,
                `/events/${this.scope.eventId}/stream`,
                { signal: controller.signal },
            );
            release = opened.release;
            this.retryMs = MIN_RETRY_MS;
            // 연결 전후에 놓친 변경이 있을 수 있어 모두 다시 조회하게 한다
            this.emit(new Set(ALL_RESOURCES));
            const reader = opened.body.getReader();
            const decoder = new TextDecoder();
            const parser = new SseParser();
            for (;;) {
                const { value, done } = await reader.read();
                if (done) break;
                for (const e of parser.push(decoder.decode(value, { stream: true }))) {
                    if (e.event !== 'invalidate') continue;
                    const resources = parseInvalidation(e.data, this.scope);
                    if (resources) this.emit(resources);
                }
            }
        } catch (error) {
            if (controller.signal.aborted) return;
            const f = classifyFailure(error);
            if (f.kind === 'access-denied' || f.kind === 'unauthenticated') {
                this.stopped = true;
                return;
            }
        } finally {
            release();
            if (this.controller === controller) this.controller = null;
        }
        if (!controller.signal.aborted) this.retry();
    }
}

const streams = new WeakMap<ParticipationScope, LiveStream>();

function streamOf(api: Api, scope: ParticipationScope) {
    let stream = streams.get(scope);
    if (!stream) {
        stream = new LiveStream(api, scope);
        streams.set(scope, stream);
    }
    return stream;
}

/**
 * 고른 자원이 바뀌었다는 알림을 받으면 onChange를 부른다. 연결될 때마다 한 번 부른다.
 * onChange는 최신 함수를 쓰므로 렌더마다 새로 만들어도 다시 연결하지 않는다.
 */
export function useInvalidation(
    api: Api,
    scope: ParticipationScope | null,
    resources: LiveResource[],
    onChange: () => void,
) {
    const latest = useRef(onChange);
    useEffect(() => {
        latest.current = onChange;
    });
    const key = resources.join(',');
    useEffect(() => {
        if (!scope || typeof ReadableStream === 'undefined') return;
        const wanted = new Set(key.split(',') as LiveResource[]);
        return streamOf(api, scope).subscribe((changed) => {
            if ([...changed].some((r) => wanted.has(r))) latest.current();
        });
    }, [api, scope, key]);
}
