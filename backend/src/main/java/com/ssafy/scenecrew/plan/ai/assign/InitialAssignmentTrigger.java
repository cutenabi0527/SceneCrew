package com.ssafy.scenecrew.plan.ai.assign;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.pipeline.AiJobService;
import com.ssafy.scenecrew.global.outbox.OutboxHandler;
import com.ssafy.scenecrew.global.outbox.OutboxPublisher;
import com.ssafy.scenecrew.global.outbox.OutboxRelayProperties;
import com.ssafy.scenecrew.global.outbox.domain.OutboxEvent;

/**
 * AI 최초 배정의 시작(#267, V9-D46·V9-D47). 참여(035)와 가용 변경(037)이 같은 트랜잭션에서 트리거를 outbox로 남기고,
 * 처리기가 그때의 현재 상태로 AI 작업을 접수한다. 참여·가용 변경은 접수 성공 여부와 상관없이 그대로 성공한다(be/04).
 *
 * <p>같은 행사에 진행 중인 최초 배정이 있으면(이 종류의 진행 중 한도는 행사 단위다) 실패로 돌리지 않고, 같은 그룹의 트리거를
 * {@link #RECHECK} 뒤에 다시 꺼내도록 하나 남기고 끝낸다. outbox 시도 횟수를 쓰지 않으므로 AI가 오래 걸려도 트리거가 버려지지 않는다.
 * 같은 그룹에 이미 기다리는 트리거가 있으면 새로 남기지 않아, 진행 중에 겹친 트리거는 그룹마다 하나로 합쳐진다.
 * 앞 작업이 끝난 뒤 꺼낸 트리거는 그때의 미배정 묶음으로 접수한다. 미배정 묶음이나 배정 가능한 팀원이 없으면 아무것도
 * 접수하지 않는다(트리거만 끝난다). 후보 없음·AI 실패는 미배정으로 남고 총괄이 수동 배정(045)으로 대신한다.
 *
 * <p>AI 요청 계약(ai/assign/contract.py)이 요청 전체를 INVALID_REQUEST로 거절하는 묶음(업무 50개 초과, 제목·bundleKey가 비었거나
 * 100자 초과, 시각 순서 오류, 묶음 안 시간 겹침)과 팀원(busy 200개 초과, 시각 순서 오류)은 보내지 않고 경고 로그만 남긴다.
 * 한 묶음 때문에 그룹 전체가 매번 실패하지 않게 하려는 것이며, 빠진 묶음은 수동 배정 대상으로 남는다.
 */
@Component
public class InitialAssignmentTrigger implements OutboxHandler {
    public static final String TYPE = "ASSIGN_INITIAL_TRIGGER";
    /** AI 계약 상한(ai/assign/contract.py): 묶음 1~31개, 팀원 0~31명, 묶음당 업무 1~50개, 팀원당 busy 200개, 제목·bundleKey 100자 */
    static final int MAX_BUNDLES = 31;
    static final int MAX_MEMBERS = 31;
    static final int MAX_TASKS_PER_BUNDLE = 50;
    static final int MAX_BUSY = 200;
    static final int MAX_TEXT = 100;
    /** 진행 중인 최초 배정이 있을 때 트리거를 다시 꺼내기까지 기다리는 시간 */
    static final Duration RECHECK = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(InitialAssignmentTrigger.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;
    private final AiJobService aiJobs;
    private final OutboxRelayProperties relay;

    public InitialAssignmentTrigger(JdbcTemplate jdbc, OutboxPublisher outbox, AiJobService aiJobs,
            OutboxRelayProperties relay) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.aiJobs = aiJobs;
        this.relay = relay;
    }

