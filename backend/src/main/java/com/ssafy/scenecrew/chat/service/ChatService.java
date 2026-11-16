package com.ssafy.scenecrew.chat.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 채팅 채널(072)·메시지 복구(073)·보내기(WebSocket SEND). #237·#238 오세진 2026-11-05. 규칙은 backend/docs/chat.md.
 *
 * <ul>
 *   <li>채널: 전체(workGroupId null)와 직무 그룹. 총괄은 활성 그룹 전부, 팀원은 자기 소속 그룹만(be/10 "그룹 안내/채팅").</li>
 *   <li>메시지 번호는 행사 하나에서 모든 채널이 함께 쓰는 단일 증가 번호다(V9-D20-3).</li>
 *   <li>보내기는 행사별 트랜잭션 잠금을 잡고 번호를 정해 저장한다. 잠금이 커밋까지 유지되므로 번호 순서대로 커밋된다.
 *       그래서 커밋된 가장 큰 번호 H 아래에는 앞으로 커밋될 번호가 없고, 복구는 H를 안정 상한(stableThroughSequence)으로 준다.</li>
 *   <li>보낸 사람 이름은 Member 표시 이름을 조회해 넣는다(2026-11-05 오세진 결정: 스키마 변경 없음).</li>
 * </ul>
 */
@Service
public class ChatService {
    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_TEXT = 1000;
    private static final int MAX_NAME = 20;

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;

