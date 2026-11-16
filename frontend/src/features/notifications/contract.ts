import { ContractViolationError } from '../../shared/api/contract';
import { parseV9 } from '../../shared/api/v9';

// 내 알림(074)·읽음 표시(075). 알림은 계정 단위이고 각 알림에 공연과 그때의 참여 회차가 붙어 있다.

export type TargetKind = 'TASK' | 'ISSUE' | 'CHANGE_ACK' | 'INITIAL_ASSIGNMENT';

export interface Notification {
    id: string;
    eventId: string;
    participationRevision: string;
    title: string;
    body: string;
    createdAt: string;
    readAt: string | null;
    target: { kind: TargetKind; id: string };
}

export interface Page<T> {
    items: T[];
    nextCursor: string | null;
}

export function parseNotificationPage(data: unknown): Page<Notification> {
    const page = parseV9<Page<Notification>>('NotificationPage', data);
    if (new Set(page.items.map((n) => n.id)).size !== page.items.length)
        throw new ContractViolationError('notifications.id', '같은 알림이 두 번 올 수 없습니다.');
    return page;
}

export function parseNotification(data: unknown, id: string): Notification {
    const n = parseV9<Notification>('NotificationView', data);
    if (n.id !== id)
        throw new ContractViolationError('notification.id', '요청한 알림이어야 합니다.');
    return n;
}

/** 알림 대상이 있는 화면 주소. 화면이 다시 참여 자격을 확인한다. */
export function targetPath(n: Notification) {
    const base = `/account/events/${n.eventId}`;
    switch (n.target.kind) {
        case 'TASK':
        case 'INITIAL_ASSIGNMENT':
            return `${base}/tasks`;
        case 'ISSUE':
            return `${base}/issues`;
        case 'CHANGE_ACK':
            return `${base}/changes`;
    }
}

export const TARGET_LABEL: Record<TargetKind, string> = {
    TASK: '내 업무',
    INITIAL_ASSIGNMENT: '첫 배정',
    ISSUE: '보고/대응',
    CHANGE_ACK: '변경 확인',
};
