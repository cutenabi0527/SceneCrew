package com.ssafy.scenecrew.notification.service;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.notification.dto.NotificationView;
import com.ssafy.scenecrew.notification.repository.NotificationRepository;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    private final TransactionTemplate transaction;
    public NotificationService(NotificationRepository notifications, PlatformTransactionManager manager) {
        this.notifications = notifications;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public NotificationView.Page list(SessionAuthenticator.Principal principal, String cursor, int limit, NotificationCursor codec) {
        if (limit < 1 || limit > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var after = codec.decode(principal.accountId(), cursor);
        return available(() -> {
            var rows = notifications.page(principal, after, limit + 1);
            boolean more = rows.size() > limit;
            var items = java.util.List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
            return new NotificationView.Page(items, more ? codec.encode(principal.accountId(), items.getLast()) : null);
        });
    }
    public NotificationView read(SessionAuthenticator.Principal principal, UUID id) {
        return available(() -> transaction.execute(status -> notifications.read(principal, id)));
    }
    private static <T> T available(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException | TransactionException failure) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }
}
