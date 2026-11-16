package com.ssafy.scenecrew.event.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.dto.EventCrewResponse.Member;
import com.ssafy.scenecrew.event.dto.EventCrewResponse.MemberPage;
import com.ssafy.scenecrew.event.dto.EventCrewResponse.WorkGroup;
import com.ssafy.scenecrew.event.dto.EventCrewResponse.WorkGroupPage;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * SC-API-036 직무 그룹 조회·SC-API-037 참여자 조회(오세진, 2026-11-05, Jira #324).
 *
 * <ul>
 *   <li>036: 현재 Member. 총괄은 비활성 그룹을 포함한 전체 그룹과 안내·활성 여부를 받는다.
 *       팀원은 활성 그룹의 이름만 받고, 안내는 자기 그룹에만 들어간다(be/07 "총괄 전체 또는 소속 안내/공통 그룹명").</li>
 *   <li>037: 총괄만. 팀원 403, Member가 아니면 404. 떠난 참여자도 active=false로 포함한다.</li>
 * </ul>
 * 권한 확인과 읽기를 같은 읽기 트랜잭션에 둔다.
 */
public class EventCrewService {
    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;
    private static final String CURSOR_VERSION = "v1";

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;

    public EventCrewService(JdbcTemplate jdbc, CurrentMembers members) { this.jdbc = jdbc; this.members = members; }

    @Transactional(readOnly = true)
    public WorkGroupPage groups(UUID eventId, UUID accountId) {
        try {
            var me = members.require(eventId, accountId);
            boolean chief = me.getEventRole() == EventRole.CHIEF;
            // 이름 순, 같은 이름은 행사 안에서 없다(UNIQUE event_id,name). ID는 안정 정렬용.
            List<WorkGroup> items = jdbc.query("""
                    SELECT id, name, instructions, active FROM work_group
                     WHERE event_id = ?""" + (chief ? "" : " AND active = true") + " ORDER BY name, id",
                    (r, n) -> {
                        UUID id = r.getObject("id", UUID.class);
                        if (chief) return new WorkGroup(id, r.getString("name"), r.getString("instructions"), r.getBoolean("active"));
                        String instructions = Objects.equals(id, me.getWorkGroupId()) ? r.getString("instructions") : null;
                        return new WorkGroup(id, r.getString("name"), instructions, null);
                    }, eventId);
            return new WorkGroupPage(items, null);
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Transactional(readOnly = true)
    public MemberPage members(UUID eventId, UUID accountId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) throw invalid();
        Long after = cursor == null ? null : decode(cursor);
        try {
            members.requireChief(eventId, accountId);
            // 참여 순번(crew_no) 순. 행사 안에서 유일해 cursor는 마지막 순번 하나로 자른다.
            List<Row> rows = jdbc.query("""
                    SELECT id, display_name, work_group_id, event_role, active, available, is_reserve,
                           participation_revision, crew_no
                      FROM member WHERE event_id = ?""" + (after == null ? "" : " AND crew_no > ?")
                    + " ORDER BY crew_no LIMIT ?",
                    (r, n) -> new Row(r.getLong("crew_no"), new Member(
                            r.getObject("id", UUID.class), r.getString("display_name"),
                            r.getObject("work_group_id", UUID.class), r.getString("event_role"),
                            r.getBoolean("active"), r.getBoolean("available"), r.getBoolean("is_reserve"),
                            Long.toString(r.getLong("participation_revision")))),
                    after == null ? new Object[] {eventId, size + 1} : new Object[] {eventId, after, size + 1});
            boolean more = rows.size() > size;
            var page = more ? rows.subList(0, size) : rows;
            String next = more ? encode(page.getLast().crewNo()) : null;
            return new MemberPage(page.stream().map(Row::member).toList(), next);
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private record Row(long crewNo, Member member) { }

    /** "v1|<참여 순번>"을 base64url로 감싼 값. 형식이 다르면 400이다. */
    static String encode(long crewNo) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((CURSOR_VERSION + "|" + crewNo).getBytes(StandardCharsets.UTF_8));
    }

    static long decode(String value) {
        if (value.isEmpty() || value.length() > 64 || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2 || !CURSOR_VERSION.equals(parts[0]) || !parts[1].matches("0|[1-9][0-9]{0,17}")) throw invalid();
            return Long.parseLong(parts[1]);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}
