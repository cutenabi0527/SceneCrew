package com.ssafy.scenecrew.event.service;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ssafy.scenecrew.event.dto.EventListResponse;
import com.ssafy.scenecrew.event.dto.EventListResponse.EventItem;
import com.ssafy.scenecrew.event.dto.EventListResponse.Participation;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * SC-API-021 참여 행사 목록(오세진, 2026-11-05). 로그인한 계정이 지금 활성 Member인 행사만 돌려준다.
 * Member가 없으면 빈 목록이다. 탈퇴(active=false)한 행사는 목록에 넣지 않는다.
 *
 * <p>순서는 행사일 최신순(날짜 없는 행사는 맨 뒤), 같은 날은 행사 ID 순이다. 계약에서 순서가 OPEN이라 이 서비스가 정했다.
 * cursor는 마지막 항목의 (행사일, ID)를 담은 불투명 문자열이고, 그 다음 항목부터 이어서 준다.
 * 정렬 키가 유지되는 항목을 이 키로 이어서 읽는다. 날짜·참여가 바뀌면 스냅샷이 아니므로 중복·누락이 생길 수 있다.
 */
public final class EventListService {
    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;
    private static final String CURSOR_VERSION = "v1";
    /** 행사 조회(SC-API-023)도 같은 열·같은 변환을 쓴다. */
    static final String SELECT = """
            SELECT e.id, e.name, e.state, e.revision, e.date, e.prep_start_at, e.open_at, e.close_at, e.teardown_end_at,
                   e.location_name, e.address, e.total_capacity, e.expected_visitors, e.peak_visitors,
                   e.desired_reserve_count, e.description, e.instructions,
                   m.id AS member_id, m.participation_revision, m.active, m.event_role, m.work_group_id
              FROM member m JOIN event e ON e.id = m.event_id
             WHERE m.account_id = ? AND m.active = true
            """;
    private static final String ORDER = " ORDER BY e.date DESC NULLS LAST, e.id ASC LIMIT ?";

    private final JdbcTemplate jdbc;

    public EventListService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public EventListResponse list(UUID accountId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) throw invalid();
        var after = cursor == null ? null : Cursor.decode(cursor);
        List<Object> args = new ArrayList<>(List.of(accountId));
        String where = "";
        if (after != null && after.date() != null) {
            where = " AND (e.date < ? OR (e.date = ? AND e.id > ?) OR e.date IS NULL)";
            args.add(after.date()); args.add(after.date()); args.add(after.id());
        } else if (after != null) {
            where = " AND e.date IS NULL AND e.id > ?";
            args.add(after.id());
        }
        args.add(size + 1);
        List<Row> rows;
        try {
            rows = jdbc.query(SELECT + where + ORDER, EventListService::row, args.toArray());
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        String next = more ? new Cursor(page.getLast().date(), page.getLast().item().id()).encode() : null;
        return new EventListResponse(page.stream().map(Row::item).toList(), next);
    }

    static Row row(ResultSet r, int n) throws SQLException {
        var date = r.getObject("date", LocalDate.class);
        var item = new EventItem(
                r.getObject("id", UUID.class), r.getString("name"), r.getString("state"), decimal(r, "revision"),
                date == null ? null : date.toString(),
                instant(r, "prep_start_at"), instant(r, "open_at"), instant(r, "close_at"), instant(r, "teardown_end_at"),
                r.getString("location_name"), r.getString("address"),
                decimal(r, "total_capacity"), decimal(r, "expected_visitors"), decimal(r, "peak_visitors"),
                decimal(r, "desired_reserve_count"),
                new Participation(r.getObject("member_id", UUID.class), decimal(r, "participation_revision"),
                        r.getBoolean("active"), r.getString("event_role"), r.getObject("work_group_id", UUID.class)),
                r.getString("description"), r.getString("instructions"));
        return new Row(date, item);
    }
    /** 계약 DecimalBigInt: 숫자가 아니라 10진 문자열. null 컬럼은 null 그대로. */
    private static String decimal(ResultSet r, String column) throws SQLException {
        long value = r.getLong(column);
        return r.wasNull() ? null : Long.toString(value);
    }
    private static String instant(ResultSet r, String column) throws SQLException {
        Timestamp value = r.getTimestamp(column);
        return value == null ? null : value.toInstant().toString();
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }

    record Row(LocalDate date, EventItem item) { }

    /** "v1|<행사일 또는 ->|<행사 ID>"를 base64url로 감싼 값. 형식이 다르면 400이다. */
    record Cursor(LocalDate date, UUID id) {
        String encode() {
            String raw = CURSOR_VERSION + "|" + (date == null ? "-" : date) + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        static Cursor decode(String value) {
            if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3 || !CURSOR_VERSION.equals(parts[0])) throw invalid();
                LocalDate date = "-".equals(parts[1]) ? null : LocalDate.parse(parts[1]);
                UUID id = UUID.fromString(parts[2]);
                if (!id.toString().equals(parts[2])) throw invalid();
                return new Cursor(date, id);
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                throw invalid();
            }
        }
    }
}
