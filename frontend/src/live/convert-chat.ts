import type { Message, Room } from '../domain/types';
import type { ChatSnapshot } from '../features/live-chat/connection';
import { channelKey, type Channel } from '../features/live-chat/contract';
import { kstTime } from './convert';

// 실서버 채팅(#373). 채널(072)은 방으로, 연결의 스냅숏(받은 메시지·보내는 중)은 메시지로 바꾼다.
// 방 id는 채널 키(전체는 'ALL', 그룹은 workGroupId)다.

export function toRooms(channels: readonly Channel[]): Room[] {
    return channels.map((c) => ({
        id: channelKey(c.workGroupId),
        kind: c.workGroupId ? 'GROUP' : 'EVENT',
        name: c.name,
        ...(c.workGroupId && { groupId: c.workGroupId }),
    }));
}

/** 저장된 메시지는 서버 순번 순서로, 아직 저장되지 않은 내 메시지는 그 뒤에 상태와 함께 둔다. */
export function toMessages(snap: ChatSnapshot, me: string): Message[] {
    const stored: Message[] = snap.messages.map((m) => ({
        id: m.id,
        room: channelKey(m.workGroupId),
        seq: Number(m.sequence),
        by: m.senderMemberId,
        name: m.senderDisplayName,
        at: kstTime(m.createdAt),
        text: m.text,
    }));
    const last = stored.reduce((max, m) => Math.max(max, m.seq), 0);
    const pending: Message[] = snap.pending.map((p, i) => ({
        id: p.clientMessageId,
        room: channelKey(p.workGroupId),
        seq: last + i + 1,
        by: me,
        at: '',
        text: p.text,
        state: p.state,
    }));
    return [...stored, ...pending];
}
