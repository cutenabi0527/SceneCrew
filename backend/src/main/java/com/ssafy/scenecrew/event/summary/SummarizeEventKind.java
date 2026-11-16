package com.ssafy.scenecrew.event.summary;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;

/**
 * 행사 종료 요약(SUMMARIZE_EVENT, 요청4/결과2). AI 쪽은 ai/summary(/internal/summarize-event). S15P31A202-269.
 *
 * <p>이 종류는 공통 실행기(대기열)를 쓰지 않는다. EventSummaryService가 요청 안에서 AI 서버를 한 번 부르고 요약문을 바로 돌려준다.
 * ai_job 행은 "언제 누가 시도했고 끝났는지"를 남기는 시도 기록이며 result는 항상 null이다(POLICIES P07, ai/07).
 * 빈으로 등록하는 이유는 공통 복구(AiJobRecovery)·종료 후속이 이 종류를 찾을 수 있게 하고, 외부 AI 동의 검사(AiJobGate)를 함께 쓰기 위해서다.
 *
 * <p>입력(input)은 종료된 행사의 사실만 담는다. 이름·계정·팀원 id·채팅·음성·보고 원문은 넣지 않는다.
 */
@Component
public class SummarizeEventKind implements AiJobKind {
    public static final String KIND = "SUMMARIZE_EVENT";
    public static final String PATH = "/internal/summarize-event";
    static final int MAX_WORK_GROUPS = 31;
    static final int MAX_PROGRAMS = 20;
    static final int MAX_ISSUES = 100;
    static final int MAX_CHANGES = 50;
    static final int MAX_TEXT = 1000;

    private final JdbcTemplate jdbc;

    public SummarizeEventKind(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String kind() { return KIND; }
    @Override public String path() { return PATH; }
    @Override public String resource() { return "GMS"; }
    @Override public boolean external() { return true; }
    @Override public boolean requiresCurrentContext() { return false; }

    /** 공통 실행기가 부를 일이 없다. 혹시 불려도 요약문을 저장하지 않도록 계약 위반으로 끝낸다. */
    @Override
    public AiJobOutcome read(AiJobEnvelope request, AiResponse response) {
        return AiJobOutcome.failed(AiJobError.invalidResult("종료 요약은 공통 실행기를 쓰지 않습니다."));
    }

    /** 요청4 payload. 종료된 행사의 사실만. */
    public Map<String, Object> input(UUID eventId) {
        Map<String, Object> event = jdbc.queryForObject("""
                SELECT name, date, open_at, close_at, ended_at, location_name, total_capacity, expected_visitors
                  FROM event WHERE id = ?""", (r, n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", r.getString(1));
            Object date = r.getObject(2);
            e.put("date", date == null ? null : date.toString());
            e.put("openAt", time(r.getObject(3, OffsetDateTime.class)));
            e.put("closeAt", time(r.getObject(4, OffsetDateTime.class)));
            e.put("endedAt", time(r.getObject(5, OffsetDateTime.class)));
            e.put("locationName", cut(r.getString(6)));
            e.put("totalCapacity", r.getObject(7, Long.class));
            e.put("expectedVisitors", r.getObject(8, Long.class));
            return e;
        }, eventId);

        List<Map<String, Object>> groups = jdbc.query("""
                SELECT g.name, count(t.id),
                       count(t.id) FILTER (WHERE t.status = 'DONE'), count(t.id) FILTER (WHERE t.status = 'IN_PROGRESS'),
                       count(t.id) FILTER (WHERE t.status = 'WAITING'), count(t.id) FILTER (WHERE t.status = 'CANCELED')
                  FROM work_group g LEFT JOIN task t ON t.work_group_id = g.id
                 WHERE g.event_id = ? GROUP BY g.id, g.name ORDER BY g.name, g.id LIMIT ?""", (r, n) -> {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("total", r.getLong(2));
            counts.put("done", r.getLong(3));
            counts.put("inProgress", r.getLong(4));
            counts.put("waiting", r.getLong(5));
            counts.put("canceled", r.getLong(6));
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", r.getString(1));
            g.put("tasks", counts);
            return g;
        }, eventId, MAX_WORK_GROUPS);

        List<Map<String, Object>> programs = jdbc.query("""
                SELECT p.name, z.name, p.start_at, p.end_at FROM program p LEFT JOIN zone z ON z.id = p.zone_id
                 WHERE p.event_id = ? ORDER BY p.start_at, p.id LIMIT ?""", (r, n) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", r.getString(1));
            p.put("zone", r.getString(2));
            p.put("startAt", time(r.getObject(3, OffsetDateTime.class)));
            p.put("endAt", time(r.getObject(4, OffsetDateTime.class)));
            return p;
        }, eventId, MAX_PROGRAMS);

        // 이슈 제목·상태·구역·확인된 상황 유형·보고 수. 보고 원문·작성자는 넣지 않는다.
        List<Map<String, Object>> issues = jdbc.query("""
                SELECT i.title, i.status, z.name,
                       (SELECT s.primary_type FROM operational_situation s
                         WHERE s.issue_id = i.id AND s.confirmed_at IS NOT NULL ORDER BY s.confirmed_at DESC LIMIT 1),
                       (SELECT count(*) FROM report r WHERE r.issue_id = i.id), i.created_at, i.closed_at
                  FROM issue i LEFT JOIN zone z ON z.id = i.zone_id
                 WHERE i.event_id = ? ORDER BY i.created_at, i.id LIMIT ?""", (r, n) -> {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("title", cut(r.getString(1)));
            i.put("status", r.getString(2));
            i.put("zone", r.getString(3));
            i.put("situation", r.getString(4));
            i.put("reports", r.getLong(5));
            i.put("createdAt", time(r.getObject(6, OffsetDateTime.class)));
            i.put("closedAt", time(r.getObject(7, OffsetDateTime.class)));
            return i;
        }, eventId, MAX_ISSUES);

        List<Map<String, Object>> changes = jdbc.query("""
                SELECT mode, rationale, jsonb_array_length(operations), applied_at FROM change_set
                 WHERE event_id = ? AND status = 'APPLIED' AND applied_at IS NOT NULL ORDER BY applied_at, id LIMIT ?""",
                (r, n) -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("mode", r.getString(1));
                    c.put("rationale", cut(r.getString(2)));
                    c.put("operations", r.getLong(3));
                    c.put("appliedAt", time(r.getObject(4, OffsetDateTime.class)));
                    return c;
                }, eventId, MAX_CHANGES);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("workGroups", groups);
        payload.put("programs", programs);
        payload.put("issues", issues);
        payload.put("changes", new ArrayList<>(changes));
        return payload;
    }

    private static String time(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    /** 1,000 코드포인트로 자른다(AI 계약 Text 상한) */
    static String cut(String value) {
        if (value == null || value.codePointCount(0, value.length()) <= MAX_TEXT) return value;
        return value.substring(0, value.offsetByCodePoints(0, MAX_TEXT));
    }
}
