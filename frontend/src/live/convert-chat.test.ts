import { describe, expect, it } from 'vitest';
import type { ChatSnapshot } from '../features/live-chat/connection';
import { toMessages, toRooms } from './convert-chat';

const group = '10000000-0000-4000-8000-000000000004';
const me = '10000000-0000-4000-8000-000000000003';

describe('실서버 채팅 변환(#373)', () => {
    it('전체 채널은 ALL, 그룹 채널은 그룹 id를 방 id로 쓴다', () => {
        expect(
            toRooms([
                { workGroupId: null, name: '전체' },
                { workGroupId: group, name: '음향' },
            ]),
        ).toEqual([
            { id: 'ALL', kind: 'EVENT', name: '전체' },
            { id: group, kind: 'GROUP', name: '음향', groupId: group },
        ]);
    });

    it('저장된 메시지는 보낸 사람 이름과 서버 순번으로, 저장 전 내 메시지는 상태와 함께 뒤에 둔다', () => {
        const snap = {
            status: 'ready',
            messages: [
                {
                    id: 'm1',
                    workGroupId: group,
                    senderMemberId: 'other',
                    senderDisplayName: '김현장',
                    sequence: '7',
                    clientMessageId: 'c0',
                    text: '무대 앞 정리됐습니다',
                    createdAt: '2026-11-18T01:00:00Z',
                },
            ],
            pending: [
                {
                    clientMessageId: 'c1',
                    workGroupId: null,
                    text: '확인했습니다',
                    state: 'unknown',
                },
            ],
            subscribed: new Set(['ALL', group]),
            recoveryProblem: false,
            stopReason: null,
        } as unknown as ChatSnapshot;
        const out = toMessages(snap, me);
        expect(out[0]).toMatchObject({
            id: 'm1',
            room: group,
            seq: 7,
            by: 'other',
            name: '김현장',
        });
        expect(out[0].state).toBeUndefined();
        expect(out[1]).toMatchObject({ id: 'c1', room: 'ALL', seq: 8, by: me, state: 'unknown' });
    });
});
