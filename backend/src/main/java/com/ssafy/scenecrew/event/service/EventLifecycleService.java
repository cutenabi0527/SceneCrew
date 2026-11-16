package com.ssafy.scenecrew.event.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.dto.EventListResponse.EventItem;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;

/**
 * 행사 생성·수정·공개·시작·종료(SC-API-022·024·029·030·031, S15P31A202-278). 응답은 행사 조회(023)와 같은 Event 모양이다.
 *
 * <ul>
 *   <li>022 생성: DRAFT Event와 활성 CHIEF Member를 한 트랜잭션에 만들고 초대 토큰을 발급한다(be/04 v7.1). 요청 키 필수, 201.</li>
 *   <li>024 수정: CHIEF·DRAFT·기대 revision. 들어온 필드만 바꾸고 완결성은 검사하지 않는다. 실제로 바뀐 값이 있을 때만
 *       revision과 lastInputModifiedAt을 올린다.</li>
 *   <li>029 공개: CHIEF·DRAFT·기대 revision·요청 키. 문서 추출 중이면 409 INVALID_STATE, 기본정보가 모자라면
 *       422 EVENT_INCOMPLETE(기본정보 완결성과 공개 계획 검사, PublishPlanChecks). RECRUITING으로 바꾸고 초대 토큰이 준비됐는지 확인한다(InviteTokens).</li>
 *   <li>030 시작: CHIEF·RECRUITING → LIVE. 031 종료: CHIEF·RECRUITING/LIVE → ENDED, 종료 시각·사유를 남긴다.</li>
 * </ul>
 * 상태가 바뀌는 요청은 모두 revision을 1 올린다. 잠금 순서는 Event → Member(CurrentMembers.requireChiefLocked)다.
 * 비멤버·없는 행사는 404, 팀원은 403, 상태가 맞지 않으면 409 INVALID_STATE, revision이 다르면 409 VERSION_CONFLICT(target=event).
 */
public class EventLifecycleService {
    public static final String CREATE_OPERATION = "SC-API-022";
    public static final String PUBLISH_OPERATION = "SC-API-029";
    /** 문서 추출 작업 종류. 추출 중에는 공개하지 않는다(be/08 "추출 중 publish"). */
    static final String EXTRACT_KIND = "EXTRACT_EVENT_INPUT";

    /** 024에서 바꿀 수 있는 필드와 열. 순서는 계약 EventPatch 순서 */
    public static final Map<String, String> PATCH_COLUMNS;
    static {
        var columns = new LinkedHashMap<String, String>();
        columns.put("name", "name");
        columns.put("description", "description");
        columns.put("date", "date");
        columns.put("prepStartAt", "prep_start_at");
        columns.put("openAt", "open_at");
        columns.put("closeAt", "close_at");
        columns.put("teardownEndAt", "teardown_end_at");
        columns.put("locationName", "location_name");
        columns.put("address", "address");
        columns.put("totalCapacity", "total_capacity");
        columns.put("expectedVisitors", "expected_visitors");
        columns.put("peakVisitors", "peak_visitors");
        columns.put("desiredReserveCount", "desired_reserve_count");
        PATCH_COLUMNS = java.util.Collections.unmodifiableMap(columns);
    }

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final InviteTokens inviteTokens;
    private final PublishVenueLayout venueLayout;
    private final PublishLinkSlots linkSlots;

