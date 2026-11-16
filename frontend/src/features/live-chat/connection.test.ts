import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApi } from '../../shared/api/api';
import { IntentKeyStore } from '../../shared/scope/intent-keys';
import type { ParticipationScope } from '../../shared/scope/scope';
import { ScopeManager } from '../../shared/scope/scope-manager';
import type { IdentitySession } from '../identity/session';
import { ChatConnection, liveUrl, type ChatSnapshot } from './connection';
import { parseMessagePage, parseServerFrame } from './contract';

const EVENT = '10000000-0000-4000-8000-000000000002';
const ME = '10000000-0000-4000-8000-000000000003';
const OTHER = '10000000-0000-4000-8000-000000000009';
const GROUP = '10000000-0000-4000-8000-000000000004';

const message = (seq: string, patch: Record<string, unknown> = {}) => ({
    id: `20000000-0000-4000-8000-${seq.padStart(12, '0')}`,
    workGroupId: null,
    senderMemberId: OTHER,
    sequence: seq,
    senderDisplayName: '김현장',
    clientMessageId: `30000000-0000-4000-8000-${seq.padStart(12, '0')}`,
    text: `메시지 ${seq}`,
    createdAt: '2026-11-18T01:00:00Z',
    ...patch,
});

class FakeSocket {
    static last: FakeSocket;
    sent: Record<string, unknown>[] = [];
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: string }) => void) | null = null;
    onclose: (() => void) | null = null;
    closed = false;
    constructor(readonly url: string) {
        FakeSocket.last = this;
    }
    send(raw: string) {
        this.sent.push(JSON.parse(raw) as Record<string, unknown>);
    }
    close() {
        if (this.closed) return;
        this.closed = true;
        this.onclose?.();
    }
    server(frame: unknown) {
        this.onmessage?.({ data: JSON.stringify(frame) });
    }
}

const ready = (channels: (string | null)[]) => ({
    type: 'READY',
    memberId: ME,
    participationRevision: '1',
    subscribedChannels: channels,
});

describe('채팅 프레임/복구 페이지 검사', () => {
    it('계약에 없는 필드/타입의 프레임은 받지 않는다', () => {
        expect(parseServerFrame(JSON.stringify(ready([null])))).not.toBeNull();
        expect(parseServerFrame(JSON.stringify({ ...ready([]), extra: 1 }))).toBeNull();
        expect(parseServerFrame(JSON.stringify({ type: 'PING' }))).toBeNull();
        expect(
            parseServerFrame(
                JSON.stringify({ type: 'MESSAGE', message: { ...message('1'), text: '' } }),
            ),
        ).toBeNull();
        expect(parseServerFrame('{broken')).toBeNull();
    });
    it('안정 지점보다 뒤의 메시지가 섞인 복구 페이지는 쓰지 않는다', () => {
        expect(() =>
            parseMessagePage({
                items: [message('5')],
                nextCursor: null,
                stableThroughSequence: '4',
            }),
        ).toThrow();
    });
    it('실시간 주소는 API 주소에서 만들고 자격을 넣지 않는다', () => {
        expect(liveUrl('https://api.example.invalid/api/v1', EVENT)).toBe(
            `wss://api.example.invalid/api/v1/events/${EVENT}/ws`,
        );
    });
});

