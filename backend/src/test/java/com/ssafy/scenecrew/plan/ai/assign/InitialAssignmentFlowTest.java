package com.ssafy.scenecrew.plan.ai.assign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.aijob.client.AiClient;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.global.outbox.OutboxRelay;
import com.ssafy.scenecrew.support.FileTestBeans;
import com.ssafy.scenecrew.support.TestRows;

/**
 * AI 최초 배정(#267, V9-D47): 트리거 → 입력(그룹 하나의 미배정 묶음·배정 가능 팀원·외부 AI 동의) → AI 응답 검사 → 묶음마다 재검사해
 * 통과한 묶음만 처음 배정·알림. 실제 AI 대신 FakeAssign이 ai/assign/contract.py와 같은 모양으로 답한다.
 */
@SpringBootTest(properties = "scenecrew.ai.server.service-token=test-service-token")
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import({FileTestBeans.class, InitialAssignmentFlowTest.TestBeans.class})
class InitialAssignmentFlowTest {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRelay relay;
    @Autowired InitialAssignmentTrigger trigger;
    @Autowired TransactionTemplate tx;
    @Autowired FakeAssign ai;

    UUID event, group, otherGroup, zone, chief, worker, worker2, reserve, away, outsider;
    UUID chiefAccount;
    /** 이 시험이 만든 행사. 끝나면 남은 트리거·실행 요청을 지우고 진행 중 작업을 끝내, 공유 DB의 다른 시험이 relayOnce로 꺼내지 않게 한다. */
    final List<UUID> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ai.reset();
        TestRows rows = new TestRows(jdbc);
        event = rows.event("LIVE");
        events.add(event);
        group = group("음향");
        otherGroup = group("조명");
        zone = UUID.randomUUID();
        jdbc.update("insert into zone (id, event_id, name, description, aliases) values (?, ?, 'A구역', '', '[]'::jsonb)", zone, event);
        chiefAccount = rows.account();
        chief = rows.member(event, chiefAccount, "CHIEF", true);
        worker = member(rows, group, true, false);
        worker2 = member(rows, group, true, false);
        reserve = member(rows, group, true, true);
        away = member(rows, group, false, false);
        outsider = member(rows, otherGroup, true, false);
    }

    @AfterEach
    void tearDown() {
        ai.reset();
        for (UUID e : events) {
            jdbc.update("delete from outbox_event where event_id = ?", e);
            jdbc.update("update ai_job set state = 'FAILED' where event_id = ? and state in ('QUEUED','RUNNING')", e);
        }
        events.clear();
    }

    @Test
    void triggerSendsOnlyEligibleBundlesAndMembersThenAssignsPassingBundles() {
        task("sound-a", group, null, "2026-11-24T09:00:00Z", "2026-11-24T09:30:00Z", "WAITING");
        task("sound-a", group, null, "2026-11-24T09:30:00Z", "2026-11-24T10:00:00Z", "WAITING");
        task("sound-b", group, null, "2026-11-24T10:00:00Z", "2026-11-24T10:30:00Z", "WAITING");
        // 보내지 않는 묶음: 이미 배정된 묶음, 다른 그룹 묶음, 취소된 업무가 섞인 묶음
        task("taken", group, worker2, "2026-11-24T08:00:00Z", "2026-11-24T08:30:00Z", "WAITING");
        task("light", otherGroup, null, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", "WAITING");
        task("mixed", group, null, "2026-11-24T11:00:00Z", "2026-11-24T11:30:00Z", "WAITING");
        task("mixed", group, null, "2026-11-24T11:30:00Z", "2026-11-24T12:00:00Z", "CANCELED");
        ai.answer(request -> success(request, List.of(assign("sound-a", worker), assign("sound-b", worker2)), List.of()));

        long before = revision();
        runTrigger();
        JsonNode sent = ai.calls.getFirst();
        assertThat(sent.get("kind").asString()).isEqualTo("ASSIGN_INITIAL");
        JsonNode payload = sent.get("payload");
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("workGroupId", "bundles", "members", "allowExternalAi");
        assertThat(payload.get("workGroupId").asString()).isEqualTo(group.toString());
        assertThat(keys(payload.get("bundles"), "bundleKey")).containsExactly("sound-a", "sound-b");
        assertThat(payload.get("bundles").get(0).get("tasks").get(0).propertyNames())
                .containsExactlyInAnyOrder("taskId", "title", "startAt", "endAt", "zoneId");
        assertThat(keys(payload.get("members"), "memberId")).containsExactlyInAnyOrder(worker.toString(), worker2.toString());
        JsonNode busy = member(payload, worker2).get("busy");
        assertThat(busy.size()).isEqualTo(1);
        assertThat(busy.get(0).get("startAt").asString()).isEqualTo("2026-11-24T08:00:00Z");
        assertThat(payload.get("allowExternalAi").asBoolean()).isFalse();

        UUID job = onlyJob();
        awaitState(job, "SUCCEEDED");
        assertThat(count("select count(*) from task where event_id=? and bundle_key='sound-a' and assignee_id=? and first_assigned_at is not null", event, worker)).isEqualTo(2);
        assertThat(count("select count(*) from task where event_id=? and bundle_key='sound-b' and assignee_id=?", event, worker2)).isEqualTo(1);
        // 하나의 결과로 한 번만 올린다
        assertThat(revision()).isEqualTo(before + 1);
        assertThat(count("select count(*) from notification where event_id=? and type='INITIAL_ASSIGNMENT'", event)).isEqualTo(2);
        assertThat(count("select count(*) from change_acknowledgment where event_id=?", event)).isZero();
        assertThat(count("select count(*) from operation_log where event_id=? and type='INITIAL_ASSIGNMENT' and actor_member_id is null and changes->>'source'='AI'", event)).isEqualTo(2);
        JsonNode stored = MAPPER.readTree(jdbc.queryForObject("select result::text from ai_job where id=?", String.class, job));
        assertThat(stored.get("generator").asString()).isEqualTo("RULE");
        assertThat(stored.get("assigned").size()).isEqualTo(2);
        assertThat(stored.toString()).doesNotContain("입장").doesNotContain("시험 인원");
    }

    @Test
    void consentIsPassedAndRejectedBundlesStayUnassignedWithReason() {
        jdbc.update("update account set consent='{\"externalAi\":true}'::jsonb where id=?", chiefAccount);
        task("first", group, null, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", "WAITING");
        task("second", group, null, "2026-11-24T10:00:00Z", "2026-11-24T11:00:00Z", "WAITING");
        task("third", group, null, "2026-11-24T12:00:00Z", "2026-11-24T13:00:00Z", "WAITING");
        ai.answer(request -> {
            // AI가 답하는 사이 worker가 'second'와 겹치는 업무를 다른 경로로 맡았다(재검사로 걸러야 한다).
            task("late", group, worker, "2026-11-24T10:15:00Z", "2026-11-24T10:45:00Z", "WAITING");
            return success(request, List.of(assign("first", worker), assign("second", worker)),
                    List.of(Map.of("bundleKey", "third", "code", "NO_AVAILABLE_MEMBER")));
        });
        runTrigger();
        assertThat(ai.calls.getFirst().get("payload").get("allowExternalAi").asBoolean()).isTrue();
        UUID job = onlyJob();
        awaitState(job, "SUCCEEDED");
        assertThat(count("select count(*) from task where event_id=? and bundle_key='first' and assignee_id=?", event, worker)).isEqualTo(1);
        assertThat(count("select count(*) from task where event_id=? and bundle_key in ('second','third') and assignee_id is not null", event)).isZero();
        JsonNode stored = MAPPER.readTree(jdbc.queryForObject("select result::text from ai_job where id=?", String.class, job));
        assertThat(stored.get("unassigned").toString()).contains("\"third\"").contains("NO_AVAILABLE_MEMBER")
                .contains("\"second\"").contains("TIME_OVERLAP");
        assertThat(count("select count(*) from notification where event_id=?", event)).isEqualTo(1);
    }

    @Test
    void resultOutsideTheRequestIsRejectedWithoutAssigning() {
        task("only", group, null, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", "WAITING");
        long before = revision();
        ai.answer(request -> success(request, List.of(assign("only", outsider)), List.of()));
        runTrigger();
        UUID job = onlyJob();
        awaitState(job, "FAILED");
        assertThat(jdbc.queryForObject("select error::text from ai_job where id=?", String.class, job)).contains("INVALID_RESULT");
        assertThat(count("select count(*) from task where event_id=? and assignee_id is not null", event)).isZero();
        assertThat(revision()).isEqualTo(before);
    }

    @Test
    void activeJobDefersTriggerWithoutSpendingAttemptsAndMergesOverlaps() {
        task("only", group, null, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", "WAITING");
        tx.executeWithoutResult(s -> trigger.publish(event, group));
        relay.relayOnce(); // 작업만 접수한다
        UUID first = onlyJob();
        // 이 시험에서는 앞 작업을 실행하지 않고 진행 중(QUEUED)으로 둔다
        jdbc.update("delete from outbox_event where event_id=? and type='AI_JOB_REQUESTED'", event);

        tx.executeWithoutResult(s -> trigger.publish(event, group));
        tx.executeWithoutResult(s -> trigger.publish(event, group));
        relay.relayOnce();
        // 두 트리거 모두 실패가 아니라 처리 완료이고, 시도 횟수를 쓰지 않은 트리거 하나만 뒤로 미뤄 남는다(겹친 트리거는 하나로)
        assertThat(count("select count(*) from ai_job where event_id=? and kind='ASSIGN_INITIAL'", event)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_event where event_id=? and type=? and attempts>0", event,
                InitialAssignmentTrigger.TYPE)).isZero();
        assertThat(count("select count(*) from outbox_event where event_id=? and type=? and processed_at is null", event,
                InitialAssignmentTrigger.TYPE)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_event where event_id=? and type=? and processed_at is null and available_at > now()",
                event, InitialAssignmentTrigger.TYPE)).isEqualTo(1);

        // 오래 걸려도 다시 꺼낼 때마다 시도 횟수 없이 미뤄진다(outbox 최대 5회를 넘겨도 버려지지 않는다)
        for (int i = 0; i < 6; i++) {
            readyTriggers();
            relay.relayOnce();
        }
        assertThat(count("select count(*) from outbox_event where event_id=? and type=? and processed_at is null and attempts=0", event,
                InitialAssignmentTrigger.TYPE)).isEqualTo(1);
        assertThat(count("select count(*) from ai_job where event_id=? and kind='ASSIGN_INITIAL'", event)).isEqualTo(1);

        // 앞 작업이 끝나면 남은 트리거가 그때의 미배정 묶음으로 접수한다
        jdbc.update("update ai_job set state='FAILED' where id=?", first);
        readyTriggers();
        relay.relayOnce();
        assertThat(count("select count(*) from ai_job where event_id=? and kind='ASSIGN_INITIAL'", event)).isEqualTo(2);
        assertThat(count("select count(*) from outbox_event where event_id=? and type=? and processed_at is null", event,
                InitialAssignmentTrigger.TYPE)).isZero();
    }

    @Test
    void nothingToAssignMeansNoJob() {
        UUID empty = new TestRows(jdbc).event("LIVE");
        events.add(empty);
        UUID emptyGroup = UUID.randomUUID();
        jdbc.update("insert into work_group (id, event_id, name, instructions, active) values (?, ?, '빈 그룹', '', true)", emptyGroup, empty);
        tx.executeWithoutResult(s -> trigger.publish(empty, emptyGroup));
        relay.relayOnce();
        assertThat(count("select count(*) from ai_job where event_id=?", empty)).isZero();
    }

    @Test
    void bundlesTheAiContractRejectsAreLeftOutInsteadOfFailingTheGroup() {
        String hundred = "가".repeat(100);
        task("ok", group, null, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", "WAITING", hundred);
        // 묶음 안 시간 겹침
        task("overlap", group, null, "2026-11-24T11:00:00Z", "2026-11-24T12:00:00Z", "WAITING");
        task("overlap", group, null, "2026-11-24T11:30:00Z", "2026-11-24T12:30:00Z", "WAITING");
        // 제목 101자
        task("long-title", group, null, "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z", "WAITING", hundred + "가");
        // bundleKey 101자
        task("k".repeat(101), group, null, "2026-11-24T14:00:00Z", "2026-11-24T15:00:00Z", "WAITING");
        // 업무 51개
        for (int i = 0; i < 51; i++)
            task("many", group, null, "2026-11-25T00:%02d:00Z".formatted(i), "2026-11-25T00:%02d:30Z".formatted(i), "WAITING");
        ai.answer(request -> success(request, List.of(assign("ok", worker)), List.of()));

        runTrigger();
        assertThat(keys(ai.calls.getFirst().get("payload").get("bundles"), "bundleKey")).containsExactly("ok");
        awaitState(onlyJob(), "SUCCEEDED");
        assertThat(count("select count(*) from task where event_id=? and bundle_key='ok' and assignee_id=?", event, worker)).isEqualTo(1);
        assertThat(count("select count(*) from task where event_id=? and bundle_key<>'ok' and assignee_id is not null", event)).isZero();
    }

    @Test
    void withdrawnConsentStopsTheExternalCallAndRuleOnlyRequestRejectsGms() {
        // 외부 AI 허용으로 접수한 뒤 총괄이 동의를 철회하면 AI를 부르지 않는다
        consent(true);
        task("only", group, null, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", "WAITING");
        ai.answer(request -> success(request, List.of(assign("only", worker)), List.of(), "GMS"));
        tx.executeWithoutResult(s -> trigger.publish(event, group));
        relay.relayOnce(); // 접수
        UUID first = onlyJob();
        assertThat(jdbc.queryForObject("select input->>'allowExternalAi' from ai_job where id=?", String.class, first)).isEqualTo("true");
        consent(false);
        relay.relayOnce(); // 실행
        awaitState(first, "FAILED");
        assertThat(ai.calls).isEmpty();
        assertThat(jdbc.queryForObject("select error::text from ai_job where id=?", String.class, first)).contains("CONSENT_WITHDRAWN");

        // AI가 답하는 사이 철회해도 반영하지 않는다
        consent(true);
        ai.answer(request -> {
            consent(false);
            return success(request, List.of(assign("only", worker)), List.of(), "GMS");
        });
        runTrigger();
        UUID second = latestJob();
        awaitState(second, "FAILED");
        assertThat(jdbc.queryForObject("select error::text from ai_job where id=?", String.class, second)).contains("CONSENT_WITHDRAWN");
        assertThat(count("select count(*) from task where event_id=? and assignee_id is not null", event)).isZero();

        // 외부 AI를 허용하지 않은 요청에 GMS로 답하면 계약 위반이다
        ai.reset();
        ai.answer(request -> success(request, List.of(assign("only", worker)), List.of(), "GMS"));
        runTrigger();
        assertThat(ai.calls.getFirst().get("payload").get("allowExternalAi").asBoolean()).isFalse();
        UUID third = latestJob();
        awaitState(third, "FAILED");
        assertThat(jdbc.queryForObject("select error::text from ai_job where id=?", String.class, third)).contains("INVALID_RESULT");
        assertThat(count("select count(*) from task where event_id=? and assignee_id is not null", event)).isZero();
    }

    @Test
    void recipientWhoseAccountIsNotActiveIsRejectedPerBundle() {
        task("sound-a", group, null, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", "WAITING");
        task("sound-b", group, null, "2026-11-24T10:00:00Z", "2026-11-24T11:00:00Z", "WAITING");
        UUID workerAccount = jdbc.queryForObject("select account_id from member where id=?", UUID.class, worker);
        ai.answer(request -> {
            jdbc.update("update account set status='DISABLED', deactivated_at=now() where id=?", workerAccount);
            return success(request, List.of(assign("sound-a", worker), assign("sound-b", worker2)), List.of());
        });
        runTrigger();
        UUID job = onlyJob();
        awaitState(job, "SUCCEEDED");
        assertThat(count("select count(*) from task where event_id=? and bundle_key='sound-a' and assignee_id is not null", event)).isZero();
        assertThat(count("select count(*) from task where event_id=? and bundle_key='sound-b' and assignee_id=?", event, worker2)).isEqualTo(1);
        JsonNode stored = MAPPER.readTree(jdbc.queryForObject("select result::text from ai_job where id=?", String.class, job));
        assertThat(stored.get("unassigned").toString()).contains("\"sound-a\"").contains("MEMBER_NOT_ELIGIBLE");
        assertThat(count("select count(*) from notification where event_id=?", event)).isEqualTo(1);
    }

    // ---- 도우미 -----------------------------------------------------------------------------

    void runTrigger() {
        tx.executeWithoutResult(s -> trigger.publish(event, group));
        relay.relayOnce(); // 트리거 → 작업 접수
        relay.relayOnce(); // 작업 실행
        await(() -> !ai.calls.isEmpty());
    }

    UUID group(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into work_group (id, event_id, name, instructions, active) values (?, ?, ?, '', true)", id, event, name);
        return id;
    }

    UUID member(TestRows rows, UUID workGroup, boolean available, boolean isReserve) {
        UUID id = rows.member(event, rows.account(), "WORKER", true);
        jdbc.update("update member set work_group_id=?, available=?, is_reserve=? where id=?", workGroup, available, isReserve, id);
        return id;
    }

    void task(String bundle, UUID workGroup, UUID assignee, String start, String end, String status) {
        task(bundle, workGroup, assignee, start, end, status, "업무");
    }

    void task(String bundle, UUID workGroup, UUID assignee, String start, String end, String status, String title) {
        jdbc.update("""
                insert into task (id, event_id, work_group_id, bundle_key, zone_id, title, start_at, end_at, steps, checklist,
                                  status, assignee_id, first_assigned_at)
                values (?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, '["단계"]'::jsonb, '[]'::jsonb, ?, ?,
                        case when ?::uuid is null then null else now() end)""",
                UUID.randomUUID(), event, workGroup, bundle, zone, title, start, end, status, assignee, assignee);
    }

    void consent(boolean externalAi) {
        jdbc.update("update account set consent=jsonb_build_object('externalAi', ?::boolean) where id=?", externalAi, chiefAccount);
    }

    /** 미뤄 둔 트리거를 지금 꺼낼 수 있게 한다(30초를 기다리지 않는다). */
    void readyTriggers() {
        jdbc.update("update outbox_event set available_at = now() - interval '1 second' where event_id=? and type=? and processed_at is null",
                event, InitialAssignmentTrigger.TYPE);
    }

    UUID onlyJob() {
        return jdbc.queryForObject("select id from ai_job where event_id=? and kind='ASSIGN_INITIAL'", UUID.class, event);
    }

    UUID latestJob() {
        return jdbc.queryForObject("select id from ai_job where event_id=? and kind='ASSIGN_INITIAL' order by created_at desc limit 1",
                UUID.class, event);
    }

    long revision() { return jdbc.queryForObject("select revision from event where id=?", Long.class, event); }

    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    static List<String> keys(JsonNode items, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : items) out.add(item.get(field).asString());
        return out;
    }

    static JsonNode member(JsonNode payload, UUID id) {
        for (JsonNode m : payload.get("members")) if (m.get("memberId").asString().equals(id.toString())) return m;
        throw new AssertionError("팀원 없음");
    }

    static Map<String, String> assign(String bundle, UUID member) {
        return Map.of("bundleKey", bundle, "memberId", member.toString());
    }

    /** ai/assign/examples/success-rule.json과 같은 모양의 성공 응답 */
    static AiResponse success(JsonNode request, List<Map<String, String>> assignments, List<Map<String, String>> unassigned) {
        return success(request, assignments, unassigned, "RULE");
    }

    static AiResponse success(JsonNode request, List<Map<String, String>> assignments, List<Map<String, String>> unassigned,
            String generator) {
        ObjectNode body = MAPPER.createObjectNode();
        for (String field : List.of("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision"))
            body.set(field, request.get(field));
        body.put("resultSchemaVersion", "1");
        body.put("modelVersion", "assign-rule-v1");
        ObjectNode result = body.putObject("result");
        result.set("assignments", MAPPER.valueToTree(assignments));
        result.set("unassigned", MAPPER.valueToTree(unassigned));
        List<Map<String, String>> reasons = new ArrayList<>();
        for (var a : assignments) reasons.add(Map.of("bundleKey", a.get("bundleKey"), "text", "시간이 비는 팀원입니다."));
        result.set("reasons", MAPPER.valueToTree(reasons));
        result.put("generator", generator);
        result.putObject("coordination").put("fallback", "EXTERNAL_AI_NOT_ALLOWED");
        return new AiResponse(200, MAPPER.writeValueAsString(body));
    }

    void awaitState(UUID id, String state) {
        await(() -> state.equals(jdbc.queryForObject("select state from ai_job where id=?", String.class, id)));
    }

    static void await(BooleanSupplier condition) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("10초 안에 기대한 상태가 되지 않았습니다.");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        FakeAssign fakeAssign() {
            return new FakeAssign();
        }
    }

    /** 받은 요청을 남기고, 정해 둔 답을 돌려주는 가짜 AI 서버 */
    static final class FakeAssign implements AiClient {
        final List<JsonNode> calls = new CopyOnWriteArrayList<>();
        private volatile Function<JsonNode, AiResponse> answer;

        void answer(Function<JsonNode, AiResponse> answer) { this.answer = answer; }

        void reset() {
            calls.clear();
            answer = null;
        }

        @Override
        public AiResponse post(String path, String jsonBody, Duration timeout) {
            JsonNode request = MAPPER.readTree(jsonBody);
            calls.add(request);
            Function<JsonNode, AiResponse> current = answer;
            return current == null ? new AiResponse(503, "busy") : current.apply(request);
        }
    }
}
