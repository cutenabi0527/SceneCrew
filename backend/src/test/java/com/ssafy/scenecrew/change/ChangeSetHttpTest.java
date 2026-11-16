package com.ssafy.scenecrew.change;

import java.net.URI;
import java.net.http.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.notification.service.PushSender;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation.Candidate;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation.Request;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation.Selection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** #283 변경안 065~071·R2·076·077의 실제 HTTP·Security·PostgreSQL·Redis. 행사·그룹·구역·업무는 SQL로 만든다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ChangeSetHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ChangeSetHttpTest {
    static final String WEB="https://web.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"change-test"); r.add("scenecrew.auth.audience",()->"change-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PushSender push;
    @Autowired PasswordEncoder encoder;
    @Autowired com.ssafy.scenecrew.event.service.ChiefTransferService transfers;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.ssafy.scenecrew.change.service.ChangeSetService changes;
    @Autowired SafetySupportPreparation support;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    UUID chief, haneul, minsu, outsider, event, safety, sound, zoneA, zoneB, otherZone, haneulMember, minsuMember, patrol;
    String chiefCookie, haneulCookie, minsuCookie;
    boolean supportFixtureCreated;

    @BeforeEach void fixture() throws Exception {
        chief=account(); haneul=account(); minsu=account(); outsider=account();
        event=event("LIVE");
        safety=group(event,"관객 안전"); sound=group(event,"음향·장비");
        zoneA=zone(event,"객석 A"); zoneB=zone(event,"객석 B");
        member(event,chief,"CHIEF",null,"김하준"); haneulMember=member(event,haneul,"WORKER",safety,"오하늘");
        minsuMember=member(event,minsu,"WORKER",safety,"박민수");
        // 박민수의 18:00~21:00 객석 B 순회
        patrol=task(event,safety,minsuMember,zoneB,"객석 B 통로 순회","2026-11-14T09:00:00Z","2026-11-14T12:00:00Z");
        var other=event("LIVE"); otherZone=zone(other,"입구"); member(other,outsider,"CHIEF",null,"외부");
        chiefCookie=login(chief); haneulCookie=login(haneul); minsuCookie=login(minsu);
    }
    @AfterEach void cleanup() {
        if (supportFixtureCreated) {
            jdbc.update("DELETE FROM safety_observation WHERE event_id=?", event);
            jdbc.update("DELETE FROM safety_camera WHERE event_id=?", event);
            jdbc.update("DELETE FROM safety_zone_profile WHERE event_id=?", event);
            jdbc.update("DELETE FROM venue_layout WHERE event_id=?", event);
        }
        for (var e:events) {
            jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
            jdbc.update("DELETE FROM outbox_event WHERE event_id=? AND type='CHANGE_ACK_PUSH'",e);
            jdbc.update("DELETE FROM notification_delivery WHERE notification_id IN (SELECT id FROM notification WHERE event_id=?)",e);
            jdbc.update("DELETE FROM notification WHERE event_id=?",e);
            jdbc.update("DELETE FROM change_acknowledgment WHERE event_id=?",e);
            jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
            jdbc.update("ALTER TABLE change_set_evidence_revision DISABLE TRIGGER USER");
            jdbc.update("DELETE FROM change_set_evidence_revision WHERE change_set_id IN (SELECT id FROM change_set WHERE event_id=?)",e);
            jdbc.update("ALTER TABLE change_set_evidence_revision ENABLE TRIGGER USER");
            jdbc.update("DELETE FROM equipment_reservation_asset WHERE reservation_id IN (SELECT id FROM equipment_reservation WHERE event_id=?)",e);
            jdbc.update("DELETE FROM equipment_reservation WHERE event_id=?",e);
            jdbc.update("DELETE FROM equipment_asset WHERE event_id=?",e);
            jdbc.update("DELETE FROM change_set WHERE event_id=?",e);
            jdbc.update("DELETE FROM task WHERE event_id=?",e);
            jdbc.update("DELETE FROM program WHERE event_id=?",e);
            jdbc.update("DELETE FROM device_token WHERE member_id IN (SELECT id FROM member WHERE event_id=?)",e);
            jdbc.update("DELETE FROM member WHERE event_id=?",e);
            jdbc.update("DELETE FROM zone WHERE event_id=?",e);
            jdbc.update("DELETE FROM work_group WHERE event_id=?",e);
            jdbc.update("DELETE FROM outbox_event WHERE event_id=?",e);
            jdbc.update("DELETE FROM event WHERE id=?",e);
        }
        for (var a:accounts) {
            jdbc.update("DELETE FROM idempotency_record WHERE actor_account_id=?",a);
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM device_installation WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        events.clear(); accounts.clear();
    }

    /** 안전 지원: 오하늘에게 객석 A 지원 업무를 추가하고, 박민수의 순회를 오하늘에게 넘기지 않고 시간만 줄인다. */
    String supportOps() {
        return "[{\"type\":\"ADD_TASK\",\"payload\":{\"workGroupId\":\""+safety+"\",\"bundleKey\":\"support-1\",\"zoneId\":\""+zoneA+"\","
                +"\"programId\":null,\"title\":\"객석 A 안전 지원\",\"startAt\":\"2026-11-14T09:25:00Z\",\"endAt\":\"2026-11-14T09:55:00Z\","
                +"\"steps\":[\"객석 A로 이동\",\"통로 확보\"],\"checklist\":[{\"key\":\"arrive\",\"text\":\"도착 보고\",\"required\":true}],"
                +"\"tempId\":\"tmp-support\",\"assigneeId\":\""+haneulMember+"\"}},"
                +"{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"endAt\":\"2026-11-14T11:00:00Z\"}}]";
    }
    String createBody(String ops) { return "{\"mode\":\"MANUAL\",\"operations\":"+ops+",\"rationale\":\"객석 A 밀집 대응\",\"expectedEventRevision\":\""+rev()+"\"}"; }

    @Test void createPreviewApplyAcknowledgeFullFlow() throws Exception {
        String key=UUID.randomUUID().toString();
        var created=send(post("/api/v1/events/"+event+"/change-sets",createBody(supportOps())).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var cs=json.readTree(created.body());
        assertThat(cs.propertyNames()).containsExactly("id","eventId","status","revision","baseRevision","mode","operations","rationale",
                "sourceJobId","issueId","previewHash","diff","eventRevision","evidenceRefs","simulationRefs","evidenceSnapshot");
        assertThat(cs.get("status").asString()).isEqualTo("DRAFT");
        assertThat(cs.get("revision").asString()).isEqualTo("1");
        assertThat(cs.get("previewHash").isNull()).isTrue();
        assertThat(cs.get("diff").size()).isZero();
        assertThat(cs.get("evidenceSnapshot").get("changeSetRevision").asString()).isEqualTo("1");
        String id=cs.get("id").asString();
        // 생성은 아무것도 바꾸지 않는다(검사만).
        assertThat(count("SELECT count(*) FROM task WHERE event_id=?",event)).isEqualTo(1);
        var replay=send(post("/api/v1/events/"+event+"/change-sets",createBody(supportOps())).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(json.readTree(replay.body())).isEqualTo(cs);
        error(send(get("/api/v1/change-sets/"+id).header("Cookie",haneulCookie)),403,"FORBIDDEN");

        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}")
                .header("Cookie",chiefCookie))).body());
        assertThat(preview.get("status").asString()).isEqualTo("READY");
        assertThat(preview.get("revision").asString()).isEqualTo("2");
        assertThat(preview.get("previewHash").asString()).matches("[0-9a-f]{64}");
        var diff=preview.get("diff");
        assertThat(diff.size()).isEqualTo(2);
        assertThat(diff.get(0).propertyNames()).containsExactly("operationIndex","type","targetId","summary");
        assertThat(diff.get(0).get("summary").asString()).isEqualTo("업무 추가: 객석 A 안전 지원 (18:25~18:55, 객석 A, 담당 오하늘)");
        assertThat(diff.get(1).get("summary").asString()).isEqualTo("업무 수정: 객석 B 통로 순회 — 시간 18:00~21:00 → 18:00~20:00");
        assertThat(count("SELECT count(*) FROM task WHERE event_id=?",event)).isEqualTo(1); // 미리보기도 되돌린다
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=?",event)).isZero();

        String eventRevBefore=rev(), applyKey=UUID.randomUUID().toString();
        String applyBody="{\"expectedEventRevision\":\""+eventRevBefore+"\",\"expectedChangeSetRevision\":\"2\",\"previewHash\":\""+preview.get("previewHash").asString()+"\"}";
        var applied=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/apply",applyBody).header("Cookie",chiefCookie).header("Idempotency-Key",applyKey))).body());
        assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
        assertThat(applied.get("revision").asString()).isEqualTo("3");
        assertThat(applied.get("diff")).isEqualTo(diff);
        assertThat(rev()).isEqualTo(String.valueOf(Long.parseLong(eventRevBefore)+1));
        assertThat(applied.get("eventRevision").asString()).isEqualTo(rev());
        String newTask=diff.get(0).get("targetId").asString();
        var task=jdbc.queryForMap("SELECT assignee_id, status, first_assigned_at, checklist::text AS checklist FROM task WHERE id=?::uuid",newTask);
        assertThat(task.get("assignee_id")).isEqualTo(haneulMember);
        assertThat(task.get("status")).isEqualTo("WAITING");
        assertThat(task.get("first_assigned_at")).isNotNull();
        assertThat(json.readTree((String)task.get("checklist")).get(0).get("checked").asBoolean()).isFalse();
        assertThat(count("SELECT count(*) FROM operation_log WHERE change_set_id=?::uuid",id)).isEqualTo(2);
        var again=send(post("/api/v1/change-sets/"+id+"/apply",applyBody).header("Cookie",chiefCookie).header("Idempotency-Key",applyKey));
        assertThat(json.readTree(again.body())).isEqualTo(applied);
        error(send(post("/api/v1/change-sets/"+id+"/apply",applyBody).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),409,"INVALID_STATE");

        // 오하늘: 추가 업무 확인. 박민수: 순회 시간 변경 확인.
        var mine=json.readTree(ok(send(get("/api/v1/events/"+event+"/my-changes").header("Cookie",haneulCookie))).body());
        var ack=mine.get("items").get(0);
        assertThat(ack.propertyNames()).containsExactly("id","participationRevision","state","targetsHash","targets","acknowledgedAt");
        assertThat(ack.get("state").asString()).isEqualTo("PENDING");
        assertThat(ack.get("targets").get(0).get("changeKinds").get(0).asString()).isEqualTo("ADDED");
        assertThat(ack.get("targets").get(0).get("removed").asBoolean()).isFalse();
        var minsuChanges=json.readTree(ok(send(get("/api/v1/events/"+event+"/my-changes").header("Cookie",minsuCookie))).body());
        assertThat(minsuChanges.get("items").get(0).get("targets").get(0).get("changeKinds").get(0).asString()).isEqualTo("TIME");
        String ackId=ack.get("id").asString();
        error(send(post("/api/v1/change-acknowledgments/"+ackId+"/ack","{\"targetsHash\":\"wrong\"}").header("Cookie",haneulCookie)),409,"INVALID_STATE");
        error(send(post("/api/v1/change-acknowledgments/"+ackId+"/ack","{\"targetsHash\":\""+ack.get("targetsHash").asString()+"\"}")
                .header("Cookie",minsuCookie)),404,"NOT_FOUND");
        var done=json.readTree(ok(send(post("/api/v1/change-acknowledgments/"+ackId+"/ack","{\"targetsHash\":\""+ack.get("targetsHash").asString()+"\"}")
                .header("Cookie",haneulCookie))).body());
        assertThat(done.get("state").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(done.get("acknowledgedAt").isString()).isTrue();
    }


    @Test void support361ZeroMovesBothWorkersThroughExistingHttpAndRecordsArrival() throws Exception {
        var fixture = supportFixture(0);
        registerPushFixture(haneul, haneulMember);
        registerPushFixture(minsu, minsuMember);
        var page = json.readTree(ok(send(get(supportCandidatesPath(fixture.request())).header("Cookie", chiefCookie))).body());
        var selected = page.get("candidates").valueStream().map(candidate -> {
            assertThat(candidate.get("participationRevision").isString()).isTrue();
            assertThat(candidate.get("eligibilityReasons").valueStream().map(JsonNode::asString).toList())
                    .contains("MINIMUM_CREW_SATISFIED");
            return new Candidate(UUID.fromString(candidate.get("memberId").asString()),
                    Long.parseLong(candidate.get("participationRevision").asString()),
                    UUID.fromString(candidate.get("sourceTaskId").asString()));
        }).toList();
        assertThat(selected).containsExactlyInAnyOrderElementsOf(fixture.selected());
        var ready = supportReady(fixture.request(), selected);
        String key = UUID.randomUUID().toString();
        var applied = ok(send(ack242Apply(ready, key)));
        assertThat(json.readTree(ok(send(ack242Apply(ready, key))).body())).isEqualTo(json.readTree(applied.body()));
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND zone_id=? AND assignee_id IS NOT NULL", event, zoneB)).isZero();
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND zone_id=? AND assignee_id IS NOT NULL", event, zoneA)).isEqualTo(2);
        assertThat(pushWorkCount()).isEqualTo(2);
        assertThat(notification345Inbox(haneulCookie).size()).isEqualTo(1);
        var ack = ack242Mine(haneulCookie).get(0);
        assertThat(ack.get("state").asString()).isEqualTo("PENDING");
        String ackBody = json.createObjectNode().put("targetsHash", ack.get("targetsHash").asString()).toString();
        ok(send(post("/api/v1/change-acknowledgments/" + ack.get("id").asString() + "/ack", ackBody)
                .header("Cookie", haneulCookie)));
        UUID taskId = jdbc.queryForObject("SELECT id FROM task WHERE event_id=? AND zone_id=? AND assignee_id=?",
                UUID.class, event, zoneA, haneulMember);
        var removeArrival = ack242Ready("""
                [{"type":"UPDATE_TASK","payload":{"taskId":"%s","checklist":[]}}]
                """.formatted(taskId));
        error(send(ack242Apply(removeArrival, UUID.randomUUID().toString())), 409, "INVALID_STATE");
        String taskUrl = "/api/v1/tasks/" + taskId;
        var assigned = json.readTree(ok(send(get(taskUrl).header("Cookie", haneulCookie))).body());
        assertThat(assigned.get("id").asString()).isEqualTo(taskId.toString());
        ok(send(post(taskUrl + "/start", supportTaskBody().toString()).header("Cookie", haneulCookie)));
        error(send(post(taskUrl + "/complete", supportTaskBody().toString()).header("Cookie", haneulCookie)), 409, "INVALID_STATE");
        var check = supportTaskBody();
        check.putArray("checks").addObject().put("key", "arrive").put("checked", true);
        ok(send(patch(taskUrl + "/checklist", check.toString()).header("Cookie", haneulCookie)));
        var completed = json.readTree(ok(send(post(taskUrl + "/complete", supportTaskBody().toString())
                .header("Cookie", haneulCookie))).body()).get("task");
        assertThat(completed.get("status").asString()).isEqualTo("DONE");
        assertThat(completed.get("checklist").get(0).get("checkedByMemberId").asString()).isEqualTo(haneulMember.toString());
        assertThat(completed.get("checklist").get(0).get("checkedAt").isString()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void support361MinimumOneSerializesDifferentKeysAndRechecksAfterCommit(boolean sameWorker) throws Exception {
        var fixture = supportFixture(1);
        var first = fixture.selected().getFirst();
        var second = fixture.selected().getLast();
        var candidates = supportTransaction().execute(status -> support.candidates(chief, fixture.request()));
        assertThat(candidates).containsExactlyInAnyOrder(first, second);
        var firstReady = supportReady(fixture.request(), List.of(first));
        var secondReady = supportReady(fixture.request(), List.of(sameWorker ? first : second));
        var tx = transactionManager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        java.util.concurrent.CompletableFuture<HttpResponse<String>> competing = null;
        try {
            var firstBody = json.readTree(firstReady.get("body"));
            changes.apply(UUID.fromString(firstReady.get("id")), chief, UUID.randomUUID().toString(),
                    Long.parseLong(firstBody.get("expectedChangeSetRevision").asString()), fixture.request().expectedEventRevision(),
                    firstBody.get("previewHash").asString());
            int owner = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            competing = notification345Async(ack242Apply(secondReady, UUID.randomUUID().toString()));
            // Use a separate connection so pg_stat_activity is refreshed while the owner keeps its transaction open.
            var wait = new java.util.concurrent.FutureTask<>(() ->
                    notification345Waiter(owner, "SELECT state, revision FROM event", "support approval waits for Event"));
            Thread.ofVirtual().start(wait);
            wait.get(15, java.util.concurrent.TimeUnit.SECONDS);
            transactionManager.commit(tx);
            error(competing.get(15, java.util.concurrent.TimeUnit.SECONDS), 409, "VERSION_CONFLICT");
        } finally {
            if (!tx.isCompleted()) transactionManager.rollback(tx);
            if (competing != null) competing.handle((unused, failure) -> null).get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        var latest = fixture.atRevision(Long.parseLong(rev()));
        assertSupportRejected(latest, List.of(second), "SUPPORT_MINIMUM_CREW");
        assertSupportRejected(latest, List.of(first), "SUPPORT_CANDIDATE_CHANGED");
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND zone_id=? AND assignee_id IS NOT NULL", event, zoneB)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND zone_id=? AND assignee_id IS NOT NULL", event, zoneA)).isEqualTo(1);
    }

    @Test void support361MissingMinimumAndChangedParticipationRejectPreparation() {
        var fixture = supportFixture(null);
        assertSupportRejected(fixture.request(), fixture.selected(), "SUPPORT_CREW_RULE_UNAVAILABLE");
        jdbc.update("UPDATE safety_zone_profile SET minimum_crew_rule=?::jsonb WHERE zone_id=?",
                supportMinimum(fixture.request(), 0), zoneB);
        jdbc.update("UPDATE member SET participation_revision=participation_revision+1 WHERE id=?", haneulMember);
        assertSupportRejected(fixture.request(), fixture.selected(), "SUPPORT_CANDIDATE_CHANGED");
        assertSupportRejected(fixture.request(), List.of(fixture.selected().getLast(), fixture.selected().getLast()),
                "SUPPORT_CANDIDATE_DUPLICATE");
        jdbc.update("UPDATE safety_observation SET model_version='test-stub', estimates=jsonb_set(estimates,'{0,qualityReasons}', '[\"TEST_VALUE\"]') WHERE id=?",
                fixture.request().observationId());
        assertSupportRejected(fixture.request(), fixture.selected(), "SUPPORT_TEST_OBSERVATION");
    }

    @Test void support361StartedOriginTaskCannotBeReleasedEvenAfterRefreshingRevision() throws Exception {
        var fixture = supportFixture(0);
        assertThatThrownBy(() -> supportTransaction().execute(status ->
                support.prepare(minsu, fixture.request(), fixture.selected())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        ok(send(post("/api/v1/tasks/" + patrol + "/start", supportTaskBody().toString()).header("Cookie", minsuCookie)));
        assertThatThrownBy(() -> supportTransaction().execute(status ->
                support.prepare(chief, fixture.request(), fixture.selected())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        assertSupportRejected(fixture.atRevision(Long.parseLong(rev())), List.of(fixture.selected().getLast()),
                "SUPPORT_CANDIDATE_CHANGED");
    }

    @Test void support361NotificationFailureRestoresBothOriginAssignmentsAndAllowsRetry() throws Exception {
        var fixture = supportFixture(0);
        registerPushFixture(haneul, haneulMember);
        registerPushFixture(minsu, minsuMember);
        var ready = supportReady(fixture.request(), fixture.selected());
        var before = notification345Snapshot();
        String gate = "support361_" + UUID.randomUUID().toString().replace("-", "");
        String key = UUID.randomUUID().toString();
        try {
            jdbc.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.event_id='%s'::uuid AND NEW.member_id='%s'::uuid THEN
                        RAISE EXCEPTION 'support notification insert failure' USING ERRCODE='23514';
                      END IF;
                      RETURN NEW;
                    END $$
                    """.formatted(gate, event, minsuMember));
            jdbc.execute("CREATE TRIGGER " + gate + " BEFORE INSERT ON notification FOR EACH ROW EXECUTE FUNCTION " + gate + "()");
            error(send(ack242Apply(ready, key)), 500, "INTERNAL_ERROR");
            assertThat(notification345Snapshot()).isEqualTo(before);
            assertThat(pushWorkCount()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + gate + " ON notification");
            jdbc.execute("DROP FUNCTION IF EXISTS " + gate + "()");
        }
        ok(send(ack242Apply(ready, key)));
        assertThat(pushWorkCount()).isEqualTo(2);
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"observation","round","operations","minimum"})
    void support361ApplyRechecksWithoutEventRevisionChange(String changed) throws Exception {
        var fixture = supportFixture(0);
        var ready = supportReady(fixture.request(), fixture.selected());
        switch (changed) {
            case "observation" -> jdbc.update("UPDATE safety_camera SET freshness='INVALID_MAPPING' WHERE event_id=?", event);
            case "round" -> jdbc.update("UPDATE member SET participation_revision=participation_revision+1 WHERE id=?", haneulMember);
            case "minimum" -> jdbc.update("UPDATE safety_zone_profile SET minimum_crew_rule=NULL WHERE zone_id=?", zoneB);
            case "operations" -> {
                var edit = json.createObjectNode().put("expectedEventRevision", rev()).put("expectedChangeSetRevision", "2");
                edit.putArray("operations").add(json.readTree(supportOps()).get(0));
                error(send(patch("/api/v1/change-sets/" + ready.get("id"), edit.toString()).header("Cookie", chiefCookie)),
                        409, "INVALID_STATE");
                jdbc.update("UPDATE change_set SET operations=?::jsonb WHERE id=?::uuid", supportOps(), ready.get("id"));
            }
            default -> throw new AssertionError(changed);
        }
        var before = notification345Snapshot();
        error(send(ack242Apply(ready, UUID.randomUUID().toString())), 409, "INVALID_STATE");
        assertThat(notification345Snapshot()).isEqualTo(before);
    }

    @Test void support361RejectsLosingTheLastOriginWorkerByCompletionOrReassignment() throws Exception {
        var fixture = supportFixture(1);
        var ready = supportReady(fixture.request(), List.of(fixture.selected().getFirst()));
        ok(send(ack242Apply(ready, UUID.randomUUID().toString())));

        String release = """
                [{"type":"ASSIGN_TASK","payload":{"taskId":"%s","fromMemberId":"%s","toMemberId":null}}]
                """.formatted(patrol, minsuMember);
        String cancel = """
                [{"type":"CANCEL_TASK","payload":{"taskId":"%s","reason":"fixture"}}]
                """.formatted(patrol);
        for (String ops : List.of(release, cancel)) {
            var reduction = ack242Ready(ops);
            error(send(ack242Apply(reduction, UUID.randomUUID().toString())), 409, "INVALID_STATE");
        }
        error(send(patch("/api/v1/members/" + minsuMember,
                json.createObjectNode().put("expectedEventRevision", rev()).put("workGroupId", sound.toString()).toString())
                .header("Cookie", chiefCookie)), 409, "INVALID_STATE");
        ok(send(post("/api/v1/tasks/" + patrol + "/start", supportTaskBody().toString()).header("Cookie", minsuCookie)));
        error(send(post("/api/v1/tasks/" + patrol + "/complete", supportTaskBody().toString())
                .header("Cookie", minsuCookie)), 409, "INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?", String.class, patrol)).isEqualTo("IN_PROGRESS");
    }

    @Test void support361ChiefTransferCannotRemoveTheLastOriginWorker() throws Exception {
        var fixture = supportFixture(1);
        var ready = supportReady(fixture.request(), List.of(fixture.selected().getFirst()));
        ok(send(ack242Apply(ready, UUID.randomUUID().toString())));
        String revision = rev();

        var transfer = json.createObjectNode()
                .put("successorMemberId", minsuMember.toString())
                .put("previousChiefWorkGroupId", sound.toString())
                .put("expectedEventRevision", revision);
        var rejected = send(post("/api/v1/events/" + event + "/chief-transfer", transfer.toString())
                .header("Cookie", chiefCookie));
        error(rejected, 409, "INVALID_STATE");
        assertThat(json.readTree(rejected.body()).path("details").path("reason").asString())
                .isEqualTo("SUPPORT_MINIMUM_CREW");
        assertThat(rev()).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT account_id FROM member WHERE event_id=? AND event_role='CHIEF'", UUID.class, event))
                .isEqualTo(chief);
        assertThat(jdbc.queryForObject("SELECT event_role FROM member WHERE id=?", String.class, minsuMember))
                .isEqualTo("WORKER");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?", UUID.class, patrol))
                .isEqualTo(minsuMember);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void support361DepartureCommitsAndNotifiesChief(boolean deactivate) throws Exception {
        var fixture = supportFixture(1);
        var ready = supportReady(fixture.request(), List.of(fixture.selected().getLast()));
        ok(send(ack242Apply(ready, UUID.randomUUID().toString())));
        if (deactivate) {
            ok(send(notification345Deactivate()));
        } else {
            ok(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/" + event + "/leave")))
                    .header("Origin", WEB).header("Cookie", haneulCookie)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.noBody())));
        }
        assertThat(jdbc.queryForObject("SELECT active FROM member WHERE id=?", Boolean.class, haneulMember)).isFalse();
        var notices = notification345Inbox(chiefCookie);
        assertThat(notices.size()).isEqualTo(1);
        assertThat(notices.get(0).get("title").asString()).isEqualTo("원 구역 안전 인력 부족");
        assertThat(notices.get(0).get("target").get("kind").asString()).isEqualTo("TASK");
        var latest = fixture.atRevision(Long.parseLong(rev()));
        assertThatThrownBy(() -> changes.createSupport(chief, UUID.randomUUID().toString(), new Selection(latest, fixture.selected())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getDetails()).containsEntry("reason", "SUPPORT_MINIMUM_CREW"));
    }

    @Test void support361OverdueInProgressRetainsOccupancyAndRejectsLaterWork() throws Exception {
        var fixture = supportFixture(1);
        var ready = supportReady(fixture.request(), List.of(fixture.selected().getFirst()));
        ok(send(ack242Apply(ready, UUID.randomUUID().toString())));
        UUID supportTask = jdbc.queryForObject("SELECT id FROM task WHERE event_id=? AND zone_id=?", UUID.class, event, zoneA);
        ok(send(post("/api/v1/tasks/" + supportTask + "/start", supportTaskBody().toString()).header("Cookie", haneulCookie)));
        Instant past = Instant.now().minusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        // Move the fixture's scheduled end into the past while retaining its actual IN_PROGRESS state.
        jdbc.update("UPDATE task SET end_at=?::timestamptz WHERE id=?", past.toString(), supportTask);
        jdbc.update("""
                UPDATE operation_log SET changes=jsonb_set(changes,'{selection,request,endAt}',to_jsonb(?::text))
                WHERE change_set_id=?::uuid AND type='SAFETY_SUPPORT_SELECTED'
                """, past.toString(), ready.get("id"));
        var futureOperations = json.createArrayNode();
        var future = (tools.jackson.databind.node.ObjectNode) json.readTree(supportOps()).get(0);
        ((tools.jackson.databind.node.ObjectNode) future.get("payload"))
                .put("startAt", past.plusSeconds(5).toString()).put("endAt", past.plusSeconds(100).toString());
        futureOperations.add(future);
        error(send(post("/api/v1/events/" + event + "/change-sets", createBody(futureOperations.toString()))
                .header("Cookie", chiefCookie).header("Idempotency-Key", UUID.randomUUID().toString())), 422, "TIME_OVERLAP");
        UUID next = task(event, safety, haneulMember, zoneB, "후속 배정", past.plusSeconds(5).toString(),
                past.plusSeconds(100).toString());
        error(send(post("/api/v1/tasks/" + next + "/start", supportTaskBody().toString())
                .header("Cookie", haneulCookie)), 409, "INVALID_STATE");
        var reduction = ack242Ready("""
                [{"type":"ASSIGN_TASK","payload":{"taskId":"%s","fromMemberId":"%s","toMemberId":null}}]
                """.formatted(patrol, minsuMember));
        error(send(ack242Apply(reduction, UUID.randomUUID().toString())), 409, "INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?", String.class, supportTask)).isEqualTo("IN_PROGRESS");
        var check = supportTaskBody();
        check.putArray("checks").addObject().put("key", "arrive").put("checked", true);
        ok(send(patch("/api/v1/tasks/" + supportTask + "/checklist", check.toString()).header("Cookie", haneulCookie)));
        ok(send(post("/api/v1/tasks/" + supportTask + "/complete", supportTaskBody().toString()).header("Cookie", haneulCookie)));
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?", UUID.class,
                fixture.selected().getFirst().sourceTaskId())).isNull();
        ok(send(post("/api/v1/tasks/" + next + "/start", supportTaskBody().toString()).header("Cookie", haneulCookie)));
    }


    @Test void support361SafetyProfileWriteLocksEventAndRollsBackUnsafeMinimum() throws Exception {
        var fixture = supportFixture(1);
        var ready = supportReady(fixture.request(), List.of(fixture.selected().getFirst()));
        ok(send(ack242Apply(ready, UUID.randomUUID().toString())));
        var unsafe = safetyProfileChange(fixture.request(), 2);
        // Match the existing geometry so this test only changes the source profile.
        jdbc.update("UPDATE venue_layout SET zone_polygons=?::jsonb WHERE event_id=?",
                unsafe.get("layout").get("zonePolygons").toString(), event);
        var before = notification345Snapshot();
        var profiles = safetyProfileRows();
        var layouts = jdbc.queryForList("SELECT * FROM venue_layout WHERE event_id=?", event);
        var cameras = jdbc.queryForList("SELECT * FROM safety_camera WHERE event_id=?", event);

        java.util.concurrent.CompletableFuture<HttpResponse<String>> waiting = null;
        try (var owner = jdbc.getDataSource().getConnection()) {
            owner.setAutoCommit(false);
            try {
                int pid;
                try (var statement = owner.prepareStatement("SELECT pg_backend_pid() FROM event WHERE id=? FOR UPDATE")) {
                    statement.setObject(1, event);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        pid = rows.getInt(1);
                    }
                }
                waiting = notification345Async(safetyProfilePatch(unsafe));
                notification345Waiter(pid, "SELECT state, revision FROM event", "safety profile waits for Event");
            } finally {
                owner.rollback();
            }
        } finally {
            notification345Drain(waiting);
        }

        var rejected = waiting.get();
        error(rejected, 409, "INVALID_STATE");
        assertThat(json.readTree(rejected.body()).get("details").get("reason").asString())
                .isEqualTo("SUPPORT_MINIMUM_CREW");
        assertThat(notification345Snapshot()).isEqualTo(before);
        assertThat(safetyProfileRows()).isEqualTo(profiles);
        assertThat(jdbc.queryForList("SELECT * FROM venue_layout WHERE event_id=?", event)).isEqualTo(layouts);
        assertThat(jdbc.queryForList("SELECT * FROM safety_camera WHERE event_id=?", event)).isEqualTo(cameras);

        var safe = safetyProfileChange(fixture.request(), 1);
        ok(send(safetyProfilePatch(safe)));
        assertThat(count("SELECT count(*) FROM safety_zone_profile WHERE zone_id=? AND effective_area_m2=10"
                + " AND minimum_crew_rule->>'minimumCount'='1'", zoneB)).isEqualTo(1);
        assertThat(notification345Snapshot().get("task")).isEqualTo(before.get("task"));
        assertThat(notification345Snapshot().get("change_set")).isEqualTo(before.get("change_set"));
        assertThat(jdbc.queryForList("SELECT * FROM safety_camera WHERE event_id=?", event)).isEqualTo(cameras);
    }

    private List<Map<String, Object>> safetyProfileRows() {
        return jdbc.queryForList("SELECT * FROM safety_zone_profile WHERE event_id=? ORDER BY zone_id", event);
    }

    private HttpRequest.Builder safetyProfilePatch(JsonNode body) {
        return patch("/api/v1/events/" + event + "/safety-map", body.toString()).header("Cookie", chiefCookie)
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private JsonNode safetyProfileChange(Request request, int minimum) {
        var evidence = json.readTree("""
                {"sourceKind":"MANUAL_CONFIRMATION","sourceRef":"fixture","observedAt":null}
                """);
        var points = json.readTree("""
                [{"x":0.1,"y":0.1},{"x":0.8,"y":0.1},{"x":0.8,"y":0.8},{"x":0.1,"y":0.8}]
                """);
        var body = json.createObjectNode().put("expectedEventRevision", rev()).put("expectedLayoutRevision", "1");
        var layout = body.putObject("layout").put("coordinateSystem", "normalized-image").put("width", 1).put("height", 1);
        layout.set("source", evidence);
        var polygons = layout.putArray("zonePolygons");
        var profiles = body.putArray("profiles");
        for (UUID zone : List.of(zoneA, zoneB)) {
            polygons.addObject().put("zoneId", zone.toString()).set("points", points);
            var profile = profiles.addObject().put("zoneId", zone.toString()).put("expectedProfileRevision", "1")
                    .putNull("effectiveAreaM2").putNull("areaEvidence").putNull("riskRule").putNull("minimumCrewRule");
            if (zone.equals(zoneB)) {
                profile.put("effectiveAreaM2", 10).set("areaEvidence", evidence);
                profile.putObject("minimumCrewRule").put("workGroupId", safety.toString()).put("minimumCount", minimum)
                        .put("startAt", request.startAt().toString()).put("endAt", request.endAt().toString())
                        .set("evidence", evidence);
            }
        }
        return body;
    }


    @Test void support361PublicCandidatesAreChiefOnlyAndDoNotReserveWorkers() throws Exception {
        var fixture = supportFixture(1);
        String path = supportCandidatesPath(fixture.request());
        error(send(get(path)), 401, "UNAUTHENTICATED");
        error(send(get(path).header("Cookie", minsuCookie)), 403, "FORBIDDEN");
        error(send(get(path).header("Cookie", login(outsider))), 404, "NOT_FOUND");
        error(send(get(path + "&startAt=" + fixture.request().startAt()).header("Cookie", chiefCookie)),
                400, "INVALID_REQUEST");
        var before = notification345Snapshot();
        var response = ok(send(get(path).header("Cookie", chiefCookie)));
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var candidates = json.readTree(response.body());
        assertThat(candidates.get("eventRevision").asString()).isEqualTo(rev());
        assertThat(candidates.get("candidates").size()).isEqualTo(2);
        assertThat(notification345Snapshot()).isEqualTo(before);
        assertThat(pushWorkCount()).isZero();
        var excessive = send(supportCreate(supportBody(fixture.request(), fixture.selected()), UUID.randomUUID().toString()));
        supportError(excessive, "SUPPORT_MINIMUM_CREW");

        jdbc.update("UPDATE member SET available=false WHERE event_id=? AND event_role='WORKER'", event);
        assertThat(json.readTree(ok(send(get(path).header("Cookie", chiefCookie))).body())
                .get("candidates").size()).isZero();
        jdbc.update("UPDATE safety_zone_profile SET minimum_crew_rule=NULL WHERE zone_id=?", zoneB);
        supportError(send(get(path).header("Cookie", chiefCookie)), "SUPPORT_CREW_RULE_UNAVAILABLE");
    }

    @Test void support361PublicCreateRejectsStaleOrMixedInputsAndReplaysTheSameKey() throws Exception {
        var fixture = supportFixture(0);
        String path = supportCandidatesPath(fixture.request());
        ok(send(get(path).header("Cookie", chiefCookie)));
        var body = supportBody(fixture.request(), fixture.selected());
        var mixed = body.deepCopy();
        mixed.set("operations", json.readTree(supportOps()));
        error(send(supportCreate(mixed, UUID.randomUUID().toString())), 400, "INVALID_REQUEST");
        var wrongMode = body.deepCopy().put("mode", "AI");
        error(send(supportCreate(wrongMode, UUID.randomUUID().toString())), 400, "INVALID_REQUEST");
        var numericRound = body.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) numericRound.get("supportSelection").get("candidates").get(0))
                .put("participationRevision", 2);
        error(send(supportCreate(numericRound, UUID.randomUUID().toString())), 400, "INVALID_REQUEST");
        supportError(send(supportCreate(supportBody(fixture.request(),
                List.of(fixture.selected().getFirst(), fixture.selected().getFirst())), UUID.randomUUID().toString())),
                "SUPPORT_CANDIDATE_DUPLICATE");

        jdbc.update("UPDATE member SET participation_revision=3 WHERE id=?", haneulMember);
        supportError(send(supportCreate(body, UUID.randomUUID().toString())), "SUPPORT_CANDIDATE_CHANGED");
        jdbc.update("UPDATE event SET revision=revision+1 WHERE id=?", event);
        error(send(get(path).header("Cookie", chiefCookie)), 409, "VERSION_CONFLICT");
        error(send(supportCreate(body, UUID.randomUUID().toString())), 409, "VERSION_CONFLICT");

        var latest = fixture.atRevision(Long.parseLong(rev()));
        var current = new Candidate(haneulMember, 3, fixture.selected().getFirst().sourceTaskId());
        var currentBody = supportBody(latest, List.of(current));
        String key = UUID.randomUUID().toString();
        var created = send(supportCreate(currentBody, key));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        jdbc.update("UPDATE safety_camera SET freshness='INVALID_MAPPING' WHERE event_id=?", event);
        supportError(send(get(supportCandidatesPath(latest)).header("Cookie", chiefCookie)),
                "SUPPORT_OBSERVATION_UNAVAILABLE");
        supportError(send(supportCreate(currentBody, UUID.randomUUID().toString())), "SUPPORT_OBSERVATION_UNAVAILABLE");
        var replay = send(supportCreate(currentBody, key));
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(created.body()));
        error(send(post("/api/v1/events/" + event + "/change-sets", createBody(supportOps()))
                .header("Cookie", chiefCookie).header("Idempotency-Key", key)), 409, "IDEMPOTENCY_MISMATCH");
        assertThat(count("SELECT count(*) FROM change_set WHERE event_id=?", event)).isEqualTo(1);
    }

    private String supportCandidatesPath(Request request) {
        return "/api/v1/events/" + request.eventId() + "/safety-support-candidates?observationId=" + request.observationId()
                + "&sourceZoneId=" + request.sourceZoneId() + "&targetZoneId=" + request.targetZoneId()
                + "&workGroupId=" + request.workGroupId() + "&startAt=" + request.startAt() + "&endAt=" + request.endAt()
                + "&expectedEventRevision=" + request.expectedEventRevision();
    }

    private tools.jackson.databind.node.ObjectNode supportBody(Request request, List<Candidate> selected) {
        var body = json.createObjectNode().put("mode", "MANUAL")
                .put("expectedEventRevision", Long.toString(request.expectedEventRevision()));
        var candidates = body.putObject("supportSelection")
                .put("observationId", request.observationId().toString())
                .put("sourceZoneId", request.sourceZoneId().toString()).put("targetZoneId", request.targetZoneId().toString())
                .put("workGroupId", request.workGroupId().toString())
                .put("startAt", request.startAt().toString()).put("endAt", request.endAt().toString())
                .putArray("candidates");
        for (Candidate candidate : selected) {
            candidates.addObject().put("memberId", candidate.memberId().toString())
                    .put("participationRevision", Long.toString(candidate.participationRevision()))
                    .put("sourceTaskId", candidate.sourceTaskId().toString());
        }
        return body;
    }

    private HttpRequest.Builder supportCreate(JsonNode body, String key) {
        return post("/api/v1/events/" + event + "/change-sets", body.toString())
                .header("Cookie", chiefCookie).header("Idempotency-Key", key);
    }

    private void supportError(HttpResponse<String> response, String reason) {
        error(response, 409, "INVALID_STATE");
        assertThat(json.readTree(response.body()).get("details").get("reason").asString()).isEqualTo(reason);
    }

    private Map<String,String> supportReady(Request request, List<Candidate> selected) throws Exception {
        var response = send(supportCreate(supportBody(request, selected), UUID.randomUUID().toString()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var created = json.readTree(response.body());
        String id = created.get("id").asString();
        var previewBody = json.createObjectNode().put("expectedEventRevision", rev())
                .put("expectedChangeSetRevision", created.get("revision").asString()).toString();
        var preview = json.readTree(ok(send(post("/api/v1/change-sets/" + id + "/preview", previewBody)
                .header("Cookie", chiefCookie))).body());
        var applyBody = json.createObjectNode().put("expectedEventRevision", rev())
                .put("expectedChangeSetRevision", preview.get("revision").asString())
                .put("previewHash", preview.get("previewHash").asString()).toString();
        return Map.of("id", id, "body", applyBody);
    }

    private void assertSupportRejected(Request request, List<Candidate> selected, String reason) {
        assertThatThrownBy(() -> supportTransaction().execute(status -> support.prepare(chief, request, selected)))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getDetails()).containsEntry("reason", reason));
    }

    private org.springframework.transaction.support.TransactionTemplate supportTransaction() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    private tools.jackson.databind.node.ObjectNode supportTaskBody() {
        return json.createObjectNode().put("expectedEventRevision", rev());
    }

    private record SupportFixture(Request request, List<Candidate> selected) {
        Request atRevision(long revision) {
            return new Request(request.eventId(), request.observationId(), request.sourceZoneId(), request.targetZoneId(),
                    request.workGroupId(), request.startAt(), request.endAt(), revision);
        }
    }

    private SupportFixture supportFixture(Integer minimum) {
        supportFixtureCreated = true;
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var request = new Request(event, UUID.randomUUID(), zoneB, zoneA, safety,
                now.minusSeconds(60), now.plusSeconds(600), Long.parseLong(rev()));
        jdbc.update("UPDATE task SET start_at=?::timestamptz, end_at=?::timestamptz WHERE id=?",
                request.startAt().toString(), request.endAt().toString(), patrol);
        UUID other = task(event, safety, haneulMember, zoneB, "B구역 배치",
                request.startAt().toString(), request.endAt().toString());
        UUID chiefId = jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?", UUID.class, event, chief);
        UUID layout = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO venue_layout(id,event_id,revision,coordinate_system,width,height,zone_polygons,source,confirmed_by_member_id,confirmed_at)
                VALUES (?,?,1,'normalized-image',1,1,'[]','{}',?,now())
                """, layout, event, chiefId);
        jdbc.update("""
                INSERT INTO safety_zone_profile(id,event_id,zone_id,layout_id,layout_revision,minimum_crew_rule,revision)
                VALUES (?,?,?,?,1,?::jsonb,1),(?,?,?,?,1,NULL,1)
                """, UUID.randomUUID(), event, zoneB, layout, minimum == null ? null : supportMinimum(request, minimum),
                UUID.randomUUID(), event, zoneA, layout);
        UUID camera = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        String coverage = """
                [{"zoneId":"%s","scope":"FULL","layoutId":"%s","layoutRevision":"1","profileRevision":"1"}]
                """.formatted(zoneA, layout);
        jdbc.update("""
                INSERT INTO safety_camera(id,event_id,name,source_binding_ref,enabled,revision,coverage,configured_by_member_id,
                                          configured_at,connection_state,stream_session_id,last_received_at,latest_observation_id,freshness)
                VALUES (?,?,'fixture','fixture',true,1,?::jsonb,?,now(),'CONNECTED',?,now(),?,'FRESH')
                """, camera, event, coverage, chiefId, session, request.observationId());
        String estimates = """
                [{"zoneId":"%s","estimatedCount":10,"qualityReasons":[]}]
                """.formatted(zoneA);
        jdbc.update("""
                INSERT INTO safety_observation(id,event_id,camera_id,camera_revision,stream_session_id,sequence,captured_at,
                    capture_time_basis,received_at,input_hash,coverage,state,freshness,estimates,model_version,processed_at,metadata_expires_at)
                VALUES (?,?,?,1,?,1,now(),'COLLECTOR_CLOCK',now(),'fixture',?::jsonb,'READY','FRESH',?::jsonb,'fixture-model',now(),now()+interval '1 hour')
                """, request.observationId(), event, camera, session, coverage, estimates);
        return new SupportFixture(request, List.of(new Candidate(haneulMember, 2, other), new Candidate(minsuMember, 2, patrol)));
    }

    private String supportMinimum(Request request, int minimum) {
        return json.createObjectNode().put("workGroupId", safety.toString()).put("minimumCount", minimum)
                .put("startAt", request.startAt().toString()).put("endAt", request.endAt().toString()).toString();
    }

    @Test void editResetsReviewAndRevisionConflictsAreReported() throws Exception {
        String id=create(supportOps());
        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie))).body());
        var stale=send(post("/api/v1/change-sets/"+id+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie));
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("changeSet");
        var edited=json.readTree(ok(send(patch("/api/v1/change-sets/"+id,"{\"rationale\":\"근거 보강\",\"expectedChangeSetRevision\":\"2\",\"expectedEventRevision\":\""+rev()+"\"}")
                .header("Cookie",chiefCookie))).body());
        assertThat(edited.get("status").asString()).isEqualTo("DRAFT");
        assertThat(edited.get("previewHash").isNull()).isTrue();
        assertThat(edited.get("diff").size()).isZero();
        assertThat(edited.get("revision").asString()).isEqualTo("3");
        var applyOld="{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"2\",\"previewHash\":\""+preview.get("previewHash").asString()+"\"}";
        error(send(post("/api/v1/change-sets/"+id+"/apply",applyOld).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),409,"INVALID_STATE");
        // 다시 미리보기한 뒤, 다른 보고 등으로 행사 revision이 바뀌면 적용은 공연 정보 충돌이다.
        var again=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"3\"}").header("Cookie",chiefCookie))).body());
        String oldRev=rev();
        jdbc.update("UPDATE event SET revision=revision+1 WHERE id=?",event);
        var conflict=send(post("/api/v1/change-sets/"+id+"/apply","{\"expectedEventRevision\":\""+oldRev+"\",\"expectedChangeSetRevision\":\"4\",\"previewHash\":\""+again.get("previewHash").asString()+"\"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        error(conflict,409,"VERSION_CONFLICT");
        assertThat(json.readTree(conflict.body()).get("details").get("target").asString()).isEqualTo("event");
        assertThat(count("SELECT count(*) FROM task WHERE event_id=?",event)).isEqualTo(1);
        // R2 근거 기록: 1·2·3·4 revision마다 한 행
        assertThat(json.readTree(ok(send(get("/api/v1/change-sets/"+id+"/evidence/1").header("Cookie",chiefCookie))).body()).get("changeSetRevision").asString()).isEqualTo("1");
        error(send(get("/api/v1/change-sets/"+id+"/evidence/99").header("Cookie",chiefCookie)),404,"NOT_FOUND");
    }

    @Test void stateChangedOutsideRevisionMakesApplyFailWithoutPartialChanges() throws Exception {
        String id=create(supportOps());
        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie))).body());
        // revision을 올리지 않은 다른 변경(박민수의 순회가 이미 끝남)
        jdbc.update("UPDATE task SET status='DONE' WHERE id=?",patrol);
        var r=send(post("/api/v1/change-sets/"+id+"/apply","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"2\",\"previewHash\":\""+preview.get("previewHash").asString()+"\"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        error(r,409,"INVALID_STATE");
        // 첫 연산(업무 추가)도 남지 않는다.
        assertThat(count("SELECT count(*) FROM task WHERE event_id=?",event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM change_set WHERE id=?::uuid",String.class,id)).isEqualTo("READY");
        assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE change_set_id=?::uuid",id)).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid",id)).isZero();
    }

    @Test void assignAndCancelNotifyOldAndNewAssignee() throws Exception {
        String ops="[{\"type\":\"ASSIGN_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"fromMemberId\":\""+minsuMember+"\",\"toMemberId\":\""+haneulMember+"\"}}]";
        String id=create(ops);
        applyAll(id,1);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,patrol)).isEqualTo(haneulMember);
        var old=json.readTree(ok(send(get("/api/v1/events/"+event+"/my-changes").header("Cookie",minsuCookie))).body()).get("items").get(0).get("targets").get(0);
        assertThat(old.get("removed").asBoolean()).isTrue();
        assertThat(old.get("changeKinds").get(0).asString()).isEqualTo("ASSIGNEE");
        // 이전 담당 기대가 틀리면 409(task)
        String wrong="[{\"type\":\"ASSIGN_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"fromMemberId\":\""+minsuMember+"\",\"toMemberId\":null}}]";
        var r=send(post("/api/v1/events/"+event+"/change-sets",createBody(wrong)).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        error(r,409,"VERSION_CONFLICT");
        assertThat(json.readTree(r.body()).get("details").get("target").asString()).isEqualTo("task");
        String cancel=create("[{\"type\":\"CANCEL_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"reason\":\"객석 B 인원 충분\"}}]");
        applyAll(cancel,1);
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?",String.class,patrol)).isEqualTo("CANCELED");
        var haneulItems=json.readTree(ok(send(get("/api/v1/events/"+event+"/my-changes").header("Cookie",haneulCookie))).body()).get("items");
        assertThat(haneulItems.get(0).get("targets").get(0).get("changeKinds").get(0).asString()).isEqualTo("CANCELED");
        assertThat(haneulItems.size()).isEqualTo(2);
    }

    @Test void groupInstructionAndChecklistOperations() throws Exception {
        jdbc.update("UPDATE task SET checklist='[{\"key\":\"door\",\"text\":\"문 확인\",\"required\":true,\"checked\":true,\"checkedAt\":\"2026-11-14T09:10:00Z\",\"checkedByMemberId\":null}]'::jsonb WHERE id=?",patrol);
        String ops="[{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"checklist\":[{\"key\":\"door\",\"text\":\"문 확인\",\"required\":true},{\"key\":\"exit\",\"text\":\"비상구 확인\",\"required\":false}]}},"
                +"{\"type\":\"UPSERT_WORK_GROUP\",\"payload\":{\"tempId\":\"tmp-g\",\"name\":\"안내\",\"instructions\":\"입구 안내\"}},"
                +"{\"type\":\"UPDATE_INSTRUCTIONS\",\"payload\":{\"scope\":\"EVENT\",\"workGroupId\":null,\"body\":\"비상시 객석 A 출구 사용\"}},"
                +"{\"type\":\"DEACTIVATE_WORK_GROUP\",\"payload\":{\"workGroupId\":\""+sound+"\"}}]";
        applyAll(create(ops),1);
        var list=json.readTree(jdbc.queryForObject("SELECT checklist::text FROM task WHERE id=?",String.class,patrol));
        assertThat(list.get(0).get("checked").asBoolean()).isTrue();      // 남은 항목은 수행값 유지
        assertThat(list.get(1).get("checked").asBoolean()).isFalse();     // 새 항목은 미체크
        assertThat(jdbc.queryForObject("SELECT instructions FROM event WHERE id=?",String.class,event)).isEqualTo("비상시 객석 A 출구 사용");
        assertThat(count("SELECT count(*) FROM work_group WHERE event_id=? AND name='안내' AND active",event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active FROM work_group WHERE id=?",Boolean.class,sound)).isFalse();
        // 팀원이 있는 그룹은 비활성으로 못 바꾼다.
        var r=send(post("/api/v1/events/"+event+"/change-sets",createBody("[{\"type\":\"DEACTIVATE_WORK_GROUP\",\"payload\":{\"workGroupId\":\""+safety+"\"}}]"))
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        error(r,409,"INVALID_STATE");
    }

    @Test void rejectsAssigningUnavailableMemberButAllowsReserve() throws Exception {
        String ops="[{\"type\":\"ASSIGN_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"fromMemberId\":\""+minsuMember+"\",\"toMemberId\":\""+haneulMember+"\"}}]";
        // 미리보기까지 끝난 뒤 받는 사람이 가용 아님이 되면 적용에서 막고 업무는 그대로다.
        String id=create(ops);
        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}")
                .header("Cookie",chiefCookie))).body());
        jdbc.update("UPDATE member SET available=false WHERE id=?",haneulMember);
        var applied=send(post("/api/v1/change-sets/"+id+"/apply","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"2\",\"previewHash\":\""
                +preview.get("previewHash").asString()+"\"}").header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        error(applied,422,"INVALID_REFERENCE");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,patrol)).isEqualTo(minsuMember);
        // 새 변경안도 만들 때부터 막는다.
        var created=send(post("/api/v1/events/"+event+"/change-sets",createBody(ops)).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        error(created,422,"INVALID_REFERENCE");
        assertThat(json.readTree(created.body()).get("details").get("field").asString()).isEqualTo("operations[0].payload.toMemberId");
        // 예비는 가용이면 총괄 승인으로 투입할 수 있다(be/04).
        jdbc.update("UPDATE member SET available=true, is_reserve=true WHERE id=?",haneulMember);
        applyAll(create(ops),1);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,patrol)).isEqualTo(haneulMember);
    }

    @Test void rejectsOverlapReferencesUnsupportedAndBadInput() throws Exception {
        String overlap="[{\"type\":\"ADD_TASK\",\"payload\":{\"workGroupId\":\""+safety+"\",\"bundleKey\":\"x\",\"zoneId\":\""+zoneA+"\",\"programId\":null,"
                +"\"title\":\"겹침\",\"startAt\":\"2026-11-14T10:00:00Z\",\"endAt\":\"2026-11-14T10:30:00Z\",\"steps\":[\"a\"],\"checklist\":[],"
                +"\"tempId\":\"tmp-x\",\"assigneeId\":\""+minsuMember+"\"}}]";
        error(send(post("/api/v1/events/"+event+"/change-sets",createBody(overlap)).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),422,"TIME_OVERLAP");
        var ref=send(post("/api/v1/events/"+event+"/change-sets",createBody(overlap.replace(zoneA.toString(),otherZone.toString()).replace(minsuMember.toString(),haneulMember.toString())))
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        error(ref,422,"INVALID_REFERENCE");
        assertThat(json.readTree(ref.body()).get("details").get("field").asString()).isEqualTo("operations[0].payload.zoneId");
        String reserve="[{\"type\":\"RESERVE_EQUIPMENT\",\"payload\":{}}]";
        error(send(post("/api/v1/events/"+event+"/change-sets",createBody(reserve)).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        for (String bad:List.of("{\"mode\":\"MANUAL\",\"operations\":[]}", "{\"mode\":\"X\",\"operations\":"+supportOps()+"}",
                "{\"mode\":\"MANUAL\",\"operations\":"+supportOps()+",\"extra\":1}",
                "{\"mode\":\"MANUAL\",\"operations\":[{\"type\":\"CANCEL_TASK\",\"payload\":{\"taskId\":\""+patrol+"\"}}]}"))
            error(send(post("/api/v1/events/"+event+"/change-sets",bad).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(post("/api/v1/events/"+event+"/change-sets",createBody(supportOps())).header("Cookie",haneulCookie).header("Idempotency-Key",UUID.randomUUID().toString())),403,"FORBIDDEN");
        error(send(post("/api/v1/events/"+event+"/change-sets",createBody(supportOps())).header("Cookie",login(outsider)).header("Idempotency-Key",UUID.randomUUID().toString())),404,"NOT_FOUND");
        // AI 모드는 이 행사의 끝난 변경 생성 작업이 있어야 한다(#343).
        var ai=send(post("/api/v1/events/"+event+"/change-sets","{\"mode\":\"AI\",\"operations\":"+supportOps()+",\"sourceJobId\":\""+UUID.randomUUID()+"\"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        error(ai,422,"INVALID_REFERENCE");
    }

    @Test void discardListAndPaging() throws Exception {
        List<String> ids=new ArrayList<>();
        for (int i=0;i<5;i++) ids.add(0,create("[{\"type\":\"UPDATE_INSTRUCTIONS\",\"payload\":{\"scope\":\"EVENT\",\"workGroupId\":null,\"body\":\"안내 "+i+"\"}}]"));
        var discarded=json.readTree(ok(send(post("/api/v1/change-sets/"+ids.get(0)+"/discard","{\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie))).body());
        assertThat(discarded.get("status").asString()).isEqualTo("DISCARDED");
        error(send(post("/api/v1/change-sets/"+ids.get(0)+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"2\"}").header("Cookie",chiefCookie)),409,"INVALID_STATE");
        var seen=new ArrayList<String>(); String cursor=null; int pages=0;
        do {
            var page=json.readTree(ok(send(get("/api/v1/events/"+event+"/change-sets?limit=2"+(cursor==null?"":"&cursor="+cursor)).header("Cookie",chiefCookie))).body());
            for (JsonNode n:page.get("items")) seen.add(n.get("id").asString());
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asString();
            pages++;
        } while (cursor!=null && pages<10);
        assertThat(seen).containsExactlyElementsOf(ids); // 최신 생성순
        assertThat(pages).isEqualTo(3);
        error(send(get("/api/v1/events/"+event+"/change-sets").header("Cookie",haneulCookie)),403,"FORBIDDEN");
    }

    @Test void rejectsNormalizedDuplicateGroupNames() throws Exception {
        group(event,"Safety A");
        String ops="""
                [{"type":"UPSERT_WORK_GROUP","payload":{"tempId":"tmp-duplicate","name":"sAfEtY a","instructions":""}}]
                """;
        error(send(post("/api/v1/events/"+event+"/change-sets",createBody(ops))
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"Straße,STRASSE", "ΟΣ,οσ"})
    void rejectsUnicodeCasefoldEquivalentGroupNames(String existing, String incoming) throws Exception {
        group(event,existing);
        String ops="[{\"type\":\"UPSERT_WORK_GROUP\",\"payload\":{\"tempId\":\"tmp-unicode-name\",\"name\":\""+incoming+"\",\"instructions\":\"\"}}]";
        error(send(post("/api/v1/events/"+event+"/change-sets",createBody(ops)).header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
    }

    @Test void rechecksChiefAfterWaitingForEventLock() throws Exception {
        var request=post("/api/v1/events/"+event+"/change-sets", """
                {"mode":"MANUAL","operations":[{"type":"UPDATE_INSTRUCTIONS","payload":{"scope":"EVENT","workGroupId":null,"body":"old chief"}}]}
                """).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()).build();
        try (var locked=jdbc.getDataSource().getConnection()) {
            locked.setAutoCommit(false);
            try (var stmt=locked.prepareStatement("SELECT 1 FROM event WHERE id=? FOR UPDATE")) {
                stmt.setObject(1,event); stmt.executeQuery().close();
            }
            int pid;
            try (var stmt=locked.createStatement();var rs=stmt.executeQuery("SELECT pg_backend_pid()")) {
                rs.next();pid=rs.getInt(1);
            }
            var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            try {
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (count("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",pid)==0
                        && System.nanoTime()<until && !pending.isDone()) Thread.sleep(20);
                assertThat(count("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",pid))
                        .as("변경안 요청이 행사 잠금을 기다리는 상태").isPositive();
                try (var stmt=locked.prepareStatement("UPDATE member SET event_role='WORKER',work_group_id=? WHERE event_id=? AND account_id=?")) {
                    stmt.setObject(1,safety);stmt.setObject(2,event);stmt.setObject(3,chief);stmt.executeUpdate();
                }
                try (var stmt=locked.prepareStatement("UPDATE member SET event_role='CHIEF' WHERE id=?")) {
                    stmt.setObject(1,haneulMember);stmt.executeUpdate();
                }
                locked.commit();
                error(pending.get(5,java.util.concurrent.TimeUnit.SECONDS),403,"FORBIDDEN");
            } finally {
                locked.rollback();
                pending.get(5,java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test void rejectsTaskInDifferentZoneFromItsProgram() throws Exception {
        UUID program=UUID.randomUUID();
        jdbc.update("INSERT INTO program (id,event_id,name,zone_id,start_at,end_at) VALUES (?,?,?, ?,?::timestamptz,?::timestamptz)",
                program,event,"공연",zoneB,"2026-11-14T09:00:00Z","2026-11-14T10:00:00Z");
        try {
            String ops=supportOps().replace("\"programId\":null","\"programId\":\""+program+"\"");
            error(send(post("/api/v1/events/"+event+"/change-sets",createBody(ops))
                    .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
        } finally { jdbc.update("DELETE FROM program WHERE id=?",program); }
    }

    @Test void rejectsTaskOutsideItsProgramTime() throws Exception {
        UUID program=UUID.randomUUID();
        jdbc.update("INSERT INTO program (id,event_id,name,zone_id,start_at,end_at) VALUES (?,?,?, ?,?::timestamptz,?::timestamptz)",
                program,event,"공연",zoneA,"2026-11-14T07:00:00Z","2026-11-14T08:00:00Z");
        try {
            String ops=supportOps().replace("\"programId\":null","\"programId\":\""+program+"\"");
            error(send(post("/api/v1/events/"+event+"/change-sets",createBody(ops))
                    .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
        } finally { jdbc.update("DELETE FROM program WHERE id=?",program); }
    }

    @Test void validatesLinkedTasksAfterProgramChangesAndAllowsCoordinatedMoves() throws Exception {
        UUID program=UUID.randomUUID();
        jdbc.update("INSERT INTO program (id,event_id,name,zone_id,start_at,end_at) VALUES (?,?,?, ?,?::timestamptz,?::timestamptz)",
                program,event,"공연",zoneB,"2026-11-14T09:00:00Z","2026-11-14T12:00:00Z");
        jdbc.update("UPDATE task SET program_id=? WHERE id=?",program,patrol);
        try {
            String programMove="{\"type\":\"UPDATE_PROGRAM\",\"payload\":{\"programId\":\""+program+"\",\"zoneId\":\""+zoneA+"\"}}";
            error(send(post("/api/v1/events/"+event+"/change-sets",createBody("["+programMove+"]"))
                    .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
            String taskMove="{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"zoneId\":\""+zoneA+"\"}}";
            String id=create("["+programMove+","+taskMove+"]");
            applyAll(id,1);
            assertThat(jdbc.queryForObject("SELECT zone_id FROM task WHERE id=?",UUID.class,patrol)).isEqualTo(zoneA);
        } finally {
            jdbc.update("UPDATE task SET program_id=NULL WHERE id=?",patrol);
            jdbc.update("DELETE FROM program WHERE id=?",program);
        }
    }

    @Test void rejectsBundleAcrossDifferentWorkGroups() throws Exception {
        jdbc.update("UPDATE task SET assignee_id=NULL,first_assigned_at=NULL WHERE id=?",patrol);
        String ops=supportOps().replace("\"bundleKey\":\"support-1\"","\"bundleKey\":\"b1\"")
                .replace(safety.toString(),sound.toString()).replace("\"assigneeId\":\""+haneulMember+"\"","\"assigneeId\":null");
        error(send(post("/api/v1/events/"+event+"/change-sets",createBody(ops))
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
    }

    @Test void rejectsDeactivatingGroupWithUnfinishedTask() throws Exception {
        task(event,sound,null,zoneA,"미배정 음향 업무","2026-11-14T09:00:00Z","2026-11-14T10:00:00Z");
        String ops="[{\"type\":\"DEACTIVATE_WORK_GROUP\",\"payload\":{\"workGroupId\":\""+sound+"\"}}]";
        error(send(post("/api/v1/events/"+event+"/change-sets",createBody(ops))
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),409,"INVALID_STATE");
    }

    @Test void myChangesUseApplyOrderRatherThanDraftCreationOrder() throws Exception {
        String first=create("[{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"steps\":[\"먼저 만든 초안\"]}}]");
        String second=create("[{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"steps\":[\"나중에 만든 초안\"]}}]");
        applyAll(second,1);
        applyAll(first,1);
        String firstAck=jdbc.queryForObject("SELECT id::text FROM change_acknowledgment WHERE change_set_id=?::uuid AND member_id=?",String.class,first,minsuMember);
        String secondAck=jdbc.queryForObject("SELECT id::text FROM change_acknowledgment WHERE change_set_id=?::uuid AND member_id=?",String.class,second,minsuMember);
        var page=json.readTree(ok(send(get("/api/v1/events/"+event+"/my-changes?limit=1").header("Cookie",minsuCookie))).body());
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(firstAck);
        var next=json.readTree(ok(send(get("/api/v1/events/"+event+"/my-changes?limit=1&cursor="+page.get("nextCursor").asString()).header("Cookie",minsuCookie))).body());
        assertThat(next.get("items").get(0).get("id").asString()).isEqualTo(secondAck);
        assertThat(next.get("nextCursor").isNull()).isTrue();
    }

    @Test void endedEventRejectsNewAcknowledgmentButAllowsExistingResultRecovery() throws Exception {
        String id=create(supportOps());
        applyAll(id,1);
        var haneulAck=json.readTree(ok(send(get("/api/v1/events/"+event+"/my-changes").header("Cookie",haneulCookie))).body()).get("items").get(0);
        var minsuAck=json.readTree(ok(send(get("/api/v1/events/"+event+"/my-changes").header("Cookie",minsuCookie))).body()).get("items").get(0);
        String recovered=ok(send(post("/api/v1/change-acknowledgments/"+haneulAck.get("id").asString()+"/ack",
                "{\"targetsHash\":\""+haneulAck.get("targetsHash").asString()+"\"}").header("Cookie",haneulCookie))).body();
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        error(send(post("/api/v1/change-acknowledgments/"+minsuAck.get("id").asString()+"/ack",
                "{\"targetsHash\":\""+minsuAck.get("targetsHash").asString()+"\"}").header("Cookie",minsuCookie)),409,"INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT acknowledged_at FROM change_acknowledgment WHERE id=?::uuid",java.sql.Timestamp.class,minsuAck.get("id").asString())).isNull();
        var replay=ok(send(post("/api/v1/change-acknowledgments/"+haneulAck.get("id").asString()+"/ack",
                "{\"targetsHash\":\""+haneulAck.get("targetsHash").asString()+"\"}").header("Cookie",haneulCookie)));
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(recovered));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ADD_TASK","UPDATE_TASK","PROGRAM_START","PROGRAM_END"})
    void rejectsChangesOutsideEventWindows(String type) throws Exception {
        jdbc.update("UPDATE event SET prep_start_at='2026-11-14T08:00:00Z',open_at='2026-11-14T09:00:00Z',close_at='2026-11-14T13:00:00Z',teardown_end_at='2026-11-14T14:00:00Z' WHERE id=?",event);
        UUID program=UUID.randomUUID();
        jdbc.update("INSERT INTO program (id,event_id,zone_id,name,start_at,end_at) VALUES (?,?,?,'공연','2026-11-14T10:00:00Z','2026-11-14T11:00:00Z')",program,event,zoneA);
        String ops=switch(type) {
            case "ADD_TASK" -> supportOps().replace("2026-11-14T09:25:00Z","2026-11-14T07:00:00Z");
            case "UPDATE_TASK" -> "[{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"endAt\":\"2026-11-14T15:00:00Z\"}}]";
            case "PROGRAM_START" -> "[{\"type\":\"UPDATE_PROGRAM\",\"payload\":{\"programId\":\""+program+"\",\"startAt\":\"2026-11-14T08:30:00Z\"}}]";
            default -> "[{\"type\":\"UPDATE_PROGRAM\",\"payload\":{\"programId\":\""+program+"\",\"endAt\":\"2026-11-14T13:30:00Z\"}}]";
        };
        error(send(post("/api/v1/events/"+event+"/change-sets",createBody(ops)).header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
    }

    @Test void acceptsExactEventWindowBoundaries() throws Exception {
        jdbc.update("UPDATE event SET prep_start_at='2026-11-14T08:00:00Z',open_at='2026-11-14T09:00:00Z',close_at='2026-11-14T13:00:00Z',teardown_end_at='2026-11-14T14:00:00Z' WHERE id=?",event);
        UUID program=UUID.randomUUID();
        jdbc.update("INSERT INTO program (id,event_id,zone_id,name,start_at,end_at) VALUES (?,?,?,'공연','2026-11-14T10:00:00Z','2026-11-14T11:00:00Z')",program,event,zoneA);
        String ops="[{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"startAt\":\"2026-11-14T08:00:00Z\",\"endAt\":\"2026-11-14T14:00:00Z\"}},"
                +"{\"type\":\"UPDATE_PROGRAM\",\"payload\":{\"programId\":\""+program+"\",\"startAt\":\"2026-11-14T09:00:00Z\",\"endAt\":\"2026-11-14T13:00:00Z\"}}]";
        applyAll(create(ops),1);
    }

    @Test void ack242AddedThenCanceledKeepsBothKindsThroughConfirmation() throws Exception {
        var operations=json.createArrayNode();
        operations.add(json.readTree(supportOps()).get(0));
        String id=create(json.writeValueAsString(operations));
        var firstPreview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview",
                "{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}")
                .header("Cookie",chiefCookie))).body());
        String taskId=firstPreview.get("diff").get(0).get("targetId").asString();
        operations.add(json.readTree("{\"type\":\"CANCEL_TASK\",\"payload\":{\"taskId\":\""+taskId+"\",\"reason\":\"추가 직후 취소\"}}"));
        var edit=json.createObjectNode().put("expectedEventRevision",rev()).put("expectedChangeSetRevision","2");
        edit.set("operations",operations);
        var edited=json.readTree(ok(send(patch("/api/v1/change-sets/"+id,json.writeValueAsString(edit))
                .header("Cookie",chiefCookie))).body());
        assertThat(edited.get("status").asString()).isEqualTo("DRAFT");
        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview",
                "{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"3\"}")
                .header("Cookie",chiefCookie))).body());
        assertThat(preview.get("status").asString()).isEqualTo("READY");
        assertThat(preview.get("diff").size()).isEqualTo(2);
        assertThat(preview.get("diff").get(0).get("type").asString()).isEqualTo("ADD_TASK");
        assertThat(preview.get("diff").get(1).get("type").asString()).isEqualTo("CANCEL_TASK");
        assertThat(preview.get("diff").get(1).get("targetId").asString()).isEqualTo(taskId);
        assertThat(count("SELECT count(*) FROM task WHERE id=?::uuid",taskId)).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid",id)).isZero();
        assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE change_set_id=?::uuid",id)).isZero();
        assertThat(count("SELECT count(*) FROM operation_log WHERE change_set_id=?::uuid",id)).isZero();

        var applied=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/apply",
                "{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"4\",\"previewHash\":\""+preview.get("previewHash").asString()+"\"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()))).body());
        assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
        assertThat(applied.get("diff")).isEqualTo(preview.get("diff"));
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?::uuid",String.class,taskId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?::uuid",UUID.class,taskId)).isEqualTo(haneulMember);
        assertThat(count("SELECT count(*) FROM operation_log WHERE change_set_id=?::uuid",id)).isEqualTo(2);
        var items=ack242Mine(haneulCookie);
        assertThat(items.size()).isEqualTo(1);
        var ack=items.get(0);
        System.out.printf("ACK242 ADD_CANCEL state=%s targets=%s hash=%s%n",ack.get("state"),ack.get("targets"),ack.get("targetsHash"));
        assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid AND acknowledgment_id=?::uuid AND member_id=?",
                id,ack.get("id").asString(),haneulMember)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid",id)).isEqualTo(1);
        assertThat(ack.get("state").asString()).isEqualTo("PENDING");
        assertThat(ack.get("participationRevision").asString()).isEqualTo("2");
        // 키는 canonical 순서로 작성한다. production 해시 함수를 재사용하지 않는다.
        var target=json.createObjectNode();
        target.putArray("changeKinds").add("ADDED").add("CANCELED");
        target.put("changedAt",jdbc.queryForObject("SELECT applied_at FROM change_set WHERE id=?::uuid",java.sql.Timestamp.class,id).toInstant().toString());
        target.put("removed",true).put("taskId",taskId).put("title","객석 A 안전 지원");
        var expectedTargets=json.createArrayNode().add(target);
        assertThat(ack.get("targets")).isEqualTo(expectedTargets);
        String expectedHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(expectedTargets).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(ack.get("targetsHash").asString()).isEqualTo(expectedHash);
        assertThat(json.readTree((String)ack242Row(ack).get("targets"))).isEqualTo(expectedTargets);
        assertThat(ack242Row(ack).get("targets_hash")).isEqualTo(expectedHash);
        assertThat(ack242Row(ack).get("acknowledged_at")).isNull();
        assertThat(ack242Row(ack).get("acknowledged_targets")).isNull();
        var done=json.readTree(ok(send(ack242Post(ack,haneulCookie))).body());
        assertThat(done.get("state").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(done.get("targets")).isEqualTo(expectedTargets);
        assertThat(done.get("targetsHash").asString()).isEqualTo(expectedHash);
        var confirmed=ack242Row(ack);
        assertThat(done.get("acknowledgedAt").asString()).isEqualTo(((java.sql.Timestamp)confirmed.get("acknowledged_at")).toInstant().toString());
        assertThat(json.readTree((String)confirmed.get("acknowledged_targets"))).isEqualTo(expectedTargets);
        assertThat(ack242Mine(haneulCookie).get(0)).isEqualTo(done);
        assertThat(json.readTree(ok(send(ack242Post(ack,haneulCookie))).body())).isEqualTo(done);
        assertThat(ack242Row(ack)).isEqualTo(confirmed);
    }

    /** #242 A: 두 대상 중 하나가 다시 바뀌면 과거 화면의 확인은 거부되어야 한다. 현재 결함의 회귀 시험. */
    @Test void ack242RejectsOldHashAfterOneOfTwoTargetsChanges() throws Exception {
        UUID secondTask=task(event,safety,minsuMember,zoneA,"다음 순회","2026-11-14T12:30:00Z","2026-11-14T13:00:00Z");
        String first=create("["+ack242Step(patrol,"A 첫 업무")+","+ack242Step(secondTask,"A 둘째 업무")+"]");
        applyAll(first,1);
        var ack=ack242Mine(minsuCookie).get(0);
        assertThat(ack.get("targets").size()).isEqualTo(2);
        assertThat(ack.get("state").asString()).isEqualTo("PENDING");
        assertThat(ack242Row(ack).get("acknowledged_at")).isNull();
        assertThat(ack242Row(ack).get("acknowledged_targets")).isNull();

        String second=create("["+ack242Step(patrol,"B 첫 업무 재변경")+"]");
        applyAll(second,1);
        assertThat(json.readTree(jdbc.queryForObject("SELECT steps::text FROM task WHERE id=?",String.class,patrol)).get(0).asString())
                .isEqualTo("B 첫 업무 재변경");
        assertThat(json.readTree(jdbc.queryForObject("SELECT steps::text FROM task WHERE id=?",String.class,secondTask)).get(0).asString())
                .isEqualTo("A 둘째 업무");
        assertThat(ack242Mine(minsuCookie).size()).isEqualTo(2);
        Map<String,Object> before=ack242Row(ack); // B의 정당한 갱신 이후, 실패 POST 직전 상태와 비교한다.
        var response=send(ack242Post(ack,minsuCookie));
        Map<String,Object> after=ack242Row(ack);
        System.out.printf("ACK242 A http=%d body=%s before=%s after=%s%n",response.statusCode(),response.body(),before,after);
        // 오류 code는 계약 내 INVALID_STATE/VERSION_CONFLICT 충돌이 있어 여기서 결정하지 않는다.
        org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).as("#242 오래된 대상 확인 거부").isEqualTo(409);
            softly.assertThat(after).as("거부한 요청은 확인 시각·대상을 저장하지 않음").isEqualTo(before);
        });
    }

    /** #242 C: 새 변경이 과거 실제 확인 사실을 덮어쓰거나 새 확인까지 완료하면 안 된다. */
    @Test void ack242PreservesConfirmedHistoryAndCreatesNewPendingTarget() throws Exception {
        var ack=ack242Pending();
        ok(send(ack242Post(ack,minsuCookie)));
        Map<String,Object> confirmed=ack242Row(ack);
        assertThat(confirmed.get("acknowledged_at")).isNotNull();
        assertThat(json.readTree((String)confirmed.get("acknowledged_targets")))
                .isEqualTo(json.readTree((String)confirmed.get("targets")));
        String next=create("["+ack242Step(patrol,"확인 이후 새 절차")+"]");
        applyAll(next,1);
        assertThat(ack242Row(ack)).isEqualTo(confirmed);
        var items=ack242Mine(minsuCookie);
        assertThat(items.size()).isEqualTo(2);
        var latest=items.get(0);
        assertThat(latest.get("id").asString()).isNotEqualTo(ack.get("id").asString());
        assertThat(latest.get("state").asString()).isEqualTo("PENDING");
        assertThat(latest.get("acknowledgedAt").isNull()).isTrue();
        assertThat(latest.get("targets").get(0).get("taskId").asString()).isEqualTo(patrol.toString());
        assertThat(items.get(1).get("state").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack242Row(latest).get("acknowledged_targets")).isNull();
    }

    @Test void ack242RejectsAnotherEventAccountWithoutDatabaseChanges() throws Exception {
        var ack=ack242Pending();
        var before=ack242Row(ack);
        String otherCookie=login(outsider); // outsider는 다른 행사의 총괄이며 이 행사의 Member가 아니다.
        error(send(get("/api/v1/events/"+event+"/my-changes").header("Cookie",otherCookie)),404,"NOT_FOUND");
        error(send(ack242Post(ack,otherCookie)),404,"NOT_FOUND");
        assertThat(ack242Row(ack)).isEqualTo(before);
    }

    @Test void ack242RejectsAnotherMemberWithoutDatabaseChanges() throws Exception {
        var ack=ack242Pending();
        var before=ack242Row(ack);
        error(send(ack242Post(ack,haneulCookie)),404,"NOT_FOUND");
        assertThat(ack242Row(ack)).isEqualTo(before);
    }

    @Test void ack242RejectsOldRoundAfterActualHttpRejoin() throws Exception {
        var ack=ack242Pending();
        var before=ack242Row(ack);
        // 탈퇴 API가 없어 이탈 상태만 SQL fixture로 만든다. 재참여는 실제 공개 Join API를 사용한다.
        String invite="ack242-"+UUID.randomUUID();
        jdbc.update("UPDATE event SET invite_token_hash=?,total_capacity=31 WHERE id=?",
                com.ssafy.scenecrew.invite.service.InviteJoinService.tokenHash(invite),event);
        jdbc.update("UPDATE member SET active=false WHERE id=?",minsuMember);
        jdbc.update("UPDATE task SET assignee_id=NULL WHERE event_id=? AND assignee_id=? AND status='WAITING'",event,minsuMember);
        jdbc.update("UPDATE event SET revision=revision+1 WHERE id=?",event);
        error(send(ack242Post(ack,minsuCookie)),404,"NOT_FOUND");
        var joined=json.readTree(ok(send(post("/api/v1/invites/"+invite+"/join",
                "{\"workGroupId\":\""+safety+"\",\"available\":true,\"isReserve\":false,\"expectedEventRevision\":\""+rev()+"\"}")
                .header("Cookie",minsuCookie).header("X-SceneCrew-CSRF","1")
                .header("Idempotency-Key",UUID.randomUUID().toString()))).body());
        assertThat(joined.get("participation").get("memberId").asString()).isEqualTo(minsuMember.toString());
        assertThat(joined.get("participation").get("participationRevision").asString()).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT participation_revision FROM member WHERE id=?",Long.class,minsuMember)).isEqualTo(3L);
        assertThat(ack242Mine(minsuCookie).size()).isZero();
        error(send(ack242Post(ack,minsuCookie)),404,"NOT_FOUND");
        assertThat(ack242Row(ack)).isEqualTo(before);
    }

    @Test void ack242ConcurrentPostsWaitOnRealEventLockAndReturnOneConfirmation() throws Exception {
        var ack=ack242Pending();
        java.util.concurrent.CompletableFuture<HttpResponse<String>> first=null,second=null;
        HttpResponse<String> firstResult,secondResult;
        try (var locked=jdbc.getDataSource().getConnection()) {
            locked.setAutoCommit(false);
            try (var stmt=locked.prepareStatement("SELECT 1 FROM event WHERE id=? FOR UPDATE")) {
                stmt.setObject(1,event);stmt.executeQuery().close();
            }
            int owner;
            try (var stmt=locked.createStatement();var result=stmt.executeQuery("SELECT pg_backend_pid()")) {
                result.next();owner=result.getInt(1);
            }
            assertThat(ack242WaitingPosts()).isZero();
            try {
                first=client.sendAsync(ack242Post(ack,minsuCookie).build(),HttpResponse.BodyHandlers.ofString());
                ack242AwaitWaitingPosts(1);
                second=client.sendAsync(ack242Post(ack,minsuCookie).build(),HttpResponse.BodyHandlers.ofString());
                ack242AwaitWaitingPosts(2);
                long direct=count("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",owner);
                assertThat(direct).isPositive();
                assertThat(first.isDone()).isFalse();
                assertThat(second.isDone()).isFalse();
                assertThat(ack242Row(ack).get("acknowledged_at")).isNull();
                System.out.printf("ACK242 parallel waitingPosts=%d directBlockedByFixture=%d%n",ack242WaitingPosts(),direct);
                locked.commit();
                firstResult=first.get(15,java.util.concurrent.TimeUnit.SECONDS);
                secondResult=second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                locked.rollback();
                // 잠금 해제 후 모든 HTTP 요청을 회수해 cleanup과 늦은 쓰기의 경합을 피한다.
                if(first!=null) first.get(15,java.util.concurrent.TimeUnit.SECONDS);
                if(second!=null) second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        var one=json.readTree(ok(firstResult).body());
        var two=json.readTree(ok(secondResult).body());
        assertThat(one).isEqualTo(two);
        assertThat(one.get("state").asString()).isEqualTo("ACKNOWLEDGED");
        var stored=ack242Row(ack);
        assertThat(stored.get("acknowledged_at")).isNotNull();
        assertThat(json.readTree((String)stored.get("acknowledged_targets")))
                .isEqualTo(json.readTree((String)stored.get("targets")));
        assertThat(one.get("acknowledgedAt").asString())
                .isEqualTo(((java.sql.Timestamp)stored.get("acknowledged_at")).toInstant().toString());
        assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE id=?::uuid",ack.get("id").asString())).isEqualTo(1);
    }

    @Test void ack242SameHashReplayPreservesTimestampTargetsAndResponse() throws Exception {
        var ack=ack242Pending();
        var first=json.readTree(ok(send(ack242Post(ack,minsuCookie))).body());
        var stored=ack242Row(ack);
        assertThat(stored.get("acknowledged_at")).isNotNull();
        var replay=json.readTree(ok(send(ack242Post(ack,minsuCookie))).body());
        assertThat(replay).isEqualTo(first);
        assertThat(ack242Row(ack)).isEqualTo(stored);
    }

    @Test void ack242WrongHashPreservesPendingAndConfirmedDatabaseRows() throws Exception {
        var ack=ack242Pending();
        var before=ack242Row(ack);
        error(send(post("/api/v1/change-acknowledgments/"+ack.get("id").asString()+"/ack",
                "{\"targetsHash\":\"wrong\"}").header("Cookie",minsuCookie)),409,"INVALID_STATE");
        assertThat(ack242Row(ack)).isEqualTo(before);
        ok(send(ack242Post(ack,minsuCookie)));
        var confirmed=ack242Row(ack);
        error(send(post("/api/v1/change-acknowledgments/"+ack.get("id").asString()+"/ack",
                "{\"targetsHash\":\"wrong\"}").header("Cookie",minsuCookie)),409,"INVALID_STATE");
        assertThat(ack242Row(ack)).isEqualTo(confirmed);
    }

    @Test void ack242UnauthenticatedGetAndPostDoNotChangeDatabase() throws Exception {
        var ack=ack242Pending();
        var before=ack242Row(ack);
        error(send(get("/api/v1/events/"+event+"/my-changes")),401,"UNAUTHENTICATED");
        error(send(post("/api/v1/change-acknowledgments/"+ack.get("id").asString()+"/ack",
                "{\"targetsHash\":\""+ack.get("targetsHash").asString()+"\"}")),401,"UNAUTHENTICATED");
        assertThat(ack242Row(ack)).isEqualTo(before);
    }

    @Test void ack242PartialReplacementKeepsRemainingTaskConfirmable() throws Exception {
        UUID t2=task(event,safety,minsuMember,zoneA,"다음 순회","2026-11-14T12:30:00Z","2026-11-14T13:00:00Z");
        applyAll(create("["+ack242Step(patrol,"A1")+","+ack242Step(t2,"A2")+"]"),1);
        var old=ack242Mine(minsuCookie).get(0);
        applyAll(create("["+ack242Step(patrol,"B1")+"]"),1);
        var items=ack242Mine(minsuCookie);
        var remaining=items.get(1);
        assertThat(remaining.get("id")).isEqualTo(old.get("id"));
        assertThat(remaining.get("targets").size()).isEqualTo(1);
        assertThat(remaining.get("targets").get(0).get("taskId").asString()).isEqualTo(t2.toString());
        assertThat(remaining.get("targetsHash")).isNotEqualTo(old.get("targetsHash"));
        assertThat(items.get(0).get("targets").get(0).get("taskId").asString()).isEqualTo(patrol.toString());
        assertThat(json.readTree(ok(send(ack242Post(remaining,minsuCookie))).body()).get("state").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack242Row(items.get(0)).get("acknowledged_at")).isNull();
    }

    @Test void ack242AllReplacedTargetsCannotBeAcknowledgedEvenWithCurrentHash() throws Exception {
        var old=ack242Pending();
        applyAll(create("["+ack242Step(patrol,"새 절차")+"]"),1);
        var empty=ack242Mine(minsuCookie).get(1);
        assertThat(empty.get("state").asString()).isEqualTo("NO_CURRENT_TARGETS");
        assertThat(empty.get("targets").size()).isZero();
        var before=ack242Row(empty);
        error(send(ack242Post(old,minsuCookie)),409,"INVALID_STATE");
        error(send(ack242Post(empty,minsuCookie)),409,"INVALID_STATE");
        assertThat(ack242Row(empty)).isEqualTo(before);
    }

    @Test void ack242UnrelatedMemberChangeAndNoOpKeepOriginalHash() throws Exception {
        var old=ack242Pending();var before=ack242Row(old);
        UUID t9=task(event,safety,haneulMember,zoneA,"다른 팀원 업무","2026-11-14T12:30:00Z","2026-11-14T13:00:00Z");
        applyAll(create("["+ack242Step(t9,"다른 업무 변경")+"]"),1);
        assertThat(ack242Row(old)).isEqualTo(before);
        applyAll(create("["+ack242Step(patrol,"#242 확인 대상 절차")+"]"),1);
        assertThat(ack242Row(old)).isEqualTo(before);
        assertThat(ack242Mine(minsuCookie).size()).isEqualTo(1);
        ok(send(ack242Post(old,minsuCookie)));
    }

    @Test void ack242RemovedNoticeSurvivesChangesOnlyAffectingNewAssignee() throws Exception {
        applyAll(create("[{\"type\":\"ASSIGN_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"fromMemberId\":\""+minsuMember+"\",\"toMemberId\":\""+haneulMember+"\"}}]"),1);
        var removed=ack242Mine(minsuCookie).get(0);var before=ack242Row(removed);
        assertThat(removed.get("targets").get(0).get("removed").asBoolean()).isTrue();
        applyAll(create("["+ack242Step(patrol,"새 담당자의 절차")+"]"),1);
        assertThat(ack242Row(removed)).isEqualTo(before);
        ok(send(ack242Post(removed,minsuCookie)));
    }

    @Test void ack242DirectReleaseChangesHashAndPreservesMinimalRemovalNotice() throws Exception {
        var old=ack242Pending();
        ack242Transfer(chiefCookie,minsuMember,sound);
        ack242Transfer(minsuCookie,haneulMember,sound);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,patrol)).isNull();
        var notice=ack242Mine(minsuCookie).get(0);
        assertThat(notice.get("id")).isEqualTo(old.get("id"));
        assertThat(notice.get("targetsHash")).isNotEqualTo(old.get("targetsHash"));
        assertThat(notice.get("targets").get(0).get("removed").asBoolean()).isTrue();
        assertThat(notice.get("targets").get(0).get("changeKinds").get(0).asString()).isEqualTo("ASSIGNEE");
        var before=ack242Row(notice);
        error(send(ack242Post(old,minsuCookie)),409,"INVALID_STATE");
        assertThat(ack242Row(notice)).isEqualTo(before);
        ok(send(ack242Post(notice,minsuCookie)));
    }

    @Test void ack242PreviewAndApplyReplayDoNotRepeatInvalidation() throws Exception {
        var old=ack242Pending();var original=ack242Row(old);
        var ready=ack242Ready("["+ack242Step(patrol,"미리 본 절차")+"]");
        assertThat(ack242Row(old)).isEqualTo(original); // 생성·preview 저장점 rollback
        assertThat(json.readTree(jdbc.queryForObject("SELECT steps::text FROM task WHERE id=?",String.class,patrol)).get(0).asString())
                .isEqualTo("#242 확인 대상 절차");
        String key=UUID.randomUUID().toString();
        var first=ok(send(ack242Apply(ready,key)));
        var after=ack242Row(old);
        long countBefore=count("SELECT count(*) FROM change_acknowledgment WHERE event_id=?",event);
        var replay=ok(send(ack242Apply(ready,key)));
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(first.body()));
        assertThat(ack242Row(old)).isEqualTo(after);
        assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE event_id=?",event)).isEqualTo(countBefore);
    }

    @Test void ack242InsertFailureRollsBackPendingChangesWithWholeApply() throws Exception {
        var old=ack242Pending();var before=ack242Row(old);
        var ready=ack242Ready("["+ack242Step(patrol,"되돌릴 절차")+"]");
        String cs=ready.get("id");
        // 새 ack INSERT의 UNIQUE 실패를 강제하는 격리 DB fixture. 스키마는 바꾸지 않는다.
        jdbc.update("""
                INSERT INTO change_acknowledgment(id,event_id,change_set_id,member_id,participation_revision,targets,targets_hash,acknowledged_at,acknowledged_targets)
                SELECT ?,event_id,?::uuid,member_id,participation_revision,targets,targets_hash,now(),targets FROM change_acknowledgment WHERE id=?::uuid
                """,UUID.randomUUID(),cs,old.get("id").asString());
        String revision=rev();
        error(send(ack242Apply(ready,UUID.randomUUID().toString())),500,"INTERNAL_ERROR");
        assertThat(ack242Row(old)).isEqualTo(before);
        assertThat(rev()).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT status FROM change_set WHERE id=?::uuid",String.class,cs)).isEqualTo("READY");
        assertThat(count("SELECT count(*) FROM operation_log WHERE change_set_id=?::uuid",cs)).isZero();
        assertThat(json.readTree(jdbc.queryForObject("SELECT steps::text FROM task WHERE id=?",String.class,patrol)).get(0).asString())
                .isEqualTo("#242 확인 대상 절차");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TIME","ZONE","CHECKLIST","ASSIGN","CANCEL"})
    void ack242SupportedChangesSupersedePendingTargets(String kind) throws Exception {
        var old=ack242Pending();
        String operation=switch(kind) {
            case "TIME" -> "{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"endAt\":\"2026-11-14T11:30:00Z\"}}";
            case "ZONE" -> "{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"zoneId\":\""+zoneA+"\"}}";
            case "CHECKLIST" -> "{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"checklist\":[{\"key\":\"gate\",\"text\":\"문 확인\",\"required\":true}]}}";
            case "ASSIGN" -> "{\"type\":\"ASSIGN_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"fromMemberId\":\""+minsuMember+"\",\"toMemberId\":\""+haneulMember+"\"}}";
            default -> "{\"type\":\"CANCEL_TASK\",\"payload\":{\"taskId\":\""+patrol+"\",\"reason\":\"종료\"}}";
        };
        applyAll(create("["+operation+"]"),1);
        var items=ack242Mine(minsuCookie);
        assertThat(items.get(1).get("state").asString()).isEqualTo("NO_CURRENT_TARGETS");
        var before=ack242Row(old);
        error(send(ack242Post(old,minsuCookie)),409,"INVALID_STATE");
        assertThat(ack242Row(old)).isEqualTo(before);
        assertThat(items.get(0).get("targets").get(0).get("changeKinds").get(0).asString())
                .isEqualTo("ASSIGN".equals(kind)?"ASSIGNEE":"CANCEL".equals(kind)?"CANCELED":kind);
    }

    @Test void ack242NetZeroOperationsDoNotSupersedeOrCreateTargets() throws Exception {
        var old=ack242Pending();var before=ack242Row(old);
        applyAll(create("["+ack242Step(patrol,"임시 변경")+","+ack242Step(patrol,"#242 확인 대상 절차")+"]"),1);
        assertThat(ack242Row(old)).isEqualTo(before);
        assertThat(ack242Mine(minsuCookie).size()).isEqualTo(1);
    }

    @Test void ack242DirectReleaseRollbackRestoresTargetsAndAssignments() throws Exception {
        var old=ack242Pending();
        ack242Transfer(chiefCookie,minsuMember,sound);
        var before=ack242Row(old);
        String revision=rev();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            transfers.transfer(event,minsu,haneulMember,sound,Long.parseLong(revision));
            assertThat(ack242Row(old)).isNotEqualTo(before); // 실제 대상 갱신을 관찰한 뒤 rollback한다.
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,patrol)).isNull();
            status.setRollbackOnly();
        });
        assertThat(ack242Row(old)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,patrol)).isEqualTo(minsuMember);
        assertThat(rev()).isEqualTo(revision);
    }

    @Test void ack242ApplyBeforeAckRejectsOldHashAfterRealLockWait() throws Exception { ack242Compete(true); }
    @Test void ack242AckBeforeApplyPreservesConfirmedHistoryAfterRealLockWait() throws Exception { ack242Compete(false); }

    /** 최초 실패 회귀의 409·DB 불변 기대값을 유지한다. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TITLE","PROGRAM"})
    void ack242UnresolvedMetadataChangeMustRejectOldHash(String kind) throws Exception {
        var old=ack242Pending();
        UUID program=UUID.randomUUID();
        jdbc.update("INSERT INTO program(id,event_id,zone_id,name,start_at,end_at) VALUES (?,?,?,'연결 공연','2026-11-14T09:00:00Z','2026-11-14T12:00:00Z')",program,event,zoneB);
        String field="TITLE".equals(kind)?"\"title\":\"새 제목\"":"\"programId\":\""+program+"\"";
        applyAll(create("[{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+patrol+"\","+field+"}}]"),1);
        var empty=ack242Mine(minsuCookie).get(0);
        assertThat(ack242Mine(minsuCookie).size()).isEqualTo(1);
        assertThat(empty.get("state").asString()).isEqualTo("NO_CURRENT_TARGETS");
        assertThat(empty.get("targets").size()).isZero();
        var before=ack242Row(old);
        var response=send(ack242Post(old,minsuCookie));
        var after=ack242Row(old);
        System.out.printf("ACK242 METADATA %s http=%d before=%s after=%s%n",kind,response.statusCode(),before,after);
        org.assertj.core.api.SoftAssertions.assertSoftly(s -> {
            s.assertThat(response.statusCode()).isEqualTo(409);
            s.assertThat(after).isEqualTo(before);
        });
        error(send(ack242Post(empty,minsuCookie)),409,"INVALID_STATE");
        assertThat(ack242Row(old)).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TITLE","PROGRAM"})
    void ack242MetadataPartialReplacementKeepsT2WithoutNewAck(String kind) throws Exception {
        UUID t2=task(event,safety,minsuMember,zoneA,"다음 순회","2026-11-14T12:30:00Z","2026-11-14T13:00:00Z");
        applyAll(create("["+ack242Step(patrol,"A1")+","+ack242Step(t2,"A2")+"]"),1);
        var old=ack242Mine(minsuCookie).get(0);
        applyAll(create("["+ack242MetadataOp(kind,patrol,"새 제목",ack242Program())+"]"),1);
        var items=ack242Mine(minsuCookie);
        assertThat(items.size()).isEqualTo(1); // 새 공식 확인 요청 없음
        var remaining=items.get(0);
        assertThat(remaining.get("id")).isEqualTo(old.get("id"));
        assertThat(remaining.get("targetsHash")).isNotEqualTo(old.get("targetsHash"));
        assertThat(remaining.get("targets").size()).isEqualTo(1);
        assertThat(remaining.get("targets").get(0).get("taskId").asString()).isEqualTo(t2.toString());
        var before=ack242Row(old);
        error(send(ack242Post(old,minsuCookie)),409,"INVALID_STATE");
        assertThat(ack242Row(old)).isEqualTo(before);
        assertThat(json.readTree(ok(send(ack242Post(remaining,minsuCookie))).body()).get("state").asString()).isEqualTo("ACKNOWLEDGED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TITLE","PROGRAM"})
    void ack242MetadataNoOpAndFinalRestoreKeepOriginalHash(String kind) throws Exception {
        var old=ack242Pending();var before=ack242Row(old);
        String title=jdbc.queryForObject("SELECT title FROM task WHERE id=?",String.class,patrol);
        String unchanged=ack242MetadataOp(kind,patrol,title,null);
        applyAll(create("["+unchanged+"]"),1);
        assertThat(ack242Row(old)).isEqualTo(before);
        applyAll(create("["+ack242MetadataOp(kind,patrol,"임시 제목",ack242Program())+","+unchanged+"]"),1);
        assertThat(ack242Row(old)).isEqualTo(before);
        assertThat(ack242Mine(minsuCookie).size()).isEqualTo(1);
        ok(send(ack242Post(old,minsuCookie)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TITLE","PROGRAM"})
    void ack242MetadataKeepsUnrelatedMemberAndConfirmedHistory(String kind) throws Exception {
        var old=ack242Pending();var before=ack242Row(old);
        UUID t9=task(event,safety,haneulMember,zoneB,"다른 팀원 업무","2026-11-14T09:00:00Z","2026-11-14T10:00:00Z");
        applyAll(create("["+ack242MetadataOp(kind,t9,"다른 제목",ack242Program())+"]"),1);
        assertThat(ack242Row(old)).isEqualTo(before);
        ok(send(ack242Post(old,minsuCookie)));
        var confirmed=ack242Row(old);
        applyAll(create("["+ack242MetadataOp(kind,patrol,"새 제목",ack242Program())+"]"),1);
        assertThat(ack242Row(old)).isEqualTo(confirmed);
        assertThat(ack242Mine(minsuCookie).size()).isEqualTo(1);
        assertThat(ack242Mine(haneulCookie).size()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TITLE","PROGRAM"})
    void ack242MetadataCombinedWithStepsStillCreatesOfficialAck(String kind) throws Exception {
        var old=ack242Pending();
        var operation=json.readTree(ack242MetadataOp(kind,patrol,"새 제목",ack242Program()));
        ((tools.jackson.databind.node.ObjectNode)operation.get("payload")).putArray("steps").add("새 절차");
        applyAll(create("["+json.writeValueAsString(operation)+"]"),1);
        var items=ack242Mine(minsuCookie);
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).get("state").asString()).isEqualTo("PENDING");
        var kinds=items.get(0).get("targets").get(0).get("changeKinds");
        assertThat(kinds.size()).isEqualTo(1);
        assertThat(kinds.get(0).asString()).isEqualTo("STEPS");
        assertThat(items.get(1).get("state").asString()).isEqualTo("NO_CURRENT_TARGETS");
        error(send(ack242Post(old,minsuCookie)),409,"INVALID_STATE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TITLE","PROGRAM"})
    void ack242MetadataPreviewRollbackAndReplayHaveNoDuplicateEffects(String kind) throws Exception {
        var old=ack242Pending();var before=ack242Row(old);
        var ready=ack242Ready("["+ack242MetadataOp(kind,patrol,"새 제목",ack242Program())+"]");
        assertThat(ack242Row(old)).isEqualTo(before);
        var input=json.readTree(ready.get("body"));
        String revision=rev(),key=UUID.randomUUID().toString();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            changes.apply(UUID.fromString(ready.get("id")),chief,key,2,Long.parseLong(revision),input.get("previewHash").asString());
            assertThat(json.readTree((String)ack242Row(old).get("targets")).size()).isZero();
            status.setRollbackOnly();
        });
        assertThat(ack242Row(old)).isEqualTo(before);
        assertThat(rev()).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT status FROM change_set WHERE id=?::uuid",String.class,ready.get("id"))).isEqualTo("READY");
        var first=ok(send(ack242Apply(ready,key)));var after=ack242Row(old);
        var replay=ok(send(ack242Apply(ready,key)));
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(first.body()));
        assertThat(ack242Row(old)).isEqualTo(after);
        assertThat(ack242Mine(minsuCookie).size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM change_set WHERE id=?::uuid",Long.class,ready.get("id"))).isEqualTo(3L);
    }

    String ack242MetadataOp(String kind,UUID taskId,String title,UUID program) {
        var payload=json.createObjectNode().put("taskId",taskId.toString());
        if("TITLE".equals(kind)) payload.put("title",title);
        else if(program==null) payload.putNull("programId");
        else payload.put("programId",program.toString());
        var op=json.createObjectNode().put("type","UPDATE_TASK");op.set("payload",payload);
        return json.writeValueAsString(op);
    }
    UUID ack242Program() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO program(id,event_id,zone_id,name,start_at,end_at) VALUES (?,?,?,'연결 공연','2026-11-14T09:00:00Z','2026-11-14T12:00:00Z')",id,event,zoneB);
        return id;
    }

    void ack242Compete(boolean applyFirst) throws Exception {
        var old=ack242Pending();
        var ready=ack242Ready("["+ack242Step(patrol,"경합 후 절차")+"]");
        var apply=ack242Apply(ready,UUID.randomUUID().toString()).build();
        var ack=ack242Post(old,minsuCookie).build();
        java.util.concurrent.CompletableFuture<HttpResponse<String>> first=null,second=null;
        HttpResponse<String> firstResult,secondResult;
        try(var locked=jdbc.getDataSource().getConnection()) {
            locked.setAutoCommit(false);
            try(var stmt=locked.prepareStatement("SELECT 1 FROM event WHERE id=? FOR UPDATE")) {
                stmt.setObject(1,event);stmt.executeQuery().close();
            }
            try {
                first=client.sendAsync(applyFirst?apply:ack,HttpResponse.BodyHandlers.ofString());
                ack242AwaitWaitingPosts(1);
                second=client.sendAsync(applyFirst?ack:apply,HttpResponse.BodyHandlers.ofString());
                ack242AwaitWaitingPosts(2);
                assertThat(first.isDone()).isFalse();assertThat(second.isDone()).isFalse();
                System.out.printf("ACK242 applyFirst=%s waiting=%d%n",applyFirst,ack242WaitingPosts());
                locked.commit();
                firstResult=first.get(15,java.util.concurrent.TimeUnit.SECONDS);
                secondResult=second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                locked.rollback();
                if(first!=null) first.get(15,java.util.concurrent.TimeUnit.SECONDS);
                if(second!=null) second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        if(applyFirst) {
            ok(firstResult);error(secondResult,409,"INVALID_STATE");
            assertThat(ack242Row(old).get("acknowledged_at")).isNull();
        } else {
            var done=json.readTree(ok(firstResult).body());ok(secondResult);
            assertThat(done.get("acknowledgedAt").asString())
                    .isEqualTo(((java.sql.Timestamp)ack242Row(old).get("acknowledged_at")).toInstant().toString());
            assertThat(ack242Row(old).get("targets_hash")).isEqualTo(old.get("targetsHash").asString());
        }
        assertThat(ack242Mine(minsuCookie).get(0).get("state").asString()).isEqualTo("PENDING");
    }
    Map<String,String> ack242Ready(String ops) throws Exception {
        String id=create(ops);
        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview",
                "{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie))).body());
        return Map.of("id",id,"body","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"2\",\"previewHash\":\""+preview.get("previewHash").asString()+"\"}");
    }
    HttpRequest.Builder ack242Apply(Map<String,String> ready,String key) {
        return post("/api/v1/change-sets/"+ready.get("id")+"/apply",ready.get("body")).header("Cookie",chiefCookie).header("Idempotency-Key",key);
    }
    void ack242Transfer(String cookie,UUID next,UUID group) throws Exception {
        ok(send(post("/api/v1/events/"+event+"/chief-transfer","{\"successorMemberId\":\""+next+"\",\"previousChiefWorkGroupId\":\""+group+"\",\"expectedEventRevision\":\""+rev()+"\"}").header("Cookie",cookie)));
    }

    String ack242Step(UUID taskId,String step) {
        var payload=json.createObjectNode().put("taskId",taskId.toString());
        payload.putArray("steps").add(step);
        var operation=json.createObjectNode().put("type","UPDATE_TASK");
        operation.set("payload",payload);
        return json.writeValueAsString(operation);
    }
    JsonNode ack242Mine(String cookie) throws Exception {
        return json.readTree(ok(send(get("/api/v1/events/"+event+"/my-changes").header("Cookie",cookie))).body()).get("items");
    }
    JsonNode ack242Pending() throws Exception {
        applyAll(create("["+ack242Step(patrol,"#242 확인 대상 절차")+"]"),1);
        var ack=ack242Mine(minsuCookie).get(0);
        assertThat(ack.get("state").asString()).isEqualTo("PENDING");
        return ack;
    }
    HttpRequest.Builder ack242Post(JsonNode ack,String cookie) {
        return post("/api/v1/change-acknowledgments/"+ack.get("id").asString()+"/ack",
                "{\"targetsHash\":\""+ack.get("targetsHash").asString()+"\"}").header("Cookie",cookie);
    }
    Map<String,Object> ack242Row(JsonNode ack) {
        return jdbc.queryForMap("""
                SELECT targets::text AS targets, targets_hash, acknowledged_at, acknowledged_targets::text AS acknowledged_targets
                  FROM change_acknowledgment WHERE id=?::uuid""",ack.get("id").asString());
    }
    long ack242WaitingPosts() {
        return count("""
                SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                  AND query LIKE 'SELECT state, revision FROM event WHERE id = %FOR UPDATE%'""");
    }
    void ack242AwaitWaitingPosts(int expected) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(ack242WaitingPosts()<expected && System.nanoTime()<until) Thread.sleep(20);
        assertThat(ack242WaitingPosts()).as("실제 Event 잠금 대기 중인 ack HTTP 요청 수").isEqualTo(expected);
    }

    // ---- 도움 ----
    @Test void notification345ApplyInboxReadOfficialAckAndReceiptReplay() throws Exception {
        registerPushFixture(haneul, haneulMember);
        // 실제 #233 경로로 가용성을 끄더라도 현재 참여자의 변경 알림 자격은 유지된다.
        ok(send(patch("/api/v1/members/"+minsuMember+"/availability",
                "{\"available\":false,\"expectedEventRevision\":\""+rev()+"\"}")
                .header("Cookie",minsuCookie).header("X-SceneCrew-CSRF","1")));
        var ready=ack242Ready(supportOps());
        assertThat(count("SELECT count(*) FROM notification WHERE event_id=?",event)).isZero();
        String key=UUID.randomUUID().toString();
        var first=ok(send(ack242Apply(ready,key)));
        assertThat(pushWorkCount()).isEqualTo(1);
        verifyNoInteractions(push);
        var rows=jdbc.queryForList("SELECT * FROM notification WHERE change_set_id=?::uuid ORDER BY member_id",ready.get("id"));
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r->r.get("member_id"))).containsExactlyInAnyOrder(haneulMember,minsuMember);
        assertThat(count("""
                SELECT count(*) FROM notification n JOIN change_acknowledgment a ON a.id=n.acknowledgment_id
                JOIN change_set c ON c.id=n.change_set_id
                WHERE n.change_set_id=?::uuid AND n.event_id=a.event_id AND n.member_id=a.member_id
                  AND n.participation_revision=a.participation_revision AND n.change_set_id=a.change_set_id
                  AND n.created_at=c.applied_at AND n.type='CHANGE_ACK' AND n.payload='{}'::jsonb
                  AND n.dedupe_key='CHANGE_ACK:'||a.id::text
                """,ready.get("id"))).isEqualTo(2);
        var h=notification345Inbox(haneulCookie);var m=notification345Inbox(minsuCookie);
        assertThat(h.size()).isEqualTo(1);assertThat(m.size()).isEqualTo(1);
        assertThat(notification345Inbox(chiefCookie).size()).isZero();
        assertThat(notification345Inbox(login(outsider)).size()).isZero();
        var ack=ack242Mine(haneulCookie).get(0);
        assertThat(h.get(0).get("target").get("kind").asString()).isEqualTo("CHANGE_ACK");
        assertThat(h.get(0).get("target").get("id")).isEqualTo(ack.get("id"));
        assertThat(h.get(0).get("eventId").asString()).isEqualTo(event.toString());
        assertThat(h.get(0).get("participationRevision").asString()).isEqualTo("2");
        var before=ack242Row(ack);
        error(send(post("/api/v1/notifications/"+h.get(0).get("id").asString()+"/read","").header("Cookie",minsuCookie)),404,"NOT_FOUND");
        ok(send(post("/api/v1/notifications/"+h.get(0).get("id").asString()+"/read","").header("Cookie",haneulCookie)));
        assertThat(ack242Row(ack)).isEqualTo(before);
        assertThat(ack242Mine(haneulCookie).get(0).get("state").asString()).isEqualTo("PENDING");
        error(send(post("/api/v1/change-acknowledgments/"+ack.get("id").asString()+"/ack","{\"targetsHash\":\"old\"}")
                .header("Cookie",haneulCookie)),409,"INVALID_STATE");
        ok(send(ack242Post(ack,haneulCookie)));
        assertThat(ack242Mine(haneulCookie).get(0).get("state").asString()).isEqualTo("ACKNOWLEDGED");
        var minsuAck=ack242Mine(minsuCookie).get(0);
        ok(send(ack242Post(minsuAck,minsuCookie)));
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?::uuid",java.sql.Timestamp.class,
                m.get(0).get("id").asString())).isNull();
        var notificationsBefore=jdbc.queryForList("SELECT * FROM notification WHERE event_id=? ORDER BY id",event);
        var acksBefore=jdbc.queryForList("SELECT * FROM change_acknowledgment WHERE event_id=? ORDER BY id",event);
        assertThat(json.readTree(ok(send(ack242Apply(ready,key))).body())).isEqualTo(json.readTree(first.body()));
        assertThat(pushWorkCount()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM notification WHERE event_id=? ORDER BY id",event)).isEqualTo(notificationsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM change_acknowledgment WHERE event_id=? ORDER BY id",event)).isEqualTo(acksBefore);
    }

    @Test void notification345ReassignmentNotifiesOldAndNewAssigneesOnce() throws Exception {
        String id=create("[{\"type\":\"ASSIGN_TASK\",\"payload\":{\"taskId\":\""+patrol
                +"\",\"fromMemberId\":\""+minsuMember+"\",\"toMemberId\":\""+haneulMember+"\"}}]");
        applyAll(id,1);
        assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid",id)).isEqualTo(2);
        for(String cookie:List.of(minsuCookie,haneulCookie)) {
            var items=notification345Inbox(cookie);assertThat(items.size()).isEqualTo(1);
            assertThat(items.get(0).get("target").get("id")).isEqualTo(ack242Mine(cookie).get(0).get("id"));
        }
        assertThat(ack242Mine(minsuCookie).get(0).get("targets").get(0).get("removed").asBoolean()).isTrue();
    }

    @Test void notification345DifferentKeysCompeteThroughRealHttpAndEventLock() throws Exception {
        var ready=ack242Ready(supportOps());String revision=rev();
        java.util.concurrent.CompletableFuture<HttpResponse<String>> one=null,two=null;
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM event WHERE id=? FOR UPDATE")) {
                lock.setObject(1,event);lock.executeQuery().close();
            }
            try {
                one=client.sendAsync(ack242Apply(ready,UUID.randomUUID().toString()).build(),HttpResponse.BodyHandlers.ofString());
                two=client.sendAsync(ack242Apply(ready,UUID.randomUUID().toString()).build(),HttpResponse.BodyHandlers.ofString());
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while(count("SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE 'SELECT state, revision FROM event%'")<2) {
                    if(System.nanoTime()>until) throw new AssertionError("두 HTTP apply의 Event 잠금 대기를 관찰하지 못했습니다.");
                    Thread.sleep(20);
                }
            } finally { connection.commit(); }
        }
        var r1=one.get(20,java.util.concurrent.TimeUnit.SECONDS);var r2=two.get(20,java.util.concurrent.TimeUnit.SECONDS);
        var responses=List.of(r1,r2);
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200,409);
        error(responses.stream().filter(r->r.statusCode()==409).findFirst().orElseThrow(),409,"INVALID_STATE");
        assertThat(rev()).isEqualTo(Long.toString(Long.parseLong(revision)+1));
        assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid",ready.get("id"))).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE change_set_id=?::uuid",ready.get("id"))).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM operation_log WHERE change_set_id=?::uuid",ready.get("id"))).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=? AND operation='SC-API-070'",event)).isEqualTo(1);
    }

    @Test void notification345SecondRecipientFailureRollsBackWholeApplyAndPending() throws Exception {
        var old=ack242Pending();
        registerPushFixture(haneul, haneulMember);
        registerPushFixture(minsu, minsuMember);
        var ready=ack242Ready(supportOps());
        var before=notification345Snapshot();
        String name="fail345_"+UUID.randomUUID().toString().replace("-","");
        try {
            // sequence는 rollback되지 않으므로 실제 두 번째 저장과 앞선 효과를 독립 관찰한다.
            jdbc.execute("CREATE SEQUENCE "+name);
            jdbc.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE attempt bigint; BEGIN
                      IF NEW.change_set_id='%s'::uuid THEN
                        attempt:=nextval('%s');
                        IF attempt=2 THEN
                          IF (SELECT count(*) FROM notification WHERE change_set_id=NEW.change_set_id)<>1
                            OR (SELECT count(*) FROM notification_delivery d JOIN notification n ON n.id=d.notification_id
                                WHERE n.change_set_id=NEW.change_set_id)<>1
                            OR (SELECT count(*) FROM change_acknowledgment WHERE change_set_id=NEW.change_set_id)<>2
                            OR NOT EXISTS (SELECT 1 FROM change_acknowledgment WHERE id='%s'::uuid AND targets='[]'::jsonb)
                            OR NOT EXISTS (SELECT 1 FROM change_set WHERE id=NEW.change_set_id AND status='APPLIED')
                          THEN RAISE EXCEPTION '345 injection precondition failed'; END IF;
                          PERFORM nextval('%s');
                          RAISE EXCEPTION '345 second recipient insert failure' USING ERRCODE='23514';
                        END IF;
                      END IF;
                      RETURN NEW;
                    END $$
                    """.formatted(name,ready.get("id"),name,old.get("id").asString(),name));
            jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON notification FOR EACH ROW EXECUTE FUNCTION "+name+"()");
            String key=UUID.randomUUID().toString();
            error(send(ack242Apply(ready,key)),500,"INTERNAL_ERROR");
            assertThat(jdbc.queryForObject("SELECT last_value FROM "+name,Long.class)).isEqualTo(3L);
            assertThat(notification345Snapshot()).isEqualTo(before);
            assertThat(pushWorkCount()).isZero();
            verifyNoInteractions(push);
            assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=? AND idempotency_key=?",event,key)).isZero();
            // 주입을 제거한 뒤 같은 키는 정상 재시도할 수 있어야 한다.
            jdbc.execute("DROP TRIGGER "+name+" ON notification");
            ok(send(ack242Apply(ready,key)));
            assertThat(pushWorkCount()).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid",ready.get("id"))).isEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS "+name+" ON notification");
            jdbc.execute("DROP FUNCTION IF EXISTS "+name+"()");
            jdbc.execute("DROP SEQUENCE IF EXISTS "+name);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DISABLED","NOT_SIGNED_UP"})
    void notification345InvalidRecipientAccountRejectsWithConflictWithoutPartialApply(String state) throws Exception {
        var old=ack242Pending();
        var ready=ack242Ready(supportOps());
        if("DISABLED".equals(state)) jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",minsu);
        else jdbc.update("UPDATE account SET signup_completed=false WHERE id=?",minsu);
        var before=notification345Snapshot();
        var accountBefore=jdbc.queryForMap("SELECT * FROM account WHERE id=?",minsu);
        var memberBefore=jdbc.queryForMap("SELECT * FROM member WHERE id=?",minsuMember);
        String key=UUID.randomUUID().toString(),name="recipient345_"+UUID.randomUUID().toString().replace("-","");
        try {
            // sequence는 rollback되지 않는다. 앞선 수신자의 알림과 pending 무효화까지 진행됐음을 확인한다.
            jdbc.execute("CREATE SEQUENCE "+name);
            jdbc.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                      IF NEW.change_set_id='%s'::uuid THEN
                        IF NOT EXISTS (SELECT 1 FROM change_acknowledgment WHERE id='%s'::uuid AND targets='[]'::jsonb)
                          THEN RAISE EXCEPTION '345 recipient rejection precondition failed'; END IF;
                        PERFORM nextval('%s');
                      END IF;
                      RETURN NEW;
                    END $$
                    """.formatted(name,ready.get("id"),old.get("id").asString(),name));
            jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON notification FOR EACH ROW EXECUTE FUNCTION "+name+"()");
            var rejected=send(ack242Apply(ready,key));
            error(rejected,409,"INVALID_STATE");
            assertThat(json.readTree(rejected.body()).get("message").asString()).contains("변경 확인");
            assertThat(jdbc.queryForMap("SELECT last_value,is_called FROM "+name))
                    .containsEntry("last_value",1L).containsEntry("is_called",true);
            assertThat(notification345Snapshot()).isEqualTo(before);
            assertThat(jdbc.queryForMap("SELECT * FROM account WHERE id=?",minsu)).isEqualTo(accountBefore);
            assertThat(jdbc.queryForMap("SELECT * FROM member WHERE id=?",minsuMember)).isEqualTo(memberBefore);
            assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=? AND idempotency_key=?",event,key)).isZero();
            jdbc.execute("DROP TRIGGER "+name+" ON notification");
            // fixture의 자격을 복원한 뒤 실패했던 동일 키는 정상 적용되고, 재전달은 추가 효과가 없다.
            jdbc.update("UPDATE account SET status='ACTIVE',signup_completed=true WHERE id=?",minsu);
            var applied=ok(send(ack242Apply(ready,key)));
            assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE change_set_id=?::uuid",ready.get("id"))).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid",ready.get("id"))).isEqualTo(2);
            var after=notification345Snapshot();
            assertThat(json.readTree(ok(send(ack242Apply(ready,key))).body())).isEqualTo(json.readTree(applied.body()));
            assertThat(notification345Snapshot()).isEqualTo(after);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS "+name+" ON notification");
            jdbc.execute("DROP FUNCTION IF EXISTS "+name+"()");
            jdbc.execute("DROP SEQUENCE IF EXISTS "+name);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"INACTIVE","NEXT_ROUND"})
    void notification345CurrentParticipationHidesOldInboxReadAndOfficialAck(String state) throws Exception {
        applyAll(create(supportOps()),1);
        var item=notification345Inbox(haneulCookie).get(0);var ack=ack242Mine(haneulCookie).get(0);
        var before=ack242Row(ack);
        if("INACTIVE".equals(state)) jdbc.update("UPDATE member SET active=false WHERE id=?",haneulMember);
        else jdbc.update("UPDATE member SET participation_revision=participation_revision+1 WHERE id=?",haneulMember);
        assertThat(notification345Inbox(haneulCookie).size()).isZero();
        error(send(post("/api/v1/notifications/"+item.get("id").asString()+"/read","").header("Cookie",haneulCookie)),404,"NOT_FOUND");
        error(send(ack242Post(ack,haneulCookie)),404,"NOT_FOUND");
        assertThat(ack242Row(ack)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?::uuid",java.sql.Timestamp.class,item.get("id").asString())).isNull();
    }

    @Test void notification345RejectsUnauthorizedAndStaleApplyWithoutEffects() throws Exception {
        var ready=ack242Ready(supportOps());var before=notification345Snapshot();
        error(send(post("/api/v1/change-sets/"+ready.get("id")+"/apply",ready.get("body"))
                .header("Cookie",haneulCookie).header("Idempotency-Key",UUID.randomUUID().toString())),403,"FORBIDDEN");
        assertThat(notification345Snapshot()).isEqualTo(before);
        // 참여 writer가 회차와 Event revision을 함께 바꾼 경계를 만든다.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s->{
            jdbc.update("UPDATE member SET participation_revision=participation_revision+1 WHERE id=?",haneulMember);
            jdbc.update("UPDATE event SET revision=revision+1 WHERE id=?",event);
        });
        before=notification345Snapshot();
        error(send(ack242Apply(ready,UUID.randomUUID().toString())),409,"VERSION_CONFLICT");
        assertThat(notification345Snapshot()).isEqualTo(before);
    }

    /** 실제 apply가 Event를 가진 채 탈퇴가 기다린다. 임시 DB gate만 사용하고 서비스는 교체하지 않는다. */
    @Test void notification345DeactivationAfterApplyWaitsForEventWithoutHoldingAccount() throws Exception {
        var ready=ack242Ready(supportOps()); String key=UUID.randomUUID().toString();
        long beforeRevision=Long.parseLong(rev()), deadlocks=notification345Deadlocks();
        java.util.concurrent.CompletableFuture<HttpResponse<String>> applying=null,leaving=null;
        try(var gate=new Notification345Gate("change_set","NEW.id='"+ready.get("id")+"'::uuid AND NEW.status='APPLIED'")) {
            try {
                applying=notification345Async(ack242Apply(ready,key));
                int applyPid=notification345Waiter(gate.pid,"UPDATE change_set SET status", "A apply owns Event");
                leaving=notification345Async(notification345Deactivate());
                int leavePid=notification345Waiter(applyPid,"SELECT state FROM event", "A deactivation waits for apply Event");
                assertThat(leavePid).isNotEqualTo(applyPid);
                // apply는 아직 Writer에 도달하지 않았다. 탈퇴가 Account를 먼저 보유했다면 NOWAIT가 실패한다.
                try(var probe=jdbc.getDataSource().getConnection()) {
                    probe.setAutoCommit(false);
                    try(var lock=probe.prepareStatement("SELECT id FROM account WHERE id=? FOR UPDATE NOWAIT")) {
                        lock.setObject(1,haneul); assertThat(lock.executeQuery().next()).isTrue();
                    } finally { probe.rollback(); }
                }
                System.out.println("345x334 A: Account NOWAIT probe acquired; deactivation holds no Account lock");
            } finally { gate.release(); notification345Drain(applying,leaving); }
            var applied=ok(applying.get(20,java.util.concurrent.TimeUnit.SECONDS));
            ok(leaving.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(rev()).isEqualTo(Long.toString(beforeRevision+2));
            assertThat(jdbc.queryForMap("SELECT status,token_version FROM account WHERE id=?",haneul))
                    .containsEntry("status","DISABLED").containsEntry("token_version",2L);
            assertThat(jdbc.queryForMap("SELECT active,available,participation_revision FROM member WHERE id=?",haneulMember))
                    .containsEntry("active",false).containsEntry("available",false).containsEntry("participation_revision",3L);
            assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE change_set_id=?::uuid",ready.get("id"))).isEqualTo(2);
            assertThat(count("""
                    SELECT count(*) FROM notification n JOIN change_acknowledgment a ON a.id=n.acknowledgment_id
                    WHERE n.change_set_id=?::uuid AND n.event_id=a.event_id AND n.change_set_id=a.change_set_id
                      AND n.member_id=a.member_id AND n.participation_revision=a.participation_revision
                      AND n.type='CHANGE_ACK' AND n.payload='{}'::jsonb AND n.dedupe_key='CHANGE_ACK:'||a.id::text
                    """,ready.get("id"))).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid",ready.get("id"))).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM notification WHERE member_id=? AND participation_revision=2 AND read_at IS NULL",haneulMember)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE member_id=? AND participation_revision=2 AND acknowledged_at IS NULL",haneulMember)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND title='객석 A 안전 지원' AND assignee_id IS NULL",event)).isEqualTo(1);
            error(send(get("/api/v1/me/notifications").header("Cookie",haneulCookie)),401,"UNAUTHENTICATED");
            UUID oldNotification=jdbc.queryForObject("SELECT id FROM notification WHERE member_id=?",UUID.class,haneulMember);
            UUID oldAck=jdbc.queryForObject("SELECT id FROM change_acknowledgment WHERE member_id=?",UUID.class,haneulMember);
            error(send(post("/api/v1/notifications/"+oldNotification+"/read","").header("Cookie",haneulCookie)),401,"UNAUTHENTICATED");
            error(send(post("/api/v1/change-acknowledgments/"+oldAck+"/ack","{\"targetsHash\":\"old\"}").header("Cookie",haneulCookie)),401,"UNAUTHENTICATED");
            assertThat(notification345Inbox(minsuCookie).size()).isEqualTo(1);
            var durable=notification345Snapshot();
            assertThat(json.readTree(ok(send(ack242Apply(ready,key))).body())).isEqualTo(json.readTree(applied.body()));
            assertThat(notification345Snapshot()).isEqualTo(durable);
            assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=? AND operation='SC-API-070'",event)).isEqualTo(1);
            assertThat(notification345Deadlocks()).isEqualTo(deadlocks);
            System.out.println("345x334 A: HTTP apply=200 deactivate=200; ack=2 notification=2; no deadlock delta; replay unchanged");
        }
    }

    @Test void notification345DeactivationBeforeApplyForcesRevisionRecheckWithoutReceipt() throws Exception {
        var ready=ack242Ready(supportOps()); long beforeRevision=Long.parseLong(rev()),deadlocks=notification345Deadlocks();
        java.util.concurrent.CompletableFuture<HttpResponse<String>> applying=null,leaving=null;
        try(var gate=new Notification345Gate("member","NEW.id='"+haneulMember+"'::uuid AND NOT NEW.active")) {
            try {
                leaving=notification345Async(notification345Deactivate());
                int leavePid=notification345Waiter(gate.pid,"UPDATE member SET active=false", "B deactivation owns Event");
                applying=notification345Async(ack242Apply(ready,UUID.randomUUID().toString()));
                notification345Waiter(leavePid,"SELECT state, revision FROM event", "B apply waits for deactivation Event");
            } finally { gate.release(); notification345Drain(applying,leaving); }
            ok(leaving.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var rejected=applying.get(20,java.util.concurrent.TimeUnit.SECONDS);
            error(rejected,409,"VERSION_CONFLICT");
            assertThat(json.readTree(rejected.body()).get("details").get("target").asString()).isEqualTo("event");
            assertThat(rev()).isEqualTo(Long.toString(beforeRevision+1));
            assertThat(jdbc.queryForMap("SELECT status,token_version FROM account WHERE id=?",haneul))
                    .containsEntry("status","DISABLED").containsEntry("token_version",2L);
            assertThat(jdbc.queryForMap("SELECT active,available,participation_revision FROM member WHERE id=?",haneulMember))
                    .containsEntry("active",false).containsEntry("available",false).containsEntry("participation_revision",3L);
            assertThat(count("SELECT count(*) FROM task WHERE event_id=?",event)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,patrol)).isEqualTo(minsuMember);
            assertThat(jdbc.queryForObject("SELECT status FROM change_set WHERE id=?::uuid",String.class,ready.get("id"))).isEqualTo("READY");
            for(String table:List.of("change_acknowledgment","notification"))
                assertThat(count("SELECT count(*) FROM "+table+" WHERE event_id=?",event)).isZero();
            assertThat(count("SELECT count(*) FROM operation_log WHERE change_set_id=?::uuid",ready.get("id"))).isZero();
            assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=? AND operation='SC-API-070'",event)).isZero();
            assertThat(notification345Deadlocks()).isEqualTo(deadlocks);
            System.out.println("345x334 B: HTTP deactivate=200 apply=409 VERSION_CONFLICT(event); ack=0 notification=0 apply receipt=0; no deadlock delta");
        }
    }

    @Test void notification345DeactivationBusinessFailurePreservesExistingNotificationsAndAcknowledgments() throws Exception {
        applyAll(create(supportOps()),1);
        jdbc.update("UPDATE task SET status='IN_PROGRESS' WHERE event_id=? AND assignee_id=?",event,haneulMember);
        var before=notification345Snapshot();
        var memberBefore=jdbc.queryForMap("SELECT * FROM member WHERE id=?",haneulMember);
        var accountBefore=jdbc.queryForMap("SELECT * FROM account WHERE id=?",haneul);
        error(send(notification345Deactivate()),409,"TASK_IN_PROGRESS");
        assertThat(notification345Snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM member WHERE id=?",haneulMember)).isEqualTo(memberBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM account WHERE id=?",haneul)).isEqualTo(accountBefore);
        assertThat(notification345Inbox(haneulCookie).size()).isEqualTo(1);
        assertThat(ack242Mine(haneulCookie).get(0).get("state").asString()).isEqualTo("PENDING");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"JOIN","REJOIN","CREATE"})
    void notification345DeactivationRetriesAfterActualHttpMembershipChange(String kind) throws Exception {
        applyAll(create(supportOps()),1);
        var durable=notification345Snapshot(); long beforeRevision=Long.parseLong(rev());
        UUID added=null,addedMember=null;
        String invite="gate345-"+UUID.randomUUID(); UUID addedGroup=null;
        if(!"CREATE".equals(kind)) {
            added=event("LIVE"); addedGroup=group(added,"추가 참여");
            member(added,outsider,"CHIEF",null,"외부 총괄");
            jdbc.update("UPDATE event SET invite_token_hash=?,total_capacity=31 WHERE id=?",
                    com.ssafy.scenecrew.invite.service.InviteJoinService.tokenHash(invite),added);
            if("REJOIN".equals(kind)) {
                addedMember=member(added,haneul,"WORKER",addedGroup,"재참여자");
                jdbc.update("UPDATE member SET active=false WHERE id=?",addedMember);
            }
        }
        java.util.concurrent.CompletableFuture<HttpResponse<String>> leaving=null;
        try(var owner=jdbc.getDataSource().getConnection()) {
            owner.setAutoCommit(false);
            int ownerPid;
            try(var query=owner.createStatement();var rows=query.executeQuery("SELECT pg_backend_pid()")) { rows.next();ownerPid=rows.getInt(1); }
            try(var lock=owner.prepareStatement("SELECT id FROM event WHERE id=? FOR UPDATE")) { lock.setObject(1,event);lock.executeQuery().close(); }
            try {
                leaving=notification345Async(notification345Deactivate());
                notification345Waiter(ownerPid,"SELECT state FROM event",kind+" deactivation snapshot read before membership addition");
                if("CREATE".equals(kind)) {
                    var created=send(post("/api/v1/events","{\"name\":\"탈퇴 경합 추가 행사\"}").header("Cookie",haneulCookie)
                            .header("X-SceneCrew-CSRF","1").header("Idempotency-Key",UUID.randomUUID().toString()));
                    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
                    added=UUID.fromString(json.readTree(created.body()).get("id").asString());events.add(added);
                } else {
                    var joined=ok(send(post("/api/v1/invites/"+invite+"/join","{\"workGroupId\":\""+addedGroup
                            +"\",\"available\":true,\"isReserve\":false,\"expectedEventRevision\":\"3\"}")
                            .header("Cookie",haneulCookie).header("X-SceneCrew-CSRF","1").header("Idempotency-Key",UUID.randomUUID().toString())));
                    var participation=json.readTree(joined.body()).get("participation");
                    if(addedMember!=null) assertThat(participation.get("memberId").asString()).isEqualTo(addedMember.toString());
                    addedMember=UUID.fromString(participation.get("memberId").asString());
                    assertThat(participation.get("participationRevision").asString()).isEqualTo("REJOIN".equals(kind)?"3":"1");
                }
            } finally { owner.commit();notification345Drain(leaving); }
        }
        if("CREATE".equals(kind)) {
            error(leaving.get(20,java.util.concurrent.TimeUnit.SECONDS),409,"CHIEF_TRANSFER_REQUIRED");
            assertThat(notification345Snapshot()).isEqualTo(durable);
            assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=?",String.class,haneul)).isEqualTo("ACTIVE");
            assertThat(count("SELECT count(*) FROM member WHERE account_id=? AND active",haneul)).isEqualTo(2);
            assertThat(notification345Inbox(haneulCookie).size()).isEqualTo(1);
        } else {
            ok(leaving.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(count("SELECT count(*) FROM member WHERE account_id=? AND active",haneul)).isZero();
            assertThat(rev()).isEqualTo(Long.toString(beforeRevision+1));
            assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,added)).isEqualTo(5L);
            assertThat(jdbc.queryForObject("SELECT participation_revision FROM member WHERE id=?",Long.class,addedMember))
                    .isEqualTo("REJOIN".equals(kind)?4L:2L);
            assertThat(count("SELECT count(*) FROM operation_log WHERE event_id IN (?,?) AND type='MEMBER_LEFT_BY_ACCOUNT_DEACTIVATION'",event,added)).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT * FROM notification WHERE event_id=? ORDER BY id",event)).isEqualTo(durable.get("notification"));
            assertThat(jdbc.queryForList("SELECT * FROM change_acknowledgment WHERE event_id=? ORDER BY id",event)).isEqualTo(durable.get("change_acknowledgment"));
        }
        System.out.println("345x334 "+kind+": actual HTTP membership addition committed before Account lock; refreshed membership enforced");
    }

    private void registerPushFixture(UUID account, UUID member) {
        jdbc.update("UPDATE account SET consent=jsonb_set(consent,'{push}','true') WHERE id=?", account);
        UUID installation = UUID.randomUUID();
        jdbc.update("INSERT INTO device_installation(device_id,proof_hash,account_id,push_session_id) VALUES (?,?,?,?)",
                installation, new byte[32], account, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO device_token(id,member_id,device_id,fcm_token,active,updated_at,participation_revision)
                SELECT ?,id,?,'synthetic-token',true,now(),participation_revision FROM member WHERE id=?
                """, UUID.randomUUID(), installation.toString(), member);
    }

    private long pushWorkCount() {
        return count("SELECT count(*) FROM outbox_event WHERE event_id=? AND type='CHANGE_ACK_PUSH'", event);
    }

    HttpRequest.Builder notification345Deactivate() {
        return post("/api/v1/me/deactivate","{\"password\":\"SyntheticPass8!\"}")
                .header("Cookie",haneulCookie).header("X-SceneCrew-CSRF","1");
    }
    java.util.concurrent.CompletableFuture<HttpResponse<String>> notification345Async(HttpRequest.Builder request) {
        return client.sendAsync(request.timeout(java.time.Duration.ofSeconds(25)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @SafeVarargs final void notification345Drain(java.util.concurrent.CompletableFuture<HttpResponse<String>>... requests) throws Exception {
        // HTTP cancellation alone does not stop server transactions. Await completion before dropping gates or fixtures.
        for(var request:requests) if(request!=null) request.get(30,java.util.concurrent.TimeUnit.SECONDS);
    }
    long notification345Deadlocks() {
        return count("SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()");
    }
    int notification345Waiter(int blocker,String sqlFragment,String label) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            var rows=jdbc.queryForList("""
                    SELECT pid,wait_event_type,wait_event FROM pg_stat_activity
                    WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock'
                      AND ?=ANY(pg_blocking_pids(pid)) AND query LIKE ?
                    """,blocker,"%"+sqlFragment+"%");
            if(rows.size()==1) {
                int pid=((Number)rows.getFirst().get("pid")).intValue();
                System.out.println("345x334 "+label+": waiter="+pid+" blocker="+blocker+" "+rows.getFirst());
                return pid;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Actual PostgreSQL lock wait not observed: "+label);
    }
    final class Notification345Gate implements AutoCloseable {
        final String name="gate345_"+UUID.randomUUID().toString().replace("-","");
        final String table;
        final java.sql.Connection owner;
        final int pid;
        boolean released;
        Notification345Gate(String table,String predicate) throws Exception {
            this.table=table;
            long key=java.util.concurrent.ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE);
            owner=jdbc.getDataSource().getConnection();
            owner.setAutoCommit(false);
            try(var statement=owner.createStatement();var rows=statement.executeQuery("SELECT pg_backend_pid(),pg_advisory_xact_lock("+key+")")) {
                rows.next();pid=rows.getInt(1);
            }
            try {
                jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "+predicate
                        +" THEN PERFORM pg_advisory_xact_lock("+key+"); END IF; RETURN NEW; END $$");
                jdbc.execute("CREATE TRIGGER "+name+" BEFORE UPDATE ON "+table+" FOR EACH ROW EXECUTE FUNCTION "+name+"()");
            } catch(Exception failure) { owner.close();throw failure; }
        }
        void release() throws Exception { if(!released) { owner.commit(); released=true; } }
        @Override public void close() throws Exception {
            try { release(); } finally {
                owner.close();
                jdbc.execute("DROP TRIGGER IF EXISTS "+name+" ON "+table);
                jdbc.execute("DROP FUNCTION IF EXISTS "+name+"()");
            }
        }
    }

    JsonNode notification345Inbox(String cookie) throws Exception {
        return json.readTree(ok(send(get("/api/v1/me/notifications").header("Cookie",cookie))).body()).get("items");
    }
    Map<String,Object> notification345Snapshot() {
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("event",jdbc.queryForMap("SELECT * FROM event WHERE id=?",event));
        for(String table:List.of("task","change_set","change_acknowledgment","notification","operation_log","idempotency_record"))
            state.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE event_id=? ORDER BY id",event));
        return state;
    }

    // ---- 장비 예약 연산(V9-D44) ----------------------------------------------------------------------------------

    String evidence(UUID id) { return "[{\"kind\":\"EQUIPMENT_ASSET\",\"id\":\""+id+"\",\"revision\":\"1\",\"contentHash\":null,\"observedAt\":null,\"preconditions\":[]}]"; }
    String write(String assets,String program,String task,String start,String end) {
        return "{\"assetIds\":["+assets+"],\"programId\":"+program+",\"taskId\":"+task+",\"startAt\":\""+start+"\",\"endAt\":\""+end+"\"}";
    }
    String reserveOp(String write,UUID evidenceId) { return "{\"type\":\"RESERVE_EQUIPMENT\",\"payload\":{\"reservation\":"+write+",\"evidenceRefs\":"+evidence(evidenceId)+"}}"; }
    String q(UUID id) { return "\""+id+"\""; }
    UUID asset(UUID event,String name,UUID... components) {
        var id=UUID.randomUUID();
        String comps=json.writeValueAsString(Arrays.stream(components).map(UUID::toString).toList());
        jdbc.update("""
                INSERT INTO equipment_asset (id,event_id,name,model,technical_state,state_evidence,component_asset_ids,ports,verified_compatibility,
                                             storage_zone_id,holder_member_id,revision)
                VALUES (?,?,?,NULL,'VERIFIED_USABLE','[]'::jsonb,?::jsonb,'[]'::jsonb,'[]'::jsonb,NULL,NULL,1)""",id,event,name,comps);
        return id;
    }
    UUID reservation(UUID event,String status,String start,String end,UUID... assets) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO equipment_reservation (id,event_id,program_id,task_id,start_at,end_at,status,source_change_set_id,revision) VALUES (?,?,NULL,NULL,?::timestamptz,?::timestamptz,?,NULL,1)",
                id,event,start,end,status);
        for (UUID a:assets) jdbc.update("INSERT INTO equipment_reservation_asset VALUES (?,?)",id,a);
        return id;
    }
    HttpResponse<String> createRaw(String ops) throws Exception {
        return send(post("/api/v1/events/"+event+"/change-sets",createBody(ops)).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
    }
    String addSupportTask() { return supportOps().substring(1,supportOps().indexOf(",{\"type\":\"UPDATE_TASK\"")); }

    @Test void reservationOperationsPreviewEqualsApplyAndShowInSchedule() throws Exception {
        UUID mixer=asset(event,"메인 믹서"), spare=asset(event,"예비 믹서"), wireless=asset(event,"무선 마이크");
        UUID moved=reservation(event,"PLANNED","2026-11-14T09:00:00Z","2026-11-14T10:00:00Z",spare);
        UUID canceled=reservation(event,"ACTIVE","2026-11-14T09:00:00Z","2026-11-14T10:00:00Z",wireless);
        // 수정 시점에는 무선 마이크 예약(09:00~10:00)이 아직 있지만 [10:00, 11:00)과 끝·시작만 맞닿는다(허용).
        String ops="["+addSupportTask()+","
                +reserveOp(write(q(mixer),"null","\"tmp-support\"","2026-11-14T09:25:00Z","2026-11-14T09:55:00Z"),mixer)+","
                +"{\"type\":\"UPDATE_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+moved+"\",\"expectedRevision\":\"1\",\"after\":"
                +write(q(spare)+","+q(wireless),"null","null","2026-11-14T10:00:00Z","2026-11-14T11:00:00Z")+",\"evidenceRefs\":"+evidence(spare)+"}},"
                +"{\"type\":\"CANCEL_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+canceled+"\",\"expectedRevision\":\"1\",\"reason\":\"무선 마이크 고장\"}}]";
        String id=create(ops);
        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}")
                .header("Cookie",chiefCookie))).body());
        var diff=preview.get("diff");
        assertThat(diff.size()).isEqualTo(4);
        assertThat(diff.get(0).propertyNames()).containsExactly("operationIndex","type","targetId","summary");
        for (int i=1;i<4;i++) assertThat(diff.get(i).propertyNames()).containsExactly("operationIndex","type","targetId","summary","before","after","evidenceRefs");
        var reserved=diff.get(1);
        assertThat(reserved.get("before").isNull()).isTrue();
        assertThat(reserved.get("after").propertyNames()).containsExactly("id","eventId","assetIds","programId","taskId","startAt","endAt","status","sourceChangeSetId","revision");
        assertThat(reserved.get("after").get("taskId").asString()).isEqualTo(diff.get(0).get("targetId").asString());
        assertThat(reserved.get("after").get("status").asString()).isEqualTo("PLANNED");
        assertThat(reserved.get("after").get("revision").asString()).isEqualTo("1");
        assertThat(reserved.get("after").get("sourceChangeSetId").asString()).isEqualTo(id);
        assertThat(reserved.get("summary").asString()).isEqualTo("장비 예약: 메인 믹서 (18:25~18:55)");
        assertThat(reserved.get("evidenceRefs").get(0).get("id").asString()).isEqualTo(mixer.toString());
        var updated=diff.get(2);
        assertThat(updated.get("before").get("revision").asString()).isEqualTo("1");
        assertThat(updated.get("after").get("revision").asString()).isEqualTo("2");
        assertThat(updated.get("after").get("assetIds").size()).isEqualTo(2);
        var cancel=diff.get(3);
        assertThat(cancel.get("before").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(cancel.get("after").get("status").asString()).isEqualTo("CANCELED");
        assertThat(cancel.get("evidenceRefs").isEmpty()).isTrue();
        // 미리보기는 아무것도 남기지 않는다.
        assertThat(count("SELECT count(*) FROM equipment_reservation WHERE event_id=?",event)).isEqualTo(2);
        assertThat(count("SELECT revision FROM equipment_reservation WHERE id=?",moved)).isEqualTo(1);
        // 저장한 diff를 다시 읽어도 모양(before null 포함)이 같다.
        assertThat(json.readTree(ok(send(get("/api/v1/change-sets/"+id).header("Cookie",chiefCookie))).body()).get("diff")).isEqualTo(diff);

        var applied=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/apply","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"2\",\"previewHash\":\""
                +preview.get("previewHash").asString()+"\"}").header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()))).body());
        assertThat(applied.get("diff")).isEqualTo(diff);
        var schedule=json.readTree(ok(send(get("/api/v1/events/"+event+"/equipment-reservations").header("Cookie",chiefCookie))).body()).get("items");
        Map<String,JsonNode> byId=new HashMap<>();
        for (var r:schedule) byId.put(r.get("id").asString(),r);
        assertThat(byId.get(reserved.get("targetId").asString())).isEqualTo(reserved.get("after"));
        assertThat(byId.get(moved.toString())).isEqualTo(updated.get("after"));
        assertThat(byId.get(canceled.toString())).isEqualTo(cancel.get("after"));
        assertThat(jdbc.queryForList("SELECT resource_type FROM operation_log WHERE change_set_id=?::uuid ORDER BY resource_type",String.class,id))
                .containsExactly("EQUIPMENT_RESERVATION","EQUIPMENT_RESERVATION","EQUIPMENT_RESERVATION","TASK");
    }

    @Test void reservationOperationRules() throws Exception {
        UUID mic=asset(event,"핀 마이크"), kit=asset(event,"마이크 세트",mic), lone=asset(event,"단독 스피커");
        UUID foreign=asset(event("LIVE"),"다른 행사 장비");
        reservation(event,"PLANNED","2026-11-14T09:00:00Z","2026-11-14T10:00:00Z",mic);
        reservation(event,"CANCELED","2026-11-14T09:00:00Z","2026-11-14T10:00:00Z",lone);
        // 세트를 잡으면 구성품 핀 마이크의 예약과 겹친다.
        var overlap=createRaw("["+reserveOp(write(q(kit),"null","null","2026-11-14T09:30:00Z","2026-11-14T10:30:00Z"),kit)+"]");
        error(overlap,422,"TIME_OVERLAP");
        assertThat(json.readTree(overlap.body()).get("details").get("field").asString()).isEqualTo("operations[0].payload.reservation.assetIds");
        // 끝과 시작이 같으면 겹치지 않는다. 취소된 예약은 막지 않는다.
        create("["+reserveOp(write(q(kit),"null","null","2026-11-14T10:00:00Z","2026-11-14T10:30:00Z"),kit)+"]");
        create("["+reserveOp(write(q(lone),"null","null","2026-11-14T09:00:00Z","2026-11-14T10:00:00Z"),lone)+"]");
        // 반대로 세트 예약이 있으면 구성품만 잡아도 겹친다.
        UUID kitHeld=reservation(event,"ACTIVE","2026-11-14T11:00:00Z","2026-11-14T12:00:00Z",kit);
        error(createRaw("["+reserveOp(write(q(mic),"null","null","2026-11-14T11:30:00Z","2026-11-14T11:40:00Z"),mic)+"]"),422,"TIME_OVERLAP");
        // 같은 변경안 안의 앞 예약도 본다. 하나라도 실패하면 아무것도 남지 않는다.
        long before=count("SELECT count(*) FROM equipment_reservation WHERE event_id=?",event);
        String twice=reserveOp(write(q(lone),"null","null","2026-11-14T12:00:00Z","2026-11-14T13:00:00Z"),lone);
        error(createRaw("["+twice+","+twice+"]"),422,"TIME_OVERLAP");
        assertThat(count("SELECT count(*) FROM equipment_reservation WHERE event_id=?",event)).isEqualTo(before);
        // 참조: 다른 행사 장비·풀 수 없는 tmp-·tmp- 프로그램·이 행사에 없는 업무
        var cases=Map.of(
                write(q(foreign),"null","null","2026-11-14T12:00:00Z","2026-11-14T13:00:00Z"),"operations[0].payload.reservation.assetIds",
                write(q(lone),"null","\"tmp-none\"","2026-11-14T12:00:00Z","2026-11-14T13:00:00Z"),"operations[0].payload.reservation.taskId",
                write(q(lone),"\"tmp-program\"","null","2026-11-14T12:00:00Z","2026-11-14T13:00:00Z"),"operations[0].payload.reservation.programId",
                write(q(lone),"null",q(UUID.randomUUID()),"2026-11-14T12:00:00Z","2026-11-14T13:00:00Z"),"operations[0].payload.reservation.taskId");
        for (var c:cases.entrySet()) {
            var r=createRaw("["+reserveOp(c.getKey(),lone)+"]");
            error(r,422,"INVALID_REFERENCE");
            assertThat(json.readTree(r.body()).get("details").get("field").asString()).isEqualTo(c.getValue());
        }
        // 형식: 근거 없음·중복 장비·시작>=끝·장비 없음·revision 형식·빈 사유·여분 키
        for (String bad:List.of(
                "{\"type\":\"RESERVE_EQUIPMENT\",\"payload\":{\"reservation\":"+write(q(lone),"null","null","2026-11-14T12:00:00Z","2026-11-14T13:00:00Z")+",\"evidenceRefs\":[]}}",
                reserveOp(write(q(lone)+","+q(lone),"null","null","2026-11-14T12:00:00Z","2026-11-14T13:00:00Z"),lone),
                reserveOp(write(q(lone),"null","null","2026-11-14T13:00:00Z","2026-11-14T13:00:00Z"),lone),
                reserveOp(write("","null","null","2026-11-14T12:00:00Z","2026-11-14T13:00:00Z"),lone),
                "{\"type\":\"CANCEL_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+kitHeld+"\",\"expectedRevision\":1,\"reason\":\"x\"}}",
                "{\"type\":\"CANCEL_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+kitHeld+"\",\"expectedRevision\":\"1\",\"reason\":\" \"}}",
                "{\"type\":\"CANCEL_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+kitHeld+"\",\"expectedRevision\":\"1\",\"reason\":\"x\",\"extra\":1}}"))
            error(createRaw("["+bad+"]"),400,"INVALID_REQUEST");
        // 옛 revision은 409 VERSION_CONFLICT, 취소된 예약은 409 INVALID_STATE, 없는 예약은 422
        var stale=createRaw("[{\"type\":\"CANCEL_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+kitHeld+"\",\"expectedRevision\":\"7\",\"reason\":\"x\"}}]");
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("equipmentReservation");
        UUID dead=reservation(event,"CANCELED","2026-11-14T14:00:00Z","2026-11-14T15:00:00Z",lone);
        error(createRaw("[{\"type\":\"UPDATE_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+dead+"\",\"expectedRevision\":\"1\",\"after\":"
                +write(q(lone),"null","null","2026-11-14T14:00:00Z","2026-11-14T15:00:00Z")+",\"evidenceRefs\":"+evidence(lone)+"}}]"),409,"INVALID_STATE");
        error(createRaw("[{\"type\":\"CANCEL_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+UUID.randomUUID()+"\",\"expectedRevision\":\"1\",\"reason\":\"x\"}}]"),422,"INVALID_REFERENCE");
        // 수정은 자기 예약과 겹쳐도 된다.
        create("[{\"type\":\"UPDATE_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+kitHeld+"\",\"expectedRevision\":\"1\",\"after\":"
                +write(q(kit),"null","null","2026-11-14T11:30:00Z","2026-11-14T12:30:00Z")+",\"evidenceRefs\":"+evidence(kit)+"}}]");
        // 리뷰 수정: 끝났거나 취소된 업무에는 장비를 잡지 않는다.
        for (String status:List.of("CANCELED","DONE")) {
            UUID closed=task(event,safety,minsuMember,zoneA,"닫힌 업무 "+status,"2026-11-14T13:00:00Z","2026-11-14T13:30:00Z");
            jdbc.update("UPDATE task SET status=? WHERE id=?",status,closed);
            var r=createRaw("["+reserveOp(write(q(lone),"null",q(closed),"2026-11-14T13:00:00Z","2026-11-14T13:30:00Z"),lone)+"]");
            error(r,422,"INVALID_REFERENCE");
            assertThat(json.readTree(r.body()).get("details").get("field").asString()).isEqualTo("operations[0].payload.reservation.taskId");
        }
        // 리뷰 수정: 예약 시간도 행사 준비 시작~철수 끝 안이어야 한다(수정도 같다).
        jdbc.update("UPDATE event SET prep_start_at='2026-11-14T08:00:00Z', teardown_end_at='2026-11-14T15:00:00Z' WHERE id=?",event);
        var outside=createRaw("["+reserveOp(write(q(lone),"null","null","2026-11-14T14:30:00Z","2026-11-14T15:30:00Z"),lone)+"]");
        error(outside,422,"INVALID_REFERENCE");
        assertThat(json.readTree(outside.body()).get("details").get("field").asString()).isEqualTo("operations[0].payload.reservation.startAt");
        var movedOut=createRaw("[{\"type\":\"UPDATE_EQUIPMENT_RESERVATION\",\"payload\":{\"reservationId\":\""+kitHeld+"\",\"expectedRevision\":\"1\",\"after\":"
                +write(q(kit),"null","null","2026-11-14T07:00:00Z","2026-11-14T08:30:00Z")+",\"evidenceRefs\":"+evidence(kit)+"}}]");
        error(movedOut,422,"INVALID_REFERENCE");
        assertThat(json.readTree(movedOut.body()).get("details").get("field").asString()).isEqualTo("operations[0].payload.after.startAt");
        create("["+reserveOp(write(q(lone),"null","null","2026-11-14T14:00:00Z","2026-11-14T15:00:00Z"),lone)+"]");
    }

    @Test void reservationTakenByAnotherApplyNeedsNewPreview() throws Exception {
        UUID mixer=asset(event,"메인 믹서");
        String op="["+reserveOp(write(q(mixer),"null","null","2026-11-14T09:00:00Z","2026-11-14T10:00:00Z"),mixer)+"]";
        String first=create(op), second=create(op);
        var p1=json.readTree(ok(send(post("/api/v1/change-sets/"+first+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie))).body());
        var p2=json.readTree(ok(send(post("/api/v1/change-sets/"+second+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie))).body());
        String base=rev();
        ok(send(post("/api/v1/change-sets/"+first+"/apply","{\"expectedEventRevision\":\""+base+"\",\"expectedChangeSetRevision\":\"2\",\"previewHash\":\""+p1.get("previewHash").asString()+"\"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())));
        error(send(post("/api/v1/change-sets/"+second+"/apply","{\"expectedEventRevision\":\""+base+"\",\"expectedChangeSetRevision\":\"2\",\"previewHash\":\""+p2.get("previewHash").asString()+"\"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),409,"VERSION_CONFLICT");
        // 다시 미리보기하면 이미 잡힌 장비라 422다.
        error(send(post("/api/v1/change-sets/"+second+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"2\"}").header("Cookie",chiefCookie)),422,"TIME_OVERLAP");
        assertThat(count("SELECT count(*) FROM equipment_reservation WHERE event_id=?",event)).isEqualTo(1);
    }

    String create(String ops) throws Exception {
        var r=send(post("/api/v1/events/"+event+"/change-sets",createBody(ops)).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json.readTree(r.body()).get("id").asString();
    }
    void applyAll(String id,int revision) throws Exception {
        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\""+revision+"\"}").header("Cookie",chiefCookie))).body());
        ok(send(post("/api/v1/change-sets/"+id+"/apply","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\""+(revision+1)+"\",\"previewHash\":\""+preview.get("previewHash").asString()+"\"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())));
        // 모든 #283/#242 성공 회귀에서도 신규 ack와 알림의 1:1 대응을 검사한다(no-op는 둘 다 0건).
        assertThat(count("SELECT count(*) FROM notification WHERE change_set_id=?::uuid",id))
                .isEqualTo(count("SELECT count(*) FROM change_acknowledgment WHERE change_set_id=?::uuid",id));
    }
    String rev() { return String.valueOf(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)); }
    long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    HttpResponse<String> ok(HttpResponse<String> r) { assertThat(r.statusCode()).as(r.body()).isEqualTo(200); return r; }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    UUID event(String state) {
        var id=UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",
                id,"공연",state,3L,LocalDate.of(2026,11,14));
        return id;
    }
    UUID group(UUID event,String name) { var id=UUID.randomUUID(); jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?, ?, ?, '', true)",id,event,name); return id; }
    UUID zone(UUID event,String name) { var id=UUID.randomUUID(); jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,?,NULL,'[]'::jsonb)",id,event,name); return id; }
    UUID task(UUID event,UUID group,UUID assignee,UUID zone,String title,String start,String end) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status,first_assigned_at)
                VALUES (?,?,?,'b1',?,?,NULL,NULL,?,?::timestamptz,?::timestamptz,'["순회"]'::jsonb,'[]'::jsonb,'WAITING',now())""",
                id,event,group,assignee,zone,title,start,end);
        return id;
    }
    UUID member(UUID event,UUID account,String role,UUID group,String name) {
        var id=UUID.randomUUID();
        Long crew=jdbc.queryForObject("SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?",Long.class,event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,?,?,true,false,true,2)""",id,event,account,group,crew,role,name);
        return id;
    }
    String login(UUID account) throws Exception {
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type","application/json")
                .header("X-SceneCrew-Client","WEB").header("Origin",WEB).header("X-SceneCrew-CSRF","1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    String url(String path) { return "http://localhost:"+port+path; }
    HttpRequest.Builder get(String path) { return HttpRequest.newBuilder(URI.create(url(path))).GET(); }
    HttpRequest.Builder post(String path,String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json").header("Origin",WEB)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }
    HttpRequest.Builder patch(String path,String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json").header("Origin",WEB)
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Encoder {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}
