import { ContractViolationError } from '../../shared/api/contract';
import { parseV9 } from '../../shared/api/v9';

// 전체·그룹 채팅(072 채널, 073 메시지 복구, WebSocket 프레임). 첨부는 없다(V7-D21). 텍스트 1~1000자.
// sequence는 공연 하나에서 모든 채널이 함께 쓰는 단일 증가 번호다(V9-D20-3). 복구 기준점은 서버가 준 stableThroughSequence만 쓴다.

export interface Channel {
    workGroupId: string | null;
    name: string;
}

export interface Message {
    id: string;
    workGroupId: string | null;
    senderMemberId: string;
    // 보낼 때의 보낸 사람 표시 이름(V9-D20-2)
    senderDisplayName: string;
    sequence: string;
    clientMessageId: string;
    text: string;
    createdAt: string;
}

export interface MessagePage {
    items: Message[];
    nextCursor: string | null;
    stableThroughSequence: string;
}

export type ServerFrame =
    | {
          type: 'READY';
          memberId: string;
          participationRevision: string;
          subscribedChannels: (string | null)[];
      }
    | { type: 'STORED' | 'MESSAGE'; message: Message }
    | { type: 'ERROR'; code: string; message: string; clientMessageId: string | null };

export const channelKey = (workGroupId: string | null) => workGroupId ?? 'ALL';

export function parseChannelPage(data: unknown): { items: Channel[]; nextCursor: null } {
    const page = parseV9<{ items: Channel[]; nextCursor: null }>('ChannelPage', data);
    if (new Set(page.items.map((c) => channelKey(c.workGroupId))).size !== page.items.length)
        throw new ContractViolationError('channels', '같은 채널이 두 번 올 수 없습니다.');
    return page;
}

export function parseMessagePage(data: unknown): MessagePage {
    const page = parseV9<MessagePage>('V9MessageRecoveryPage', data);
    for (const m of page.items)
        if (BigInt(m.sequence) > BigInt(page.stableThroughSequence))
            throw new ContractViolationError(
                'messages.sequence',
                '안정 지점보다 뒤의 메시지는 복구 페이지에 올 수 없습니다.',
            );
    return page;
}

const FRAME_SCHEMA = {
    READY: 'WsReadyFrame',
    STORED: 'V9WsStoredFrame',
    MESSAGE: 'V9WsMessageFrame',
    ERROR: 'WsErrorFrame',
} as const;

/** 서버 프레임 검사(v9 x-websocket-contract 스키마). 계약과 다르면 null(연결을 다시 맺는다). */
export function parseServerFrame(raw: string): ServerFrame | null {
    let value: unknown;
    try {
        value = JSON.parse(raw);
    } catch {
        return null;
    }
    const type = (value as { type?: unknown } | null)?.type;
    if (typeof type !== 'string' || !(type in FRAME_SCHEMA)) return null;
    try {
        return parseV9<ServerFrame>(FRAME_SCHEMA[type as keyof typeof FRAME_SCHEMA], value);
    } catch {
        return null;
    }
}