    public ChatService(JdbcTemplate jdbc, CurrentMembers members) {
        this.jdbc = jdbc;
        this.members = members;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"workGroupId", "name"})
    public record Channel(UUID workGroupId, String name) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChannelPage(List<Channel> items, Object nextCursor) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "workGroupId", "senderMemberId", "senderDisplayName", "sequence", "clientMessageId", "text",
            "createdAt"})
    public record Message(UUID id, UUID workGroupId, UUID senderMemberId, String senderDisplayName, String sequence,
            String clientMessageId, String text, String createdAt) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"items", "nextCursor", "stableThroughSequence"})
    public record MessagePage(List<Message> items, String nextCursor, String stableThroughSequence) { }

    /** 연결된 사람의 현재 참여 정보. WebSocket이 보낼 때·받을 때마다 다시 확인한다. */
    public record Participant(UUID memberId, long participationRevision, boolean chief, UUID workGroupId) { }

    /** 보내기 결과. stored가 false면 같은 clientMessageId의 기존 메시지다(다시 알리지 않는다). */
    public record Sent(Message message, boolean stored) { }

    // ---- 072 ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ChannelPage channels(UUID eventId, UUID accountId) {
        Member me = members.require(eventId, accountId);
        List<Channel> items = new ArrayList<>();
        items.add(new Channel(null, "전체"));
        String sql = "SELECT id, name FROM work_group WHERE event_id = ? AND active"
                + (me.getEventRole() == EventRole.CHIEF ? "" : " AND id = ?") + " ORDER BY name, id";
        Object[] args = me.getEventRole() == EventRole.CHIEF ? new Object[] {eventId} : new Object[] {eventId, me.getWorkGroupId()};
        if (me.getEventRole() == EventRole.CHIEF || me.getWorkGroupId() != null)
            items.addAll(jdbc.query(sql, (r, n) -> new Channel(r.getObject(1, UUID.class), r.getString(2)), args));
        return new ChannelPage(items, null);
    }

    // ---- 073 ---------------------------------------------------------------------------------------------------

    /**
     * 첫 쪽에서 안정 상한 H를 정하고 afterSequence < 번호 <= H를 번호 순으로 준다. 다음 쪽(cursor)은 같은 H·같은 채널 범위로 이어서 주며,
     * 쪽마다 현재 접근을 다시 확인한다. 참여 회차가 바뀌었으면 이전 cursor는 403이다.
     *
     * @param workGroupId 없으면 볼 수 있는 모든 채널(전체 + 소속/허용 그룹)
     */
    @Transactional(readOnly = true)
    public MessagePage messages(UUID eventId, UUID accountId, UUID workGroupId, boolean channelGiven, Long afterSequence,
            String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) throw invalid();
        Participant me = participant(eventId, accountId);
        long lower;
        long stable;
        String scope;
        if (cursor != null) {
            Cursor c = Cursor.decode(cursor);
            if (c.participationRevision() != me.participationRevision()) throw new BusinessException(ErrorCode.FORBIDDEN);
            lower = c.last();
            stable = c.stable();
            scope = c.scope();
        } else {
            lower = afterSequence == null ? 0 : afterSequence;
            stable = jdbc.queryForObject("SELECT coalesce(max(sequence), 0) FROM chat_message WHERE event_id = ?", Long.class,
                    eventId);
            scope = channelGiven ? Objects.toString(workGroupId, "ALL") : "*";
        }
        List<UUID> groups = new ArrayList<>();
        boolean includeAll;
        if ("*".equals(scope)) {
            includeAll = true;
            groups.addAll(accessibleGroups(eventId, me));
        } else if ("ALL".equals(scope)) {
            includeAll = true;
        } else {
            UUID group = UUID.fromString(scope);
            if (!accessibleGroups(eventId, me).contains(group)) throw new BusinessException(ErrorCode.FORBIDDEN);
            includeAll = false;
            groups.add(group);
        }
        List<Message> rows = jdbc.query(SELECT + """
                 WHERE m.event_id = ? AND m.sequence > ? AND m.sequence <= ?
                   AND ((m.work_group_id IS NULL AND ?) OR m.work_group_id = ANY(?))
                 ORDER BY m.sequence LIMIT ?""", (r, n) -> message(r),
                eventId, lower, stable, includeAll, groups.toArray(UUID[]::new), size + 1);
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        String next = more ? new Cursor(stable, Long.parseLong(page.getLast().sequence()), scope, me.participationRevision())
                .encode() : null;
        return new MessagePage(page, next, Long.toString(stable));
    }

    // ---- WebSocket이 쓰는 확인·보내기 ------------------------------------------------------------------------------

    /** 이 행사의 활성 Member인지 확인하고 지금 참여 정보를 돌려준다. 아니면 404 */
    @Transactional(readOnly = true)
    public Participant participant(UUID eventId, UUID accountId) {
        Member me = members.require(eventId, accountId);
        return new Participant(me.getId(), me.getParticipationRevision(), me.getEventRole() == EventRole.CHIEF,
                me.getWorkGroupId());
    }

    /** 지금 이 채널을 볼 수 있는지. 전체(null)는 활성 Member면 된다. */
    @Transactional(readOnly = true)
    public boolean canAccess(UUID eventId, Participant me, UUID workGroupId) {
        return workGroupId == null || accessibleGroups(eventId, me).contains(workGroupId);
    }

    /**
     * 메시지를 저장한다. 보낸 사람이 여전히 같은 회차의 활성 Member이고 채널에 접근할 수 있어야 한다(아니면 403).
     * 같은 사람·채널·clientMessageId가 이미 있으면 본문이 같을 때 그 메시지를, 다르면 409 IDEMPOTENCY_MISMATCH를 돌려준다.
     */
    @Transactional
    public Sent send(UUID eventId, UUID accountId, long participationRevision, UUID workGroupId, String clientMessageId,
            String text) {
        members.require(eventId, accountId);
        String state = jdbc.queryForObject("SELECT state FROM event WHERE id = ? FOR SHARE", String.class, eventId);
        Member current = members.requireLocked(eventId, accountId);
        Participant me = new Participant(current.getId(), current.getParticipationRevision(),
                current.getEventRole() == EventRole.CHIEF, current.getWorkGroupId());
        if (me.participationRevision() != participationRevision || !canAccess(eventId, me, workGroupId))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        // 행사별 잠금: 커밋까지 유지되어 번호 순서대로 커밋된다(복구의 안정 상한 근거).
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, "chat:" + eventId);
        var existing = jdbc.query(SELECT + """
                 WHERE m.event_id = ? AND m.sender_member_id = ? AND m.client_message_id = ?
                   AND m.work_group_id IS NOT DISTINCT FROM ?""", (r, n) -> message(r),
                eventId, me.memberId(), clientMessageId, workGroupId);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().text().equals(text)) throw new BusinessException(ErrorCode.IDEMPOTENCY_MISMATCH);
            return new Sent(existing.getFirst(), false);
        }
        if (!Set.of("RECRUITING", "LIVE").contains(state))
            throw new BusinessException(ErrorCode.INVALID_STATE);
        long sequence = jdbc.queryForObject("SELECT coalesce(max(sequence), 0) + 1 FROM chat_message WHERE event_id = ?",
                Long.class, eventId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chat_message (id, event_id, work_group_id, sender_member_id, sequence, client_message_id, text, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, eventId, workGroupId, me.memberId(), sequence, clientMessageId, text,
                Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        return new Sent(jdbc.query(SELECT + " WHERE m.id = ?", (r, n) -> message(r), id).getFirst(), true);
    }

    // ---- 내부 ------------------------------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT m.id, m.work_group_id, m.sender_member_id, s.display_name, m.sequence, m.client_message_id, m.text, m.created_at
              FROM chat_message m JOIN member s ON s.id = m.sender_member_id
            """;

    private static Message message(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Message(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class), name(r.getString(4)),
                Long.toString(r.getLong(5)), r.getString(6), r.getString(7), r.getTimestamp(8).toInstant().toString());
    }

    /** 계약 senderDisplayName은 1~20자다. 더 길면 20자에서 자른다. */
    private static String name(String value) {
        String v = value == null || value.isBlank() ? "참여자" : value.strip();
        return v.codePointCount(0, v.length()) <= MAX_NAME ? v : v.substring(0, v.offsetByCodePoints(0, MAX_NAME));
    }

    private Set<UUID> accessibleGroups(UUID eventId, Participant me) {
        if (me.chief())
            return new LinkedHashSet<>(jdbc.queryForList("SELECT id FROM work_group WHERE event_id = ? AND active", UUID.class, eventId));
        if (me.workGroupId() == null) return Set.of();
        return new LinkedHashSet<>(jdbc.queryForList("SELECT id FROM work_group WHERE id = ? AND event_id = ? AND active",
                UUID.class, me.workGroupId(), eventId));
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }

    /** "v1|H|마지막 번호|채널 범위(*·ALL·그룹 ID)|참여 회차"를 base64url로 감싼 값 */
    record Cursor(long stable, long last, String scope, long participationRevision) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("v1|" + stable + "|" + last + "|" + scope + "|" + participationRevision).getBytes(StandardCharsets.UTF_8));
        }
        static Cursor decode(String value) {
            if (value.isEmpty() || value.length() > 200 || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
                if (p.length != 5 || !"v1".equals(p[0]) || !p[1].matches("[0-9]{1,18}") || !p[2].matches("[0-9]{1,18}")
                        || !p[4].matches("[0-9]{1,18}")) throw invalid();
                if (!"*".equals(p[3]) && !"ALL".equals(p[3]) && !UUID.fromString(p[3]).toString().equals(p[3])) throw invalid();
                return new Cursor(Long.parseLong(p[1]), Long.parseLong(p[2]), p[3], Long.parseLong(p[4]));
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
        }
    }
}
