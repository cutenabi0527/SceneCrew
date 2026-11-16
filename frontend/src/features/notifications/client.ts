import { asUuid } from '../../shared/api/contract';
import { ScopeDisposedError } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import { stateQueryResult } from '../response/client';
import { parseNotification, parseNotificationPage, type Notification, type Page } from './contract';

// 계정 범위 조회. 읽음 표시(075)는 키 없이 한 번 보내고, 결과를 모르면 목록을 다시 조회한다.

export class NotificationDesk {
    constructor(readonly session: IdentitySession) {}

    private scope() {
        const scope = this.session.scopes.currentAccount;
        if (!scope) throw new ScopeDisposedError();
        return scope;
    }

    /** 최근 알림 첫 페이지와 다음 cursor. */
    list(cursor?: string, signal?: AbortSignal): Promise<Page<Notification>> {
        const path = `/me/notifications?limit=30${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
        return this.session.api.get(this.scope(), path, parseNotificationPage, { signal });
    }

    async read(notificationId: string) {
        const scope = this.scope();
        const id = asUuid(notificationId, 'notificationId');
        const operation = 'notifications:read';
        const intentId = crypto.randomUUID();
        const outcome = await this.session.api.command(
            scope,
            {
                operation,
                intentId,
                method: 'POST',
                path: `/notifications/${id}/read`,
                idempotency: 'none',
                response: 'data',
            },
            (d) => parseNotification(d, id),
        );
        this.session.scopes.intentKeys.release(scope, operation, intentId);
        return stateQueryResult(outcome);
    }
}
