import { classifyFailure } from '../../shared/api/failure';
import type { ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import { channelKey, parseMessagePage, parseServerFrame, type Message } from './contract';

// 채팅 실시간 연결(WebSocket)과 REST 복구(073).
// - 연결: WEB은 쿠키, Android는 첫 프레임 AUTH(Access). 자격을 URL에 넣지 않는다. READY 전에는 보내지 않는다.
// - 구독: READY 뒤 채널마다 SUBSCRIBE. 서버가 READY.subscribedChannels로 확정한 채널에서만 보낸다.
// - 복구: 연결될 때마다 마지막 안정 지점(stableThroughSequence) 뒤를 REST로 받는다. 안정 지점은 서버가 준 값만 쓰고
//   실시간으로 받은 메시지 번호로 앞당기지 않는다. 같은 메시지는 id로 한 번만 보인다.
// - 보내기: clientMessageId를 붙여 SEND. STORED를 받아야 저장됨이다. 그 전에 끊기면 "확인 필요"로 두고, 복구에서
//   같은 clientMessageId의 내 메시지가 보이면 저장됨으로 바꾼다. 다시 보낼 때도 같은 clientMessageId를 쓴다(서버 중복 방지).
// - 회수: 참여 범위가 끝나거나 서버가 권한 오류를 주면 멈추고 다시 연결하지 않는다.

export type ChatStatus = 'connecting' | 'ready' | 'reconnecting' | 'stopped';

export interface Pending {
    clientMessageId: string;
    workGroupId: string | null;
    text: string;
    // sending: 보냄(저장 확인 대기), unknown: 끊겨서 저장 여부를 모름, failed: 서버가 거절
    state: 'sending' | 'unknown' | 'failed';
    code?: string;
}

export interface ChatSnapshot {
    status: ChatStatus;
    messages: Message[];
    pending: Pending[];
    subscribed: Set<string>;
    // 복구 중 일부만 받았거나 실패했다(화면에 알린다)
    recoveryProblem: boolean;
    stopReason: string | null;
}

const STOP_CODES = new Set(['FORBIDDEN', 'UNAUTHENTICATED', 'UNAUTHORIZED', 'NOT_FOUND']);
export const READY_TIMEOUT_MS = 5000;
const MIN_RETRY = 1000;
const MAX_RETRY = 30_000;
const MAX_PAGES = 10;

export function liveUrl(base: string, eventId: string) {
    const path = `/events/${eventId}/ws`;
    if (/^https?:\/\//.test(base)) return `${base.replace(/^http/, 'ws')}${path}`;
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${location.host}${base}${path}`;
}

export class ChatConnection {
    private socket: WebSocket | null = null;
    private messages = new Map<string, Message>();
    private pending = new Map<string, Pending>();
    private subscribed = new Set<string>();
    private checkpoint = '0';
    private status: ChatStatus = 'connecting';
    private retryMs = MIN_RETRY;
    private timer: ReturnType<typeof setTimeout> | undefined;
    private readyTimer: ReturnType<typeof setTimeout> | undefined;
    private recovering: Promise<void> | null = null;
    private recoveryProblem = false;
    private stopReason: string | null = null;
    private listeners = new Set<(s: ChatSnapshot) => void>();
    private disposeScope: () => void;

    constructor(
        private readonly session: IdentitySession,
        private readonly scope: ParticipationScope,
        private readonly channels: (string | null)[],
        private readonly createSocket: (url: string) => WebSocket = (url) => new WebSocket(url),
    ) {
        this.disposeScope = scope.onDispose(() =>
            this.stop('참여 정보가 바뀌어 채팅 연결을 닫았습니다.'),
        );
    }

    subscribe(listener: (s: ChatSnapshot) => void) {
        this.listeners.add(listener);
        listener(this.snapshot());
        return () => this.listeners.delete(listener);
    }

    snapshot(): ChatSnapshot {
        return {
            status: this.status,
            messages: [...this.messages.values()].sort((a, b) =>
                BigInt(a.sequence) < BigInt(b.sequence) ? -1 : 1,
            ),
            pending: [...this.pending.values()],
            subscribed: new Set(this.subscribed),
            recoveryProblem: this.recoveryProblem,
            stopReason: this.stopReason,
        };
    }

    private emit() {
        const snap = this.snapshot();
        this.listeners.forEach((l) => l(snap));
    }

    start() {
        this.connect();
    }

    stop(reason: string | null = null) {
        if (this.status === 'stopped') return;
        this.status = 'stopped';
        this.stopReason = reason;
        clearTimeout(this.timer);
        clearTimeout(this.readyTimer);
        this.subscribed.clear();
        const socket = this.socket;
        this.socket = null;
        socket?.close();
        for (const p of this.pending.values()) if (p.state === 'sending') p.state = 'unknown';
        this.disposeScope();
        this.emit();
    }

    /** 새 메시지를 보낸다. 실패·확인 필요 메시지를 다시 보낼 때는 clientMessageId를 넘긴다. */
    send(workGroupId: string | null, text: string, clientMessageId?: string) {
        const body = text.trim();
        if (!body || body.length > 1000) return false;
        if (this.status !== 'ready' || !this.subscribed.has(channelKey(workGroupId))) return false;
        const id = clientMessageId ?? crypto.randomUUID();
        const existing = this.pending.get(id);
        // 같은 clientMessageId는 같은 본문·채널로만 다시 보낸다
        if (existing && (existing.text !== body || existing.workGroupId !== workGroupId))
            return false;
        this.pending.set(id, { clientMessageId: id, workGroupId, text: body, state: 'sending' });
        this.socket!.send(
            JSON.stringify({ type: 'SEND', workGroupId, clientMessageId: id, text: body }),
        );
        this.emit();
        return true;
    }

    /** 보내지 않기로 한 실패 메시지를 목록에서 지운다. */
    discard(clientMessageId: string) {
        const p = this.pending.get(clientMessageId);
        if (p && p.state !== 'sending') this.pending.delete(clientMessageId);
        this.emit();
    }

    private connect() {
        if (this.status === 'stopped' || !this.scope.isCurrent()) return;
        let socket: WebSocket;
        try {
            socket = this.createSocket(liveUrl(this.session.base, this.scope.eventId));
        } catch {
            this.retry();
            return;
        }
        this.socket = socket;
        socket.onopen = () => {
            if (this.socket !== socket) return;
            const token = this.session.liveAccessToken();
            if (this.session.channel === 'ANDROID') {
                if (!token) return this.stop('로그인이 필요합니다. 다시 로그인한 뒤 열어주세요.');
                socket.send(JSON.stringify({ type: 'AUTH', accessToken: token }));
            }
            this.readyTimer = setTimeout(() => socket.close(), READY_TIMEOUT_MS);
        };
        socket.onmessage = (event) => {
            if (this.socket !== socket) return;
            this.onFrame(socket, typeof event.data === 'string' ? event.data : '');
        };
        socket.onclose = () => {
            if (this.socket !== socket) return;
            this.socket = null;
            clearTimeout(this.readyTimer);
            this.subscribed.clear();
            for (const p of this.pending.values()) if (p.state === 'sending') p.state = 'unknown';
            if (this.status !== 'stopped') {
                this.status = 'reconnecting';
                this.retry();
            }
            this.emit();
        };
    }

    private retry() {
        clearTimeout(this.timer);
        this.timer = setTimeout(() => this.connect(), this.retryMs);
        this.retryMs = Math.min(this.retryMs * 2, MAX_RETRY);
    }

    private onFrame(socket: WebSocket, raw: string) {
        const frame = parseServerFrame(raw);
        if (!frame) {
            // 계약과 다른 프레임은 믿지 않고 연결을 다시 맺는다(복구로 빠진 메시지를 채운다)
            socket.close();
            return;
        }
        switch (frame.type) {
            case 'READY': {
                if (
                    frame.memberId !== this.scope.memberId ||
                    frame.participationRevision !== this.scope.participationRevision
                ) {
                    this.stop(
                        '참여 정보가 바뀌어 채팅 연결을 닫았습니다. 참여 정보를 다시 확인해주세요.',
                    );
                    return;
                }
                clearTimeout(this.readyTimer);
                this.subscribed = new Set(frame.subscribedChannels.map(channelKey));
                if (this.status !== 'ready') {
                    this.status = 'ready';
                    this.retryMs = MIN_RETRY;
                    for (const c of this.channels)
                        if (!this.subscribed.has(channelKey(c)))
                            socket.send(JSON.stringify({ type: 'SUBSCRIBE', workGroupId: c }));
                    void this.recover();
                }
                this.emit();
                return;
            }
            case 'STORED':
            case 'MESSAGE': {
                this.merge(frame.message);
                this.emit();
                return;
            }
            case 'ERROR': {
                if (frame.clientMessageId) {
                    const p = this.pending.get(frame.clientMessageId);
                    if (p) {
                        p.state = 'failed';
                        p.code = frame.code;
                    }
                    this.emit();
                    return;
                }
                if (STOP_CODES.has(frame.code)) {
                    this.stop('채팅을 볼 수 있는 권한이 없습니다. 참여 정보를 다시 확인해주세요.');
                    return;
                }
                socket.close();
                return;
            }
        }
    }

    private merge(m: Message) {
        if (m.workGroupId !== null && !this.channels.includes(m.workGroupId)) return;
        this.messages.set(m.id, m);
        if (m.senderMemberId === this.scope.memberId) this.pending.delete(m.clientMessageId);
    }

    /** 마지막 안정 지점 뒤의 메시지를 REST로 받는다. 동시에 한 번만 한다. */
    recover(): Promise<void> {
        return (this.recovering ??= this.recoverOnce().finally(() => (this.recovering = null)));
    }

    private async recoverOnce() {
        const base = `/events/${this.scope.eventId}/messages`;
        let path = `${base}?afterSequence=${this.checkpoint}&limit=100`;
        let stable: string | null = null;
        const seen = new Set<string>();
        try {
            for (let i = 0; i < MAX_PAGES; i++) {
                const page = await this.session.api.get(this.scope, path, parseMessagePage);
                // 한 번의 복구 동안 서버는 같은 안정 지점을 준다
                if (stable !== null && page.stableThroughSequence !== stable)
                    throw new Error('stable point changed');
                stable = page.stableThroughSequence;
                page.items.forEach((m) => this.merge(m));
                if (page.nextCursor === null) {
                    if (BigInt(stable) > BigInt(this.checkpoint)) this.checkpoint = stable;
                    this.recoveryProblem = false;
                    this.emit();
                    return;
                }
                if (seen.has(page.nextCursor)) break;
                seen.add(page.nextCursor);
                path = `${base}?cursor=${encodeURIComponent(page.nextCursor)}&limit=100`;
            }
            // 끝까지 받지 못했으면 안정 지점을 앞당기지 않는다
            this.recoveryProblem = true;
        } catch (error) {
            const f = classifyFailure(error);
            if (f.kind === 'access-denied' || f.kind === 'unauthenticated') {
                this.stop('채팅을 볼 수 있는 권한이 없습니다. 참여 정보를 다시 확인해주세요.');
                return;
            }
            if (f.kind !== 'cancelled') this.recoveryProblem = true;
        }
        this.emit();
    }
}
