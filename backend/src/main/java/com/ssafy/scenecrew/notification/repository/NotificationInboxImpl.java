package com.ssafy.scenecrew.notification.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.notification.dto.NotificationView;
import com.ssafy.scenecrew.notification.service.NotificationCursor;

/** SQL filters authorization BEFORE LIMIT. No stored title/body or domain details are exposed. */
public class NotificationInboxImpl implements NotificationInbox {
    private final JdbcTemplate jdbc;
    public NotificationInboxImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String FROM = """
            FROM notification n
            JOIN member m ON m.id=n.member_id AND m.event_id=n.event_id
                AND m.participation_revision=n.participation_revision AND m.active=true
            JOIN account a ON a.id=m.account_id AND a.status='ACTIVE' AND a.signup_completed=true
            JOIN event e ON e.id=n.event_id
            LEFT JOIN task t ON t.id=n.task_id AND t.event_id=n.event_id
                AND (m.event_role='CHIEF' OR t.assignee_id=m.id)
            LEFT JOIN change_acknowledgment ca ON ca.id=n.acknowledgment_id AND ca.event_id=n.event_id
                AND ca.member_id=m.id AND ca.participation_revision=m.participation_revision
            LEFT JOIN issue i ON i.id::text=n.payload#>>'{target,id}' AND i.event_id=n.event_id
                AND (m.event_role='CHIEF' OR i.reporter_member_id=m.id OR EXISTS
                    (SELECT 1 FROM report r WHERE r.issue_id=i.id AND r.author_member_id=m.id) OR EXISTS
                    (SELECT 1 FROM task it WHERE it.source_issue_id=i.id AND it.event_id=n.event_id AND it.assignee_id=m.id))
            WHERE a.id=? AND a.token_version=? AND (e.state<>'DRAFT' OR m.event_role='CHIEF')
                AND (ca.id IS NOT NULL OR t.id IS NOT NULL
                    OR (n.type='ISSUE' AND i.id IS NOT NULL) OR n.type='INITIAL_ASSIGNMENT')
            """;
    private static final String SELECT = """
            SELECT n.id, n.event_id, n.participation_revision, n.created_at, n.read_at, n.type,
                CASE WHEN ca.id IS NOT NULL THEN 'CHANGE_ACK'
                     WHEN t.id IS NOT NULL THEN 'TASK'
                     WHEN n.type='ISSUE' THEN 'ISSUE' ELSE 'INITIAL_ASSIGNMENT' END AS target_kind,
                CASE WHEN ca.id IS NOT NULL THEN ca.id WHEN t.id IS NOT NULL THEN t.id
                     WHEN n.type='ISSUE' THEN i.id ELSE e.id END AS target_id
            """;

    @Override public List<NotificationView> page(Principal principal, NotificationCursor.Position after, int size) {
        List<Object> args = new ArrayList<>(List.of(principal.accountId(), principal.tokenVersion()));
        String boundary = "";
        if (after != null) {
            boundary = " AND (n.created_at, n.id) < (?, ?)";
            args.add(Timestamp.from(after.createdAt())); args.add(after.id());
        }
        args.add(size);
        return jdbc.query(SELECT + FROM + boundary + " ORDER BY n.created_at DESC, n.id DESC LIMIT ?",
                NotificationInboxImpl::view, args.toArray());
    }

    @Override public NotificationView read(Principal principal, UUID id) {
        // Event -> Account -> Member, also used by concurrent participation work.
        // Locking actual rows makes revocation/rejoin serialize with this read marker until commit.
        var events = jdbc.query("SELECT event_id FROM notification WHERE id=?", (r, row) -> r.getObject(1, UUID.class), id);
        if (events.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        jdbc.query("SELECT id FROM event WHERE id=? FOR SHARE", (r, row) -> r.getObject(1), events.getFirst());
        jdbc.query("SELECT id FROM account WHERE id=? FOR SHARE", (r, row) -> r.getObject(1), principal.accountId());
        jdbc.query("""
                SELECT m.id FROM member m JOIN notification n ON n.member_id=m.id
                WHERE n.id=? AND m.account_id=? FOR SHARE OF m
                """, (r, row) -> r.getObject(1), id, principal.accountId());
        var visible = jdbc.query(SELECT + FROM + " AND n.id=?", NotificationInboxImpl::view,
                principal.accountId(), principal.tokenVersion(), id);
        if (visible.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        // PostgreSQL serializes duplicate updates. The first committed timestamp is retained.
        jdbc.update("UPDATE notification SET read_at=statement_timestamp() WHERE id=? AND read_at IS NULL", id);
        var result = jdbc.query(SELECT + FROM + " AND n.id=?", NotificationInboxImpl::view,
                principal.accountId(), principal.tokenVersion(), id);
        if (result.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return result.getFirst();
    }

    private static NotificationView view(ResultSet r, int row) throws SQLException {
        String kind = r.getString("target_kind");
        String title = switch (kind) {
            case "TASK" -> "업무 알림"; case "CHANGE_ACK" -> "변경 확인 알림";
            case "ISSUE" -> "보고·대응 알림"; default -> "첫 배정 알림";
        };
        Timestamp read = r.getTimestamp("read_at");
        if ("SAFETY_SUPPORT_SHORTAGE".equals(r.getString("type"))) title = "원 구역 안전 인력 부족";
        return new NotificationView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class),
                Long.toString(r.getLong("participation_revision")), title, "", r.getTimestamp("created_at").toInstant(),
                read == null ? null : read.toInstant(), new NotificationView.Target(kind, r.getObject("target_id", UUID.class)));
    }
}
