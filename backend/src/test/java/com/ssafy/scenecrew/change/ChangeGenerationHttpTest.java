package com.ssafy.scenecrew.change;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.aijob.client.AiClient;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.aijob.domain.AiJobState;
import com.ssafy.scenecrew.aijob.pipeline.AiJobOutboxTypes;
import com.ssafy.scenecrew.aijob.repository.AiJobRepository;
import com.ssafy.scenecrew.change.ai.GenerateChangeKind;
import com.ssafy.scenecrew.global.outbox.OutboxRelay;
import com.ssafy.scenecrew.notification.service.PushSender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * #343 AI 대응안 생성(064) 접수·AI 입력·결과 반영(AI 변경안 저장)·063 공개·변경안 조회의 참고 기록.
 * 실제 HTTP·Security·PostgreSQL·Redis. 전체 흐름 시험은 AI 클라이언트를 대체하고 실제 Job 실행기를 사용한다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="scenecrew.ai.server.service-token=test-service-token")
@ActiveProfiles("test")
@Import(ChangeGenerationHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ChangeGenerationHttpTest {
    static final String WEB="https://web.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"change-generation-test"); r.add("scenecrew.auth.audience",()->"change-generation-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PushSender push;
    @MockitoBean AiClient ai;
    @Autowired OutboxRelay relay;
    @Autowired PasswordEncoder encoder;
    @Autowired GenerateChangeKind kind;
    @Autowired AiJobRepository jobs;
    @Autowired PlatformTransactionManager transactionManager;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    UUID chief, worker, outsider, event, other, stageGroup, stage, lobby, chiefMember, workerMember, spareMember, opening,
            issue, report, otherIssue;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); worker=account(); var spare=account(); outsider=account();
        event=event("LIVE");
        jdbc.update("UPDATE event SET open_at='2026-11-14T08:00:00Z', close_at='2026-11-14T12:00:00Z', teardown_end_at='2026-11-14T13:00:00Z' WHERE id=?",event);
        stageGroup=group(event,"무대 운영");
        stage=zone(event,"무대"); lobby=zone(event,"로비");
        chiefMember=member(event,chief,"CHIEF",null,"김총괄");
        workerMember=member(event,worker,"WORKER",stageGroup,"오하늘");
        spareMember=member(event,spare,"WORKER",stageGroup,"박민수");
        opening=task(event,stageGroup,workerMember,stage,"오프닝 진행 지원","2026-11-14T09:00:00Z","2026-11-14T09:05:00Z");
        issue=issue(event,stageGroup,stage,workerMember);
        report=report(issue,workerMember,"무대 프로젝터가 안 나옵니다");
        other=event("LIVE"); var otherGroup=group(other,"안내"); var otherZone=zone(other,"입구");
        var outsiderMember=member(other,outsider,"CHIEF",null,"외부");
        otherIssue=issue(other,otherGroup,otherZone,outsiderMember);
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
    }
    @AfterEach void cleanup() {
        for (var e:events) {
            jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
            jdbc.update("DELETE FROM outbox_event WHERE event_id=?",e);
            jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
            jdbc.update("DELETE FROM notification_delivery WHERE notification_id IN (SELECT id FROM notification WHERE event_id=?)",e);
            jdbc.update("DELETE FROM notification WHERE event_id=?",e);
            jdbc.update("DELETE FROM change_acknowledgment WHERE event_id=?",e);
            jdbc.update("ALTER TABLE change_set_evidence_revision DISABLE TRIGGER USER");
            jdbc.update("DELETE FROM change_set_evidence_revision WHERE change_set_id IN (SELECT id FROM change_set WHERE event_id=?)",e);
            jdbc.update("ALTER TABLE change_set_evidence_revision ENABLE TRIGGER USER");
            jdbc.update("DELETE FROM change_set WHERE event_id=?",e);
            jdbc.update("DELETE FROM ai_job WHERE event_id=?",e);
            jdbc.update("DELETE FROM operational_situation WHERE event_id=?",e);
            jdbc.update("DELETE FROM report WHERE issue_id IN (SELECT id FROM issue WHERE event_id=?)",e);
            jdbc.update("DELETE FROM issue WHERE event_id=?",e);
            jdbc.update("DELETE FROM task WHERE event_id=?",e);
            jdbc.update("DELETE FROM member WHERE event_id=?",e);
            jdbc.update("DELETE FROM zone WHERE event_id=?",e);
            jdbc.update("DELETE FROM work_group WHERE event_id=?",e);
            jdbc.update("DELETE FROM event WHERE id=?",e);
        }
        for (var a:accounts) {
            jdbc.update("DELETE FROM idempotency_record WHERE actor_account_id=?",a);
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        events.clear(); accounts.clear();
    }

    @Test void acceptQueuesOneGenerationWithSnapshotWithoutPersonalInfo() throws Exception {
        jdbc.update("UPDATE report SET analysis='{\"situation\":{\"primary\":\"EQUIPMENT_ANOMALY\",\"related\":[\"SCHEDULE_DELAY\"]}}'::jsonb WHERE id=?",report);
        String key=UUID.randomUUID().toString();
        var r=generate(body(issue,"오프닝 20분 지연"),key,chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var accepted=json.readTree(r.body());
        UUID job=UUID.fromString(accepted.get("jobId").asString());
        assertThat(accepted.get("statusUrl").asString()).isEqualTo("/api/v1/ai-jobs/"+job);
        var row=jdbc.queryForMap("SELECT kind,state,context_revision,input::text AS input FROM ai_job WHERE id=?",job);
        assertThat(row).containsEntry("kind","GENERATE_CHANGE").containsEntry("state","QUEUED");
        String raw=(String)row.get("input");
        var input=json.readTree(raw);
        assertThat(input.propertyNames()).containsExactlyInAnyOrder("issue","instruction","situation","now","event","zones",
                "programs","workGroups","members","tasks");
        assertThat(input.get("issue").get("zoneId").asString()).isEqualTo(stage.toString());
        assertThat(input.get("issue").get("reports").get(0).get("body").asString()).isEqualTo("무대 프로젝터가 안 나옵니다");
        assertThat(input.get("instruction").asString()).isEqualTo("오프닝 20분 지연");
        assertThat(input.get("situation").get("primaryType").asString()).isEqualTo("EQUIPMENT_ANOMALY");
        assertThat(input.get("event").get("openAt").asString()).isEqualTo("2026-11-14T08:00:00Z");
        // 팀원은 담당 가능한 WORKER만, 번호·그룹·가용·예비 여부만. 이름·계정은 보내지 않는다.
        assertThat(input.get("members").size()).isEqualTo(2);
        assertThat(input.get("members").get(0).propertyNames()).containsExactlyInAnyOrder("id","crewNo","workGroupId","available","isReserve");
        assertThat(raw).doesNotContain("오하늘").doesNotContain("김총괄").doesNotContain(worker.toString());
        assertThat(input.get("tasks").get(0).get("status").asString()).isEqualTo("TODO");   // DB WAITING → AI TODO
        // 같은 키는 새 작업 없이 최초 202, 진행 중이면 새 키는 429(행사당 1개)
        assertThat(json.readTree(generate(body(issue,"오프닝 20분 지연"),key,chiefCookie).body())).isEqualTo(accepted);
        // 아직 쓰지 않는 safetyObservationId·evidenceRefs도 같은 요청 판정에 들어간다(값만 바꾼 재요청은 409)
        error(generate("{\"issueId\":\""+issue+"\",\"instruction\":\"오프닝 20분 지연\",\"safetyObservationId\":\""+UUID.randomUUID()
                +"\",\"expectedEventRevision\":\""+rev()+"\"}",key,chiefCookie),409,"IDEMPOTENCY_MISMATCH");
        error(generate(body(issue,"다른 지시"),UUID.randomUUID().toString(),chiefCookie),429,"RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isEqualTo(1);
    }

    @Test void rejectsWithoutAccessStateRevisionIssueReportsOrConsent() throws Exception {
        error(generate(body(issue,""),UUID.randomUUID().toString(),workerCookie),403,"FORBIDDEN");
        error(generate(body(issue,""),UUID.randomUUID().toString(),outsiderCookie),404,"NOT_FOUND");
        error(send(post("/api/v1/events/"+event+"/change-generations","{\"issueId\":\""+issue+"\",\"instruction\":\"\",\"expectedEventRevision\":\"1\"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),409,"VERSION_CONFLICT");
        error(generate(body(otherIssue,""),UUID.randomUUID().toString(),chiefCookie),422,"INVALID_REFERENCE");
        UUID otherReport=report(otherIssue,null,"다른 행사 보고");
        error(generate("{\"issueId\":\""+issue+"\",\"instruction\":\"\",\"reportIds\":[\""+otherReport+"\"],\"expectedEventRevision\":\""+rev()+"\"}",
                UUID.randomUUID().toString(),chiefCookie),422,"INVALID_REFERENCE");
        // 형식: issueId 없음·모르는 필드·숫자 revision·중복 보고·키 없음
        for (String bad:List.of("{\"instruction\":\"\",\"expectedEventRevision\":\""+rev()+"\"}",
                "{\"issueId\":\""+issue+"\",\"instruction\":\"\",\"expectedEventRevision\":\""+rev()+"\",\"x\":1}",
                "{\"issueId\":\""+issue+"\",\"instruction\":\"\",\"expectedEventRevision\":"+rev()+"}",
                "{\"issueId\":\""+issue+"\",\"instruction\":\"\",\"reportIds\":[\""+report+"\",\""+report+"\"],\"expectedEventRevision\":\""+rev()+"\"}"))
            error(generate(bad,UUID.randomUUID().toString(),chiefCookie),400,"INVALID_REQUEST");
        error(send(post("/api/v1/events/"+event+"/change-generations",body(issue,"")).header("Cookie",chiefCookie)),400,"INVALID_REQUEST");
        // 모집·운영 중이 아니면 409
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        error(generate(body(issue,""),UUID.randomUUID().toString(),chiefCookie),409,"INVALID_STATE");
        jdbc.update("UPDATE event SET state='LIVE' WHERE id=?",event);
        // 외부 AI 동의가 없으면 403
        jdbc.update("UPDATE account SET consent='{}'::jsonb WHERE id=?",chief);
        var denied=generate(body(issue,""),UUID.randomUUID().toString(),chiefCookie);
        error(denied,403,"FORBIDDEN");
        assertThat(json.readTree(denied.body()).get("details").get("reason").asString()).isEqualTo("EXTERNAL_AI_CONSENT_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isZero();
    }

    @Test void readyResultBecomesAiChangeSetAndIsPublishedWithRecords() throws Exception {
        jdbc.update("""
                INSERT INTO operational_situation (id,event_id,issue_id,primary_type,related_types,source_kind,source_refs,
                                                   confirmed_by_member_id,confirmed_at,reason)
                VALUES (?,?,?,'EQUIPMENT_ANOMALY','["SCHEDULE_DELAY"]'::jsonb,'FORMAL_REPORT','[]'::jsonb,?,now(),'장비 고장 확인')""",
                UUID.randomUUID(),event,issue,chiefMember);
        AiJob job=accepted();
        var out=apply(job,ready(addTask(spareMember)));
        assertThat(out.state()).isEqualTo(AiJobState.SUCCEEDED);
        var result=json.readTree(out.resultJson());
        String changeSetId=result.get("changeSetId").asString();
        var cs=jdbc.queryForMap("SELECT mode,status,issue_id,source_job_id,created_by_member_id,revision FROM change_set WHERE id=?::uuid",changeSetId);
        assertThat(cs).containsEntry("mode","AI").containsEntry("status","DRAFT").containsEntry("issue_id",issue)
                .containsEntry("source_job_id",job.getId()).containsEntry("created_by_member_id",chiefMember);
        // 저장만 하고 계획은 바꾸지 않는다(검사만)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE event_id=?",Long.class,event)).isEqualTo(1);
        assertThat(result.get("situation").get("primaryType").asString()).isEqualTo("EQUIPMENT_ANOMALY");
        assertThat(result.get("situation").get("issueId").asString()).isEqualTo(issue.toString());

        // 063: 기록한 결과를 그대로 공개(계약 순서 + 참고 기록)
        jdbc.update("UPDATE ai_job SET state='SUCCEEDED', attempt=1, result=?::jsonb WHERE id=?",out.resultJson(),job.getId());
        var read=json.readTree(ok(send(get("/api/v1/ai-jobs/"+job.getId()).header("Cookie",chiefCookie))).body());
        assertThat(read.get("kind").asString()).isEqualTo("GENERATE_CHANGE");
        var body=read.get("result");
        assertThat(body.propertyNames()).containsExactly("changeSetId","operations","rationale","questions","evidenceRefs",
                "simulationRefs","situation","alternatives","coordination");
        assertThat(body.get("changeSetId").asString()).isEqualTo(changeSetId);
        assertThat(body.get("evidenceRefs").get(0).get("kind").asString()).isEqualTo("REPORT");
        assertThat(body.get("alternatives").get(0).get("summary").asString()).isEqualTo("팀원 2에게 맡기기");
        assertThat(body.get("coordination").get("graph").size()).isEqualTo(2);
        error(send(get("/api/v1/ai-jobs/"+job.getId()).header("Cookie",workerCookie)),404,"NOT_FOUND");

        // 변경안 조회에도 참고 기록이 붙고, 기존 미리보기로 이어진다
        var view=json.readTree(ok(send(get("/api/v1/change-sets/"+changeSetId).header("Cookie",chiefCookie))).body());
        assertThat(view.get("mode").asString()).isEqualTo("AI");
        assertThat(view.get("sourceJobId").asString()).isEqualTo(job.getId().toString());
        assertThat(view.get("evidenceRefs").size()).isEqualTo(2);
        assertThat(view.get("alternatives").size()).isEqualTo(1);
        assertThat(view.get("coordination").get("contributions").get(0).get("role").asString()).isEqualTo("SUPERVISOR");
        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+changeSetId+"/preview",
                "{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie))).body());
        assertThat(preview.get("status").asString()).isEqualTo("READY");
    }

    /** !324 리뷰: AI 실행 중 새 보고로 행사 revision이 올라도 결과를 버리지 않고, 지금 계획 기준으로 검사해 저장한다. */
    @Test void actualReportGeneratesChangeThenAppliesAndAcknowledges() throws Exception {
        var followup = send(post("/api/v1/issues/" + issue + "/reports", """
                {"kind":"FOLLOWUP","text":"예비 프로젝터 전환이 필요합니다","quickTemplateKey":null,"audioId":null}
                """).header("Cookie", workerCookie).header("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(followup.statusCode()).isEqualTo(201);
        var created = json.readTree(followup.body());
        var newReport = created.path("report");
        UUID reportId = UUID.fromString(newReport.path("id").asString());
        assertThat(reportId).isNotEqualTo(report);
        assertThat(newReport.path("issueId").asString()).isEqualTo(issue.toString());
        assertThat(newReport.path("authorMemberId").asString()).isEqualTo(workerMember.toString());
        String round = newReport.path("participationRevision").asString();
        assertThat(round).isEqualTo("2");
        assertThat(created.path("analysisJobId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM report WHERE id=? AND issue_id=? AND author_member_id=?
                    AND participation_revision=?::bigint AND kind='FOLLOWUP'
                """, Long.class, reportId, issue, workerMember, round)).isEqualTo(1);

        var input = json.createObjectNode().put("issueId", issue.toString())
                .put("instruction", "예비 프로젝터 전환 절차 추가").put("expectedEventRevision", Long.toString(rev()));
        input.putArray("reportIds").add(reportId.toString());
        var accepted = generate(input.toString(), UUID.randomUUID().toString(), chiefCookie);
        assertThat(accepted.statusCode()).isEqualTo(202);
        var jobReceipt = json.readTree(accepted.body());
        UUID jobId = UUID.fromString(jobReceipt.path("jobId").asString());
        String jobPath = "/api/v1/ai-jobs/" + jobId;
        assertThat(jobReceipt.path("statusUrl").asString()).isEqualTo(jobPath);
        assertThat(readJson(jobPath, chiefCookie).path("state").asString()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_set WHERE source_job_id=?", Long.class, jobId))
                .isZero();
        UUID requestEvent = pendingJobEvent(jobId, AiJobOutboxTypes.REQUESTED);
        String step = "예비 프로젝터로 전환";
        when(ai.post(eq("/internal/generate-change"), anyString(), any(Duration.class))).thenAnswer(call ->
                changeResponse(json.readTree(call.<String>getArgument(1)), reportId, step));

        relay.relayOnce();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(AiJobState.SUCCEEDED));
        var sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ai).post(eq("/internal/generate-change"), sent.capture(), any(Duration.class));
        var envelope = json.readTree(sent.getValue());
        assertThat(envelope.path("jobId").asString()).isEqualTo(jobId.toString());
        var reports = envelope.path("payload").path("issue").path("reports");
        assertThat(reports.size()).isEqualTo(1);
        assertThat(reports.get(0).path("id").asString()).isEqualTo(reportId.toString());
        UUID finishedEvent = pendingJobEvent(jobId, AiJobOutboxTypes.FINISHED);
        relay.relayOnce();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event WHERE id IN (?,?) AND processed_at IS NOT NULL
                """, Long.class, requestEvent, finishedEvent)).isEqualTo(2);

        var job = readJson(jobPath, chiefCookie);
        assertThat(job.path("state").asString()).isEqualTo("SUCCEEDED");
        var result = job.path("result");
        UUID changeId = UUID.fromString(result.path("changeSetId").asString());
        String changePath = "/api/v1/change-sets/" + changeId;
        var draft = readJson(changePath, chiefCookie);
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("mode").asString()).isEqualTo("AI");
        assertThat(draft.path("sourceJobId").asString()).isEqualTo(jobId.toString());
        assertThat(draft.path("issueId").asString()).isEqualTo(issue.toString());
        assertThat(draft.path("evidenceRefs")).isEqualTo(result.path("evidenceRefs"));
        assertThat(draft.path("evidenceRefs").size()).isEqualTo(1);
        assertThat(draft.path("evidenceRefs").get(0).path("kind").asString()).isEqualTo("REPORT");
        assertThat(draft.path("evidenceRefs").get(0).path("id").asString()).isEqualTo(reportId.toString());

        long currentRevision = rev();
        var applyBody = json.createObjectNode().put("expectedEventRevision", Long.toString(currentRevision))
                .put("expectedChangeSetRevision", draft.path("revision").asString());
        var ready = json.readTree(ok(send(post(changePath + "/preview", applyBody.toString())
                .header("Cookie", chiefCookie))).body());
        assertThat(ready.path("status").asString()).isEqualTo("READY");
        applyBody.put("expectedChangeSetRevision", ready.path("revision").asString())
                .put("previewHash", ready.path("previewHash").asString());
        String validApply = applyBody.toString();
        applyBody.put("expectedEventRevision", Long.toString(currentRevision - 1));
        var beforeApply = applySnapshot();
        error(send(post(changePath + "/apply", applyBody.toString()).header("Cookie", chiefCookie)
                .header("Idempotency-Key", UUID.randomUUID().toString())), 409, "VERSION_CONFLICT");
        assertThat(applySnapshot()).isEqualTo(beforeApply);

        String applyKey = UUID.randomUUID().toString();
        var applyRequest = post(changePath + "/apply", validApply).header("Cookie", chiefCookie)
                .header("Idempotency-Key", applyKey);
        var applied = json.readTree(ok(send(applyRequest)).body());
        assertThat(applied.path("id").asString()).isEqualTo(changeId.toString());
        assertThat(applied.path("status").asString()).isEqualTo("APPLIED");
        assertThat(rev()).isEqualTo(currentRevision + 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM change_set WHERE id=? AND source_job_id=? AND approved_by_member_id=?
                    AND status='APPLIED' AND applied_at IS NOT NULL
                """, Long.class, changeId, jobId, chiefMember)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT steps->>0 FROM task WHERE id=?", String.class, opening)).isEqualTo(step);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM operation_log WHERE change_set_id=? AND actor_member_id=? AND resource_id=?
                    AND type='UPDATE_TASK'
                """, Long.class, changeId, chiefMember, opening)).isEqualTo(1);
        UUID ackId = jdbc.queryForObject("""
                SELECT id FROM change_acknowledgment WHERE change_set_id=? AND member_id=?
                    AND participation_revision=?::bigint AND acknowledged_at IS NULL
                """, UUID.class, changeId, workerMember, round);
        UUID notificationId = jdbc.queryForObject("""
                SELECT n.id FROM notification n JOIN change_acknowledgment a ON a.id=n.acknowledgment_id
                JOIN change_set c ON c.id=n.change_set_id
                WHERE a.id=? AND n.change_set_id=a.change_set_id AND n.event_id=a.event_id AND n.member_id=a.member_id
                    AND n.participation_revision=a.participation_revision AND n.type='CHANGE_ACK'
                    AND n.created_at=c.applied_at AND n.read_at IS NULL
                """, UUID.class, ackId);
        var afterApply = applySnapshot();
        assertThat(json.readTree(ok(send(applyRequest)).body())).isEqualTo(applied);
        assertThat(applySnapshot()).isEqualTo(afterApply);

        var inbox = readJson("/api/v1/me/notifications", workerCookie).path("items");
        assertThat(inbox.size()).isEqualTo(1);
        var notification = inbox.get(0);
        assertThat(notification.path("id").asString()).isEqualTo(notificationId.toString());
        assertThat(notification.path("target").path("kind").asString()).isEqualTo("CHANGE_ACK");
        assertThat(notification.path("target").path("id").asString()).isEqualTo(ackId.toString());
        var read = json.readTree(ok(send(post("/api/v1/notifications/" + notificationId + "/read", "")
                .header("Cookie", workerCookie))).body());
        assertThat(read.path("readAt").asString()).isEqualTo(jdbc.queryForObject(
                "SELECT read_at FROM notification WHERE id=?", java.sql.Timestamp.class, notificationId).toInstant().toString());
        var ack = readJson("/api/v1/events/" + event + "/my-changes", workerCookie).path("items").get(0);
        assertThat(ack.path("id").asString()).isEqualTo(ackId.toString());
        assertThat(ack.path("state").asString()).isEqualTo("PENDING");
        assertThat(ack.path("targets").get(0).path("taskId").asString()).isEqualTo(opening.toString());
        String ackPath = "/api/v1/change-acknowledgments/" + ackId + "/ack";
        String ackBody = json.createObjectNode().put("targetsHash", ack.path("targetsHash").asString()).toString();
        var beforeAck = applySnapshot();
        UUID spareAccount = jdbc.queryForObject("SELECT account_id FROM member WHERE id=?", UUID.class, spareMember);
        error(send(post(ackPath, ackBody).header("Cookie", login(spareAccount))), 404, "NOT_FOUND");
        assertThat(applySnapshot()).isEqualTo(beforeAck);
        var confirmed = json.readTree(ok(send(post(ackPath, ackBody).header("Cookie", workerCookie))).body());
        assertThat(confirmed.path("state").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(confirmed.path("acknowledgedAt").asString()).isEqualTo(jdbc.queryForObject(
                "SELECT acknowledged_at FROM change_acknowledgment WHERE id=?", java.sql.Timestamp.class, ackId)
                .toInstant().toString());
        assertThat(json.readTree(jdbc.queryForObject(
                "SELECT acknowledged_targets::text FROM change_acknowledgment WHERE id=?", String.class, ackId)))
                .isEqualTo(confirmed.path("targets")).isEqualTo(ack.path("targets"));
        assertThat(readJson("/api/v1/events/" + event + "/my-changes", workerCookie).path("items").get(0))
                .isEqualTo(confirmed);
        verifyNoInteractions(push);
    }

    private JsonNode readJson(String path, String credential) throws Exception {
        return json.readTree(ok(send(get(path).header("Cookie", credential))).body());
    }

    private UUID pendingJobEvent(UUID jobId, String type) {
        return jdbc.queryForObject("""
                SELECT id FROM outbox_event WHERE event_id=? AND type=? AND payload->>'jobId'=? AND processed_at IS NULL
                """, UUID.class, event, type, jobId.toString());
    }

    private Map<String, Object> applySnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("revision", rev());
        for (String table : List.of("task", "change_set", "operation_log", "change_acknowledgment",
                "notification", "idempotency_record", "outbox_event")) {
            snapshot.put(table, jdbc.queryForObject(
                    "SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM " + table + " t WHERE event_id=?",
                    String.class, event));
        }
        return snapshot;
    }

    private AiResponse changeResponse(JsonNode request, UUID reportId, String step) {
        var response = json.createObjectNode();
        for (String field : List.of("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision")) {
            response.set(field, request.path(field));
        }
        response.put("resultSchemaVersion", "6").put("modelVersion", "change-test");
        var result = response.putObject("result").put("status", "READY").put("rationale", "추가 보고의 조치 반영");
        result.putArray("operations").addObject().put("type", "UPDATE_TASK").putObject("payload")
                .put("taskId", opening.toString()).putArray("steps").add(step);
        result.putArray("evidenceRefs").addObject().put("kind", "REPORT").put("id", reportId.toString())
                .putNull("revision").putNull("contentHash").putNull("observedAt").putArray("preconditions");
        result.putArray("alternatives");
        result.putObject("coordination").putArray("graph").addObject()
                .put("taskId", "t1").put("role", "SUPERVISOR").put("status", "SUCCEEDED");
        return new AiResponse(200, response.toString());
    }

    @Test void newReportsDuringGenerationDoNotDiscardTheResult() throws Exception {
        assertThat(kind.requiresCurrentContext()).isFalse();
        AiJob job=accepted();
        jdbc.update("UPDATE event SET revision=revision+2 WHERE id=?",event);   // 실행 중 보고 두 건
        var out=apply(job,ready(addTask(spareMember)));
        assertThat(out.state()).isEqualTo(AiJobState.SUCCEEDED);
        String changeSetId=json.readTree(out.resultJson()).get("changeSetId").asString();
        assertThat(jdbc.queryForObject("SELECT base_revision FROM change_set WHERE id=?::uuid",Long.class,changeSetId)).isEqualTo(rev());
        // 그 사이 업무가 사라져 연산이 지금 계획과 맞지 않으면 저장하지 않는다
        AiJob second=accepted2();
        String update="[{\"type\":\"UPDATE_TASK\",\"payload\":{\"taskId\":\""+opening+"\",\"endAt\":\"2026-11-14T09:30:00Z\"}}]";
        jdbc.update("UPDATE task SET status='CANCELED' WHERE id=?",opening);
        jdbc.update("DELETE FROM task WHERE id=?",opening);
        assertThat(apply(second,ready(update)).state()).isEqualTo(AiJobState.FAILED);
    }

    @Test void needsInfoHasNoChangeSet() throws Exception {
        AiJob job=accepted();
        String needs="{\"changeSetId\":null,\"operations\":[],\"rationale\":\"\",\"questions\":[{\"key\":\"zone\",\"field\":\"issue.zoneId\",\"question\":\"어느 구역인가요?\"}],"
                +"\"evidenceRefs\":[],\"simulationRefs\":[],\"situation\":null,\"alternatives\":[],\"coordination\":{\"graph\":[],\"redelegations\":[],\"resolvedConflicts\":[],\"contributions\":[]}}";
        var out=apply(job,AiJobOutcome.succeeded("6","m",needs));
        var result=json.readTree(out.resultJson());
        assertThat(result.get("changeSetId").isNull()).isTrue();
        assertThat(result.get("situation").isNull()).isTrue();   // 확인된 운영 상황이 없다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_set WHERE event_id=?",Long.class,event)).isZero();
    }

    @Test void operationsThatFailChangeSetChecksAreNotStored() throws Exception {
        AiJob job=accepted();
        // 다른 행사 구역에 업무 추가 → 변경안 검사(미리 적용) 실패 → 변경안 없음, 실패
        UUID otherZone=jdbc.queryForObject("SELECT id FROM zone WHERE event_id=?",UUID.class,other);
        String ops="[{\"type\":\"ADD_TASK\",\"payload\":{\"workGroupId\":\""+stageGroup+"\",\"bundleKey\":\"fix\",\"zoneId\":\""+otherZone+"\","
                +"\"programId\":null,\"title\":\"장비 조치\",\"startAt\":\"2026-11-14T09:05:00Z\",\"endAt\":\"2026-11-14T09:25:00Z\","
                +"\"steps\":[\"점검\"],\"checklist\":[],\"tempId\":\"tmp-fix\",\"assigneeId\":\""+spareMember+"\"}}]";
        var out=apply(job,ready(ops));
        assertThat(out.state()).isEqualTo(AiJobState.FAILED);
        assertThat(out.error().details()).containsEntry("reason","INVALID_RESULT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_set WHERE event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_set_evidence_revision WHERE change_set_id IN (SELECT id FROM change_set WHERE event_id=?)",
                Long.class,event)).isZero();
        // 요청한 총괄이 더는 총괄이 아니면 저장하지 않는다
        jdbc.update("UPDATE member SET event_role='WORKER' WHERE id=?",chiefMember);
        assertThat(apply(job,ready(addTask(spareMember))).state()).isEqualTo(AiJobState.FAILED);
    }

    // ---- 도우미 -------------------------------------------------------------------------------------------

    AiJob accepted() throws Exception {
        var r=generate(body(issue,"오프닝 20분 지연"),UUID.randomUUID().toString(),chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        return jobs.findById(UUID.fromString(json.readTree(r.body()).get("jobId").asString())).orElseThrow();
    }
    /** 앞 작업을 끝낸 뒤 새로 접수(행사당 진행 중 1개) */
    AiJob accepted2() throws Exception {
        jdbc.update("UPDATE ai_job SET state='SUCCEEDED', finished_at=now() WHERE event_id=? AND state IN ('QUEUED','RUNNING')",event);
        return accepted();
    }
    AiJobOutcome apply(AiJob job,AiJobOutcome outcome) {
        return new TransactionTemplate(transactionManager).execute(s->kind.apply(job,outcome));
    }
    String addTask(UUID assignee) {
        return "[{\"type\":\"ADD_TASK\",\"payload\":{\"workGroupId\":\""+stageGroup+"\",\"bundleKey\":\"fix\",\"zoneId\":\""+stage+"\","
                +"\"programId\":null,\"title\":\"장비 조치\",\"startAt\":\"2026-11-14T09:05:00Z\",\"endAt\":\"2026-11-14T09:25:00Z\","
                +"\"steps\":[\"고장 장비 확인\"],\"checklist\":[{\"key\":\"checked\",\"text\":\"점검 완료\",\"required\":true}],"
                +"\"tempId\":\"tmp-fix\",\"assigneeId\":\""+assignee+"\"}}]";
    }
    /** read()를 통과한 READY 결과 모양(apply 입력) */
    AiJobOutcome ready(String ops) {
        String ev="[{\"kind\":\"REPORT\",\"id\":\""+report+"\",\"revision\":null,\"contentHash\":null,\"observedAt\":null,\"preconditions\":[]},"
                +"{\"kind\":\"MEMBER\",\"id\":\""+spareMember+"\",\"revision\":null,\"contentHash\":null,\"observedAt\":null,\"preconditions\":[]}]";
        String coordination="{\"graph\":[{\"taskId\":\"t1\",\"parentTaskId\":null,\"role\":\"SUPERVISOR\",\"status\":\"SUCCEEDED\"},"
                +"{\"taskId\":\"t2\",\"parentTaskId\":\"t1\",\"role\":\"STAFFING\",\"status\":\"SUCCEEDED\"}],\"redelegations\":[],\"resolvedConflicts\":[],"
                +"\"contributions\":[{\"role\":\"SUPERVISOR\",\"summary\":\"인력에게 맡김\"},{\"role\":\"STAFFING\",\"summary\":\"팀원 3 선정\"}]}";
        return AiJobOutcome.succeeded("6","m","{\"changeSetId\":null,\"operations\":"+ops+",\"rationale\":\"프로젝터 조치\",\"questions\":[],"
                +"\"evidenceRefs\":"+ev+",\"simulationRefs\":[],\"situation\":null,\"alternatives\":[{\"summary\":\"팀원 2에게 맡기기\"}],"
                +"\"coordination\":"+coordination+"}");
    }
    String body(UUID issue,String instruction) {
        return "{\"issueId\":\""+issue+"\",\"instruction\":\""+instruction+"\",\"expectedEventRevision\":\""+rev()+"\"}";
    }
    HttpResponse<String> generate(String body,String key,String cookie) throws Exception {
        return send(post("/api/v1/events/"+event+"/change-generations",body).header("Cookie",cookie).header("Idempotency-Key",key));
    }
    long rev() { return jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event); }
    HttpResponse<String> ok(HttpResponse<String> r) { assertThat(r.statusCode()).as(r.body()).isEqualTo(200); return r; }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{\"externalAi\":true}'::jsonb, now())",id);
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
                VALUES (?,?,?,'b1',?,?,NULL,NULL,?,?::timestamptz,?::timestamptz,'["진행"]'::jsonb,'[]'::jsonb,'WAITING',now())""",
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
    UUID issue(UUID event,UUID group,UUID zone,UUID reporter) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO issue (id,event_id,work_group_id,task_id,zone_id,title,reporter_member_id,status,related_issue_id,created_at,closed_at)
                VALUES (?,?,?,NULL,?,'장비',?,'RECEIVED',NULL,now(),NULL)""",id,event,group,zone,reporter);
        return id;
    }
    UUID report(UUID issue,UUID author,String text) {
        var id=UUID.randomUUID();
        UUID by=author!=null?author:jdbc.queryForObject("SELECT reporter_member_id FROM issue WHERE id=?",UUID.class,issue);
        jdbc.update("""
                INSERT INTO report (id,issue_id,author_member_id,kind,text,normalized_text,audio_id,quick_template_key,analysis,created_at,participation_revision)
                VALUES (?,?,?,'INITIAL',?,?,NULL,NULL,NULL,now(),1)""",id,issue,by,text,text);
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
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Encoder {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}
