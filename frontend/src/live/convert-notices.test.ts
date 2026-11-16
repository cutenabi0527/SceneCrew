import { describe, expect, it } from 'vitest';
import type { Task } from '../domain/types';
import type { Notification } from '../features/notifications/contract';
import type { Acknowledgment } from '../features/response/contract';
import { markPendingAcks, toNotices } from './convert-notices';

const eventId = '10000000-0000-4000-8000-000000000002';
const memberId = '10000000-0000-4000-8000-000000000003';
const taskId = '10000000-0000-4000-8000-000000000006';
const ackId = '10000000-0000-4000-8000-0000000000a9';

const note = (over: Partial<Notification>): Notification => ({
    id: '10000000-0000-4000-8000-0000000000b1',
    eventId,
    participationRevision: '1',
    title: '업무 알림',
    body: '',
    createdAt: '2026-11-18T01:00:00Z',
    readAt: null,
    target: { kind: 'TASK', id: taskId },
    ...over,
});
const ack = (over: Partial<Acknowledgment> = {}): Acknowledgment => ({
    id: ackId,
    participationRevision: '1',
    state: 'PENDING',
    targetsHash: 'hash-1',
    targets: [
        {
            taskId,
            title: '마이크 점검',
            changeKinds: ['TIME', 'ZONE'],
            removed: false,
            changedAt: '2026-11-18T00:59:00Z',
        },
    ],
    acknowledgedAt: null,
    ...over,
});
const task = (over: Partial<Task> = {}): Task => ({
    id: taskId,
    title: '마이크 점검',
    groupId: 'g',
    zone: '무대',
    start: '10:00',
    end: '10:30',
    assignee: memberId,
    bundle: 'b',
    status: 'todo',
    ack: true,
    steps: [],
    checklist: [],
    ...over,
});
const ctx = { eventId, participationRevision: '1', memberId, tasks: [task()], issues: [] };

describe('알림함 변환(#373)', () => {
    it('지금 공연/지금 참여 회차의 알림만 남기고 읽음은 readAt으로 정한다', () => {
        const out = toNotices(
            [
                note({ id: '10000000-0000-4000-8000-0000000000c1' }),
                note({ id: '10000000-0000-4000-8000-0000000000c2', eventId: 'other' }),
                note({ id: '10000000-0000-4000-8000-0000000000c3', participationRevision: '0' }),
                note({
                    id: '10000000-0000-4000-8000-0000000000c4',
                    readAt: '2026-11-18T01:05:00Z',
                }),
            ],
            [],
            ctx,
        );
        expect(out.map((n) => [n.id, n.read])).toEqual([
            ['10000000-0000-4000-8000-0000000000c1', false],
            ['10000000-0000-4000-8000-0000000000c4', true],
        ]);
        expect(out[0]).toMatchObject({ kind: 'task', task: taskId, to: memberId });
        expect(out[0].text).toBe('업무 알림 / 마이크 점검');
    });

    it('변경 확인 알림은 같은 확인 대상의 상태/서버 지문/바뀐 업무를 붙인다(읽음과 확인은 별개)', () => {
        const [n] = toNotices(
            [
                note({
                    title: '변경 확인 알림',
                    readAt: '2026-11-18T01:05:00Z',
                    target: { kind: 'CHANGE_ACK', id: ackId },
                }),
            ],
            [ack()],
            ctx,
        );
        expect(n).toMatchObject({
            kind: 'change',
            read: true,
            ack: false,
            ackId,
            targetHash: 'hash-1',
            targetIds: [taskId],
            ackTargets: [
                { taskId, title: '마이크 점검', changes: ['시간', '구역'], removed: false },
            ],
        });
        const [done] = toNotices(
            [note({ target: { kind: 'CHANGE_ACK', id: ackId } })],
            [ack({ state: 'ACKNOWLEDGED', acknowledgedAt: '2026-11-18T01:10:00Z' })],
            ctx,
        );
        expect(done.ack).toBe(true);
        expect(done.acks).toHaveLength(1);
    });

    it('확인 대상을 찾지 못한 변경 알림은 더 확인할 것이 없는 것으로 본다', () => {
        const [n] = toNotices([note({ target: { kind: 'CHANGE_ACK', id: ackId } })], [], ctx);
        expect(n.ack).toBe(true);
        expect(n.ackId).toBeUndefined();
    });

    it('아직 확인하지 않은 변경의 업무에 미확인 표시와 바뀐 종류를 붙인다', () => {
        const tasks = [task(), task({ id: 'other' })];
        markPendingAcks(tasks, [ack()]);
        expect(tasks[0]).toMatchObject({ ack: false, changed: '시간, 구역 변경' });
        expect(tasks[1].ack).toBe(true);
        const confirmed = [task()];
        markPendingAcks(confirmed, [ack({ state: 'ACKNOWLEDGED' })]);
        expect(confirmed[0].ack).toBe(true);
    });
});