    /** 참여·가용 변경과 같은 트랜잭션에서 부른다. 그룹이 없으면 남기지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID eventId, UUID workGroupId) {
        if (workGroupId == null) return;
        outbox.publish(eventId, TYPE, workGroupId, Map.of("workGroupId", workGroupId));
    }

    @Override public String type() { return TYPE; }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        UUID eventId = event.getEventId();
        UUID group = UUID.fromString(MAPPER.readTree(event.getPayload()).get("workGroupId").asString());
        // 참여·배정 반영과 같은 순서로 Event를 먼저 잠가, 읽은 묶음·팀원과 접수한 revision이 어긋나지 않게 한다.
        // 같은 행사의 트리거 처리도 이 잠금으로 줄을 선다.
        var rows = jdbc.query("SELECT state, revision FROM event WHERE id = ? FOR UPDATE",
                (r, n) -> new Object[] {r.getString(1), r.getLong(2)}, eventId);
        if (rows.isEmpty()) return;
        String state = (String) rows.getFirst()[0];
        long revision = (Long) rows.getFirst()[1];
        if (!"RECRUITING".equals(state) && !"LIVE".equals(state)) return;
        Instant now = Instant.now();
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ai_job WHERE event_id = ? AND kind = ? AND state IN ('QUEUED','RUNNING')
                                AND deadline_at > ?)""", Boolean.class, eventId, AssignInitialKind.KIND,
                Timestamp.from(now)))) {
            deferWhileBusy(event, eventId, group, now);
            return;
        }
        if (jdbc.queryForList("SELECT id FROM work_group WHERE id = ? AND event_id = ? AND active", UUID.class, group, eventId)
                .isEmpty())
            return;

        List<Map<String, Object>> bundles = bundles(eventId, group);
        if (bundles.isEmpty()) return;
        List<Map<String, Object>> members = members(eventId, group);
        if (members.isEmpty()) return;
        // 접수 때의 동의다. 호출 직전·반영 직전에는 AssignInitialKind.external(job)을 통해 공통 관문이 다시 확인한다.
        Boolean consent = jdbc.query("""
                SELECT (a.consent -> 'externalAi') = 'true'::jsonb FROM member m JOIN account a ON a.id = m.account_id
                 WHERE m.event_id = ? AND m.event_role = 'CHIEF' AND m.active""",
                rs -> rs.next() ? rs.getBoolean(1) : null, eventId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workGroupId", group.toString());
        payload.put("bundles", bundles);
        payload.put("members", members);
        payload.put("allowExternalAi", Boolean.TRUE.equals(consent));
        aiJobs.accept(eventId, null, AssignInitialKind.KIND, revision, payload);
    }

    /**
     * 진행 중인 최초 배정이 끝난 뒤 다시 보도록 이 그룹의 트리거를 하나 남긴다. 같은 그룹에 아직 꺼낼 트리거(처리 전·시도 한도 안·
     * 지금 처리 중이 아님)가 있으면 그것이 이어받으므로 남기지 않는다. Event 잠금 아래에서만 불린다.
     */
    private void deferWhileBusy(OutboxEvent event, UUID eventId, UUID group, Instant now) {
        boolean waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM outbox_event WHERE type = ? AND aggregate_id = ? AND id <> ?
                                  AND processed_at IS NULL AND attempts < ? AND (lease_until IS NULL OR lease_until < ?))""",
                Boolean.class, TYPE, group, event.getId(), relay.maxAttempts(), Timestamp.from(now)));
        if (!waiting) outbox.publish(eventId, TYPE, group, Map.of("workGroupId", group), now.plus(RECHECK));
    }

    /**
     * 그 그룹의 미배정 묶음. 묶음의 모든 업무가 같은 그룹·대기·담당 없음·최초 배정 기록 없음·수행 시각 없음이어야 한다(P03).
     * AI 요청 계약에 맞지 않는 묶음은 빼고, 이른 묶음부터 31개까지 보낸다. 업무 제목·시각·구역만 보내고 다른 내용은 보내지 않는다.
     */
    private List<Map<String, Object>> bundles(UUID eventId, UUID group) {
        List<String> keys = jdbc.queryForList("""
                SELECT bundle_key FROM task WHERE event_id = ?
                 GROUP BY bundle_key
                HAVING bool_and(work_group_id = ?) AND bool_and(status = 'WAITING') AND bool_and(assignee_id IS NULL)
                   AND bool_and(first_assigned_at IS NULL) AND bool_and(actual_started_at IS NULL)
                   AND bool_and(actual_ended_at IS NULL)
                 ORDER BY min(start_at), bundle_key""", String.class, eventId, group);
        List<Map<String, Object>> bundles = new ArrayList<>();
        for (String key : keys) {
            if (bundles.size() == MAX_BUNDLES) break;
            List<Task> tasks = jdbc.query("""
                    SELECT id, title, start_at, end_at, zone_id FROM task WHERE event_id = ? AND bundle_key = ?
                     ORDER BY start_at, id""", (r, n) -> new Task(r.getObject(1, UUID.class), r.getString(2),
                    r.getTimestamp(3).toInstant(), r.getTimestamp(4).toInstant(), r.getObject(5, UUID.class)), eventId, key);
            String rejected = contractViolation(key, tasks);
            if (rejected != null) {
                // 업무 제목은 로그에 남기지 않는다.
                log.warn("AI 요청 계약에 맞지 않는 묶음을 최초 배정 요청에서 뺌(수동 배정 대상): eventId={} workGroupId={} bundleKey={} reason={}",
                        eventId, group, key, rejected);
                continue;
            }
            List<Map<String, Object>> sent = new ArrayList<>();
            for (Task task : tasks) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("taskId", task.id().toString());
                t.put("title", task.title());
                t.put("startAt", task.startAt().toString());
                t.put("endAt", task.endAt().toString());
                t.put("zoneId", task.zoneId().toString());
                sent.add(t);
            }
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("bundleKey", key);
            bundle.put("tasks", sent);
            bundles.add(bundle);
        }
        return bundles;
    }

    record Task(UUID id, String title, Instant startAt, Instant endAt, UUID zoneId) { }

    /** ai/assign/contract.py가 요청 전체를 INVALID_REQUEST로 거절할 묶음이면 사유, 아니면 null. tasks는 시작 시각 순이다. */
    static String contractViolation(String bundleKey, List<Task> tasks) {
        if (!fitsText(bundleKey)) return "BUNDLE_KEY";
        if (tasks.isEmpty() || tasks.size() > MAX_TASKS_PER_BUNDLE) return "TASK_COUNT";
        Instant latestEnd = null;
        for (Task task : tasks) {
            if (!fitsText(task.title())) return "TITLE";
            if (!task.startAt().isBefore(task.endAt())) return "TIME_ORDER";
            // 시작 순이므로 앞 업무들의 가장 늦은 끝과만 비교한다. 끝과 시작이 같은 시각은 겹치지 않는다.
            if (latestEnd != null && latestEnd.isAfter(task.startAt())) return "TIME_OVERLAP";
            if (latestEnd == null || task.endAt().isAfter(latestEnd)) latestEnd = task.endAt();
        }
        return null;
    }

    /** 공백만이 아닌 1~100자(코드 포인트, 파이썬 len과 같은 기준) */
    private static boolean fitsText(String value) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= MAX_TEXT;
    }

    /**
     * 같은 그룹의 활성·가용·비예비 팀원과 이미 맡은 WAITING·IN_PROGRESS 업무 시간(서버 재검사 AssignmentChecks와 같은 기준).
     * 이름 같은 개인정보는 보내지 않는다.
     */
    private List<Map<String, Object>> members(UUID eventId, UUID group) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM member WHERE event_id = ? AND work_group_id = ? AND event_role = 'WORKER'
                   AND active AND available AND NOT is_reserve ORDER BY crew_no, id LIMIT ?""",
                UUID.class, eventId, group, MAX_MEMBERS);
        List<Map<String, Object>> members = new ArrayList<>();
        for (UUID id : ids) {
            List<Instant[]> intervals = jdbc.query("""
                    SELECT start_at, end_at FROM task WHERE event_id = ? AND assignee_id = ?
                       AND status IN ('WAITING','IN_PROGRESS') ORDER BY start_at, id""",
                    (r, n) -> new Instant[] {r.getTimestamp(1).toInstant(), r.getTimestamp(2).toInstant()}, eventId, id);
            if (intervals.size() > MAX_BUSY || intervals.stream().anyMatch(b -> !b[0].isBefore(b[1]))) {
                log.warn("AI 요청 계약에 맞지 않는 팀원을 최초 배정 요청에서 뺌: eventId={} workGroupId={} memberId={} busy={}",
                        eventId, group, id, intervals.size());
                continue;
            }
            List<Map<String, Object>> busy = new ArrayList<>();
            for (Instant[] b : intervals) {
                Map<String, Object> interval = new LinkedHashMap<>();
                interval.put("startAt", b[0].toString());
                interval.put("endAt", b[1].toString());
                busy.add(interval);
            }
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("memberId", id.toString());
            member.put("busy", busy);
            members.add(member);
        }
        return members;
    }
}
