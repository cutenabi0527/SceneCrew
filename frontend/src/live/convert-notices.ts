import type { Issue, Notice, NoticeKind, Task } from '../domain/types';
import type { Notification, TargetKind } from '../features/notifications/contract';
import { ACK_KIND_LABEL, type Acknowledgment } from '../features/response/contract';
import { kstTime } from './convert';

// 내 알림(074)과 내 변경 확인(076)을 디자인 화면의 알림함·변경 확인 카드로 바꾼다(#373).
// 알림 읽음과 변경 확인은 다른 사실이다(IA05). 읽음은 readAt, 확인은 확인 대상(ack)의 상태로만 판단한다.

const KIND: Record<TargetKind, NoticeKind> = {
    TASK: 'task',
    INITIAL_ASSIGNMENT: 'assign',
    ISSUE: 'report',
    CHANGE_ACK: 'change',
};

export interface NoticeContext {
    eventId: string;
    participationRevision: string;
    memberId: string;
    tasks: readonly Task[];
    issues: readonly Issue[];
}

/** 확인 대상의 바뀐 업무 목록(제목·바뀐 종류) */
export function ackTargets(ack: Acknowledgment) {
    return ack.targets.map((t) => ({
        taskId: t.taskId,
        title: t.title,
        changes: t.changeKinds.map((k) => ACK_KIND_LABEL[k]),
        removed: t.removed,
    }));
}

/**
 * 지금 공연·지금 참여 회차의 알림만 남긴다. 지난 회차의 알림은 지금 권한으로 열 수 없으므로 보이지 않는다.
 * 변경 확인 알림은 같은 확인 대상(ack id)을 찾아 확인 여부·대상·지문을 붙인다. 대상을 못 찾으면 확인된 것으로 본다
 * (지금 회차에서 더 확인할 것이 없다).
 */
export function toNotices(
    notifications: readonly Notification[],
    acks: readonly Acknowledgment[],
    ctx: NoticeContext,
): Notice[] {
    const ackById = new Map(acks.map((a) => [a.id, a]));
    const taskTitle = new Map(ctx.tasks.map((t) => [t.id, t.title]));
    const issueTitle = new Map(ctx.issues.map((i) => [i.id, i.title]));
    return notifications
        .filter(
            (n) =>
                n.eventId === ctx.eventId && n.participationRevision === ctx.participationRevision,
        )
        .map((n) => {
            const kind = KIND[n.target.kind];
            const base: Notice = {
                id: n.id,
                to: ctx.memberId,
                kind,
                text: n.title,
                at: kstTime(n.createdAt),
                read: n.readAt !== null,
                ack: true,
            };
            switch (n.target.kind) {
                case 'TASK': {
                    const title = taskTitle.get(n.target.id);
                    return { ...base, task: n.target.id, text: join(n.title, title) };
                }
                case 'ISSUE': {
                    const title = issueTitle.get(n.target.id);
                    return { ...base, issue: n.target.id, text: join(n.title, title) };
                }
                case 'INITIAL_ASSIGNMENT':
                    return base;
                case 'CHANGE_ACK': {
                    const ack = ackById.get(n.target.id);
                    if (!ack) return base;
                    const targets = ackTargets(ack);
                    return {
                        ...base,
                        text: join(n.title, targets.map((t) => t.title).join(', ')),
                        ack: ack.state !== 'PENDING',
                        ackId: ack.id,
                        ackTargets: targets,
                        targetIds: targets.map((t) => t.taskId),
                        targetHash: ack.targetsHash,
                        acks: ack.acknowledgedAt
                            ? [{ at: kstTime(ack.acknowledgedAt), hash: ack.targetsHash }]
                            : [],
                    };
                }
            }
        });
}

const join = (title: string, detail?: string) => (detail ? `${title} / ${detail}` : title);

/**
 * 아직 확인하지 않은 변경의 업무에 미확인 표시와 바뀐 내용을 붙인다. 데모처럼 업무 카드·오늘 화면 배너가 이 값을 읽는다.
 * 확인했거나 대상이 없으면 확인된 것으로 둔다.
 */
export function markPendingAcks(tasks: Task[], acks: readonly Acknowledgment[]) {
    const pending = new Map<string, string[]>();
    for (const a of acks) {
        if (a.state !== 'PENDING') continue;
        for (const t of a.targets)
            pending.set(t.taskId, [
                ...(pending.get(t.taskId) ?? []),
                ...t.changeKinds.map((k) => ACK_KIND_LABEL[k]),
            ]);
    }
    for (const t of tasks) {
        const changes = pending.get(t.id);
        if (!changes) continue;
        t.ack = false;
        t.changed = `${[...new Set(changes)].join(', ')} 변경`;
    }
}