    public EventLifecycleService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency,
            InviteTokens inviteTokens, PublishVenueLayout venueLayout, PublishLinkSlots linkSlots) {
        this.jdbc = jdbc;
        this.members = members;
        this.idempotency = idempotency;
        this.inviteTokens = inviteTokens;
        this.venueLayout = venueLayout;
        this.linkSlots = linkSlots;
    }

    /** 022. name은 앞뒤 공백을 지운 1~20자, description은 없으면 빈 문자열(컨트롤러가 검사) */
    @Transactional
    public IdempotentResult<EventItem> create(UUID accountId, String key, String name, String description) {
        var request = new IdempotencyRequest(accountId, CREATE_OPERATION, key,
                IdempotencyRequest.hash(name, description), null, null, null);
        var result = idempotency.run(request, EventItem.class, () -> {
            // 표시 이름은 참여(035)와 같이 계정의 현재 표시 이름을 쓴다.
            String displayName = jdbc.query("""
                    SELECT display_name FROM account WHERE id = ? AND status = 'ACTIVE' AND signup_completed = true
                    FOR SHARE""", (r, n) -> r.getString(1), accountId).stream().findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
            UUID eventId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO event (id, name, description, state, revision, last_input_modified_at)
                    VALUES (?, ?, ?, 'DRAFT', 1, ?)""", eventId, name, description, utc(now()));
            jdbc.update("""
                    INSERT INTO member (id, event_id, account_id, work_group_id, crew_no, event_role, display_name,
                                        available, is_reserve, active, participation_revision)
                    VALUES (?, ?, ?, NULL, 1, 'CHIEF', ?, true, false, true, 1)""",
                    memberId, eventId, accountId, displayName);
            inviteTokens.issue(eventId);
            return IdempotentResult.of(201, read(accountId, memberId), "EVENT_CREATED",
                    Map.of("eventId", eventId.toString(), "memberId", memberId.toString()));
        });
        // 생성은 대상 행사가 없어 요청 키에 행사·회차를 묶지 못한다. 그래서 재요청이면 만든 그 행사에 지금도 활성
        // Member이고 참여 회차가 최초 응답과 같은지 확인한다(최소 현재 접근, 029와 같은 원칙). 접근을 잃었거나 나갔다가
        // 다시 들어와 회차가 바뀌었으면 옛 회차의 최초 본문을 다시 보이지 않고 404다(#378).
        if (result.replayed()) {
            Member me = members.require(result.body().id(), accountId);
            String created = result.body().myParticipation().participationRevision();
            if (!String.valueOf(me.getParticipationRevision()).equals(created)) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
        }
        return result;
    }

    /**
     * 024. changes는 들어온 필드만 담는다(키는 {@link #PATCH_COLUMNS}). 값 null은 비우기다.
     * 값 형식은 컨트롤러가 검사한다: 문자열, LocalDate, Instant(마이크로초), Long.
     */
    @Transactional
    public EventItem patch(UUID eventId, UUID accountId, Map<String, Object> changes, long expectedRevision) {
        EventRow event = lock(eventId);
        Member chief = members.requireChiefLocked(eventId, accountId);
        requireState(event, "DRAFT");
        requireRevision(event, expectedRevision);

        var current = event.values();
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (var change : changes.entrySet()) {
            String column = PATCH_COLUMNS.get(change.getKey());
            if (column == null) throw new IllegalArgumentException(change.getKey());
            if (Objects.equals(current.get(change.getKey()), change.getValue())) continue;
            sets.add(column + " = ?");
            args.add(change.getValue() instanceof Instant at ? utc(at) : change.getValue());
        }
        // 같은 값만 보낸 수정은 입력을 바꾸지 않으므로 revision을 올리지 않는다.
        if (!sets.isEmpty()) {
            args.add(utc(now()));
            args.add(eventId);
            jdbc.update("UPDATE event SET " + String.join(", ", sets)
                    + ", revision = revision + 1, last_input_modified_at = ? WHERE id = ?", args.toArray());
        }
        return read(accountId, chief.getId());
    }

    /** 029 */
    @Transactional
    public IdempotentResult<EventItem> publish(UUID eventId, UUID accountId, String key, long expectedRevision) {
        EventRow event = lock(eventId);
        // 완료 결과 복구는 현재 활성 Member·원래 참여 회차만 확인한다(최소 현재 접근). 총괄을 인계한 뒤에도
        // 같은 키 재요청은 최초 응답을 받는다. 총괄·상태·revision은 새로 실행할 때만 업무 안에서 검사한다.
        Member member = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, PUBLISH_OPERATION, key,
                IdempotencyRequest.hash(eventId, expectedRevision), eventId, member.getId(),
                member.getParticipationRevision());
        return idempotency.run(request, EventItem.class, () -> {
            if (member.getEventRole() != EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
            requireState(event, "DRAFT");
            requireRevision(event, expectedRevision);
            Boolean extracting = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM ai_job WHERE event_id = ? AND kind = ? AND state IN ('QUEUED', 'RUNNING'))""",
                    Boolean.class, eventId, EXTRACT_KIND);
            if (Boolean.TRUE.equals(extracting)) throw new BusinessException(ErrorCode.INVALID_STATE);
            // 기본정보 완결성과 공개 계획 검사(V9-D38)의 위반을 모두 모아 한 번에 돌려준다.
            var violations = new ArrayList<>(EventCompleteness.check(event.fields()));
            violations.addAll(PublishPlanChecks.check(jdbc, eventId, event.fields()));
            EventCompleteness.require(violations);
            // 미확정 초안의 평면도가 있으면 안전 지도로 확정한다(V9-D45). 없으면 아무것도 하지 않는다.
            venueLayout.save(eventId, member.getId());
            // 계획 생성 결과의 카메라·장비로 칸을 만들고 연결 업무를 담당 업무로 둔다(V9-D58·D59, #395).
            linkSlots.save(eventId, member.getId());
            jdbc.update("UPDATE event SET state = 'RECRUITING', revision = revision + 1 WHERE id = ?", eventId);
            inviteTokens.activate(eventId);
            EventItem published = read(accountId, member.getId());
            return IdempotentResult.of(200, published, "EVENT_PUBLISHED",
                    Map.of("eventId", eventId.toString(), "publishedRevision", published.revision()));
        });
    }

    /** 030 */
    @Transactional
    public EventItem start(UUID eventId, UUID accountId, long expectedRevision) {
        EventRow event = lock(eventId);
        Member chief = members.requireChiefLocked(eventId, accountId);
        requireState(event, "RECRUITING");
        requireRevision(event, expectedRevision);
        jdbc.update("UPDATE event SET state = 'LIVE', revision = revision + 1 WHERE id = ?", eventId);
        return read(accountId, chief.getId());
    }

    /** 031. reason은 앞뒤 공백을 지우고, 비면 null(컨트롤러가 정리) */
    @Transactional
    public EventItem end(UUID eventId, UUID accountId, String reason, long expectedRevision) {
        EventRow event = lock(eventId);
        Member chief = members.requireChiefLocked(eventId, accountId);
        requireState(event, "RECRUITING", "LIVE");
        requireRevision(event, expectedRevision);
        jdbc.update("""
                UPDATE event SET state = 'ENDED', revision = revision + 1, ended_at = ?, end_reason = ?
                 WHERE id = ?""", utc(now()), reason, eventId);
        return read(accountId, chief.getId());
    }

    // ---- 내부 -------------------------------------------------------------------------------------------

    /** 행사를 배타 잠금으로 읽는다. 없으면 비멤버와 같은 404 */
    private EventRow lock(UUID eventId) {
        return jdbc.query("""
                SELECT state, revision, name, description, date, prep_start_at, open_at, close_at, teardown_end_at,
                       location_name, address, total_capacity, expected_visitors, peak_visitors, desired_reserve_count
                  FROM event WHERE id = ? FOR UPDATE""", EventLifecycleService::eventRow, eventId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private EventItem read(UUID accountId, UUID memberId) {
        return jdbc.query(EventListService.SELECT + " AND m.id = ?", (r, n) -> EventListService.row(r, n).item(),
                accountId, memberId).getFirst();
    }

    private static void requireState(EventRow event, String... allowed) {
        for (String state : allowed) if (state.equals(event.state())) return;
        throw new BusinessException(ErrorCode.INVALID_STATE);
    }

    private static void requireRevision(EventRow event, long expected) {
        if (event.revision() != expected) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                    Map.of("target", "event"));
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime utc(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }

    private static EventRow eventRow(ResultSet r, int n) throws SQLException {
        return new EventRow(r.getString("state"), r.getLong("revision"), r.getString("name"), r.getString("description"),
                r.getObject("date", LocalDate.class), instant(r, "prep_start_at"), instant(r, "open_at"),
                instant(r, "close_at"), instant(r, "teardown_end_at"), r.getString("location_name"), r.getString("address"),
                r.getObject("total_capacity", Long.class), r.getObject("expected_visitors", Long.class),
                r.getObject("peak_visitors", Long.class), r.getLong("desired_reserve_count"));
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        OffsetDateTime value = r.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record EventRow(String state, long revision, String name, String description, LocalDate date,
            Instant prepStartAt, Instant openAt, Instant closeAt, Instant teardownEndAt, String locationName,
            String address, Long totalCapacity, Long expectedVisitors, Long peakVisitors, long desiredReserveCount) {

        EventCompleteness.Fields fields() {
            return new EventCompleteness.Fields(date, prepStartAt, openAt, closeAt, teardownEndAt, locationName, address,
                    totalCapacity, expectedVisitors, peakVisitors, desiredReserveCount);
        }

        /** 024에서 같은 값인지 비교할 현재 값. 키는 PATCH_COLUMNS와 같다. */
        Map<String, Object> values() {
            var values = new HashMap<String, Object>();
            values.put("name", name);
            values.put("description", description);
            values.put("date", date);
            values.put("prepStartAt", prepStartAt);
            values.put("openAt", openAt);
            values.put("closeAt", closeAt);
            values.put("teardownEndAt", teardownEndAt);
            values.put("locationName", locationName);
            values.put("address", address);
            values.put("totalCapacity", totalCapacity);
            values.put("expectedVisitors", expectedVisitors);
            values.put("peakVisitors", peakVisitors);
            values.put("desiredReserveCount", desiredReserveCount);
            return values;
        }
    }
}
