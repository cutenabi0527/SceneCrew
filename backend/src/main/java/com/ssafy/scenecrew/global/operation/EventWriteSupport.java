package com.ssafy.scenecrew.global.operation;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 행사 하위 쓰기의 공통 단계(장비·안전 API). 행사 잠금 → 상태·revision 확인 → revision 올리기 → 운영 기록.
 *
 * <p>잠금 순서는 다른 행사 쓰기와 같다: Event FOR UPDATE를 먼저 잡고 그다음 Member(CurrentMembers)와 대상 행이다.
 * revision 불일치는 409 VERSION_CONFLICT(details.target), 허용하지 않는 상태는 409 INVALID_STATE다.
 */
@Component
public class EventWriteSupport {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public record EventRow(String state, long revision) { }

    private final JdbcTemplate jdbc;

    public EventWriteSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 행사를 배타 잠금으로 읽는다. 없으면 비참여와 같은 404 */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventRow lock(UUID eventId) {
        return jdbc.query("SELECT state, revision FROM event WHERE id = ? FOR UPDATE",
                (r, n) -> new EventRow(r.getString("state"), r.getLong("revision")), eventId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    public static void requireState(EventRow event, String... allowed) {
        for (String state : allowed) if (state.equals(event.state())) return;
        throw new BusinessException(ErrorCode.INVALID_STATE);
    }

    public static void requireRevision(long actual, long expected, String target) {
        if (actual != expected) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                    Map.of("target", target));
        }
    }

    /** Event.revision을 1 올리고 새 값을 돌려준다. 잠근 뒤에만 부른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long bump(UUID eventId) {
        return jdbc.queryForObject("UPDATE event SET revision = revision + 1 WHERE id = ? RETURNING revision",
                Long.class, eventId);
    }

    /** operation_log 한 줄. changes는 JSON으로 저장한다. 기록 id를 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID log(UUID eventId, String type, UUID actorMemberId, String resourceType, UUID resourceId,
            Map<String, Object> changes, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO operation_log (id, event_id, type, actor_member_id, resource_type, resource_id, changes, reason,
                                           change_set_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, NULL, NULL, ?)""",
                id, eventId, type, actorMemberId, resourceType, resourceId, MAPPER.writeValueAsString(changes),
                at.atOffset(ZoneOffset.UTC));
        return id;
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static String json(Object value) {
        return MAPPER.writeValueAsString(value);
    }
}
