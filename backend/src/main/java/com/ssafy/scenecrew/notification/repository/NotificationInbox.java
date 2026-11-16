package com.ssafy.scenecrew.notification.repository;

import java.util.List;
import java.util.UUID;
import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.notification.dto.NotificationView;
import com.ssafy.scenecrew.notification.service.NotificationCursor;

/** Inbox fragment of the existing NotificationRepository. */
public interface NotificationInbox {
    List<NotificationView> page(Principal principal, NotificationCursor.Position after, int size);
    NotificationView read(Principal principal, UUID id);
}