describe('채팅 연결', () => {
    let scopes: ScopeManager;
    let scope: ParticipationScope;
    let transport: ReturnType<typeof vi.fn<typeof fetch>>;
    let snap: ChatSnapshot;
    const json = (payload: unknown) =>
        new Response(JSON.stringify(payload), { headers: { 'Content-Type': 'application/json' } });

    const connect = (channel: 'WEB' | 'ANDROID' = 'WEB', token: string | null = null) => {
        const session = {
            channel,
            base: 'http://test.invalid/api/v1',
            liveAccessToken: () => token,
            api: createApi({ base: '/api/v1', transport, scopes }),
            scopes,
        } as unknown as IdentitySession;
        const c = new ChatConnection(
            session,
            scope,
            [null, GROUP],
            (url) => new FakeSocket(url) as unknown as WebSocket,
        );
        c.subscribe((s) => (snap = s));
        c.start();
        return c;
    };
    const flush = () => vi.advanceTimersByTimeAsync(0);

    beforeEach(() => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
        transport = vi.fn<typeof fetch>();
        scopes = new ScopeManager(new QueryClient(), new IntentKeyStore());
        scopes.setAccount('A');
        scope = scopes.setParticipation({
            eventId: EVENT,
            memberId: ME,
            participationRevision: '1',
        }).scope!;
    });
    afterEach(() => {
        vi.useRealTimers();
        scopes.dispose();
    });

    it('Android는 첫 프레임으로 AUTH를 보내고, READY 뒤 구독하고, 안정 지점부터 복구한다', async () => {
        transport.mockResolvedValue(
            json({
                items: [message('1'), message('2')],
                nextCursor: null,
                stableThroughSequence: '2',
            }),
        );
        connect('ANDROID', 'access-1');
        const ws = FakeSocket.last;
        expect(ws.url).not.toContain('access-1');
        ws.onopen!();
        expect(ws.sent[0]).toEqual({ type: 'AUTH', accessToken: 'access-1' });
        ws.server(ready([]));
        expect(ws.sent.slice(1)).toEqual([
            { type: 'SUBSCRIBE', workGroupId: null },
            { type: 'SUBSCRIBE', workGroupId: GROUP },
        ]);
        await vi.waitFor(() => expect(snap.messages).toHaveLength(2));
        expect(String(transport.mock.calls[0][0])).toContain('afterSequence=0');
        ws.server(ready([null, GROUP]));
        expect(snap.status).toBe('ready');
        expect(snap.subscribed).toEqual(new Set(['ALL', GROUP]));
    });

    it('WEB은 AUTH를 보내지 않고, READY가 5초 안에 오지 않으면 다시 연결한다', () => {
        transport.mockResolvedValue(
            json({ items: [], nextCursor: null, stableThroughSequence: '0' }),
        );
        connect('WEB');
        const ws = FakeSocket.last;
        ws.onopen!();
        expect(ws.sent).toEqual([]);
        vi.advanceTimersByTime(5000);
        expect(ws.closed).toBe(true);
        expect(snap.status).toBe('reconnecting');
        vi.advanceTimersByTime(1000);
        expect(FakeSocket.last).not.toBe(ws);
    });

    it('STORED를 받아야 저장됨이고, 끊기면 확인 필요로 두며 복구에서 내 메시지가 보이면 정리한다', async () => {
        transport.mockResolvedValueOnce(
            json({ items: [], nextCursor: null, stableThroughSequence: '0' }),
        );
        const c = connect();
        let ws = FakeSocket.last;
        // 연결되기 전에는 보내지 않는다
        expect(c.send(null, '먼저')).toBe(false);
        ws.onopen!();
        ws.server(ready([null]));
        await flush();
        // 확정되지 않은 그룹 채널에는 보내지 않는다
        expect(c.send(GROUP, '그룹')).toBe(false);
        expect(c.send(null, '  안녕하세요  ')).toBe(true);
        const sent = ws.sent.at(-1)!;
        expect(sent).toMatchObject({ type: 'SEND', workGroupId: null, text: '안녕하세요' });
        expect(snap.pending[0].state).toBe('sending');
        ws.server({
            type: 'STORED',
            message: message('4', {
                senderMemberId: ME,
                clientMessageId: sent.clientMessageId,
                text: '안녕하세요',
            }),
        });
        expect(snap.pending).toEqual([]);
        expect(snap.messages.map((m) => m.text)).toEqual(['안녕하세요']);

        expect(c.send(null, '두 번째')).toBe(true);
        const second = ws.sent.at(-1)!.clientMessageId as string;
        ws.close();
        expect(snap.pending[0]).toMatchObject({ clientMessageId: second, state: 'unknown' });
        transport.mockResolvedValueOnce(
            json({
                items: [
                    message('5', { senderMemberId: ME, clientMessageId: second, text: '두 번째' }),
                ],
                nextCursor: null,
                stableThroughSequence: '5',
            }),
        );
        vi.advanceTimersByTime(1000);
        ws = FakeSocket.last;
        ws.onopen!();
        ws.server(ready([null]));
        await vi.waitFor(() => expect(snap.pending).toEqual([]));
        expect(snap.messages.map((m) => m.text)).toEqual(['안녕하세요', '두 번째']);
    });

    it('서버가 거절한 메시지는 같은 clientMessageId/같은 본문으로만 다시 보낸다', async () => {
        transport.mockResolvedValue(
            json({ items: [], nextCursor: null, stableThroughSequence: '0' }),
        );
        const c = connect();
        const ws = FakeSocket.last;
        ws.onopen!();
        ws.server(ready([null]));
        await flush();
        c.send(null, '확인 부탁');
        const id = ws.sent.at(-1)!.clientMessageId as string;
        ws.server({ type: 'ERROR', code: 'RATE_LIMITED', message: '잠시 뒤', clientMessageId: id });
        expect(snap.pending[0]).toMatchObject({ state: 'failed', code: 'RATE_LIMITED' });
        expect(c.send(null, '다른 본문', id)).toBe(false);
        expect(c.send(null, '확인 부탁', id)).toBe(true);
        expect(ws.sent.at(-1)).toMatchObject({ clientMessageId: id, text: '확인 부탁' });
    });

    it('끊긴 뒤 다시 연결하면 마지막 안정 지점 뒤만 받고, 같은 메시지를 두 번 보이지 않는다', async () => {
        transport
            .mockResolvedValueOnce(
                json({ items: [message('1')], nextCursor: null, stableThroughSequence: '1' }),
            )
            .mockResolvedValueOnce(
                json({
                    items: [message('1'), message('2')],
                    nextCursor: null,
                    stableThroughSequence: '2',
                }),
            );
        connect();
        let ws = FakeSocket.last;
        ws.onopen!();
        ws.server(ready([null, GROUP]));
        await vi.waitFor(() => expect(snap.messages).toHaveLength(1));
        // 실시간으로 받은 3번은 보이지만 안정 지점을 앞당기지 않는다
        ws.server({ type: 'MESSAGE', message: message('3') });
        expect(snap.messages).toHaveLength(2);
        ws.close();
        vi.advanceTimersByTime(1000);
        ws = FakeSocket.last;
        ws.onopen!();
        ws.server(ready([null, GROUP]));
        await vi.waitFor(() => expect(transport).toHaveBeenCalledTimes(2));
        expect(String(transport.mock.calls[1][0])).toContain('afterSequence=1');
        await vi.waitFor(() =>
            expect(snap.messages.map((m) => m.sequence)).toEqual(['1', '2', '3']),
        );
    });

    it('다른 참여 회차의 READY나 권한 오류면 멈추고 다시 연결하지 않는다', () => {
        connect();
        const ws = FakeSocket.last;
        ws.onopen!();
        ws.server({ ...ready([]), participationRevision: '2' });
        expect(snap.status).toBe('stopped');
        vi.advanceTimersByTime(60_000);
        expect(FakeSocket.last).toBe(ws);
    });
});
