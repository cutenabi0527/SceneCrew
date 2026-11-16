package com.ssafy.scenecrew.event.summary;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
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
import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.aijob.client.AiClient;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.client.AiTransportException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/**
 * #269 행사 종료 요약(080). 실제 HTTP·Security·PostgreSQL·Redis. AI 서버 대신 FakeSummary가 ai/summary 계약 모양으로 답한다.
 * 확인: 요약문은 응답에만 있고 DB(ai_job)에 남지 않는다. 같은 키 재요청은 AI를 다시 부르지 않는다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({EventSummaryHttpTest.Beans.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EventSummaryHttpTest {
    static final String WEB="https://web.example.invalid";
    static final String TEXT="## 개요\n가을 공연을 마쳤습니다.";
    static final JsonMapper MAPPER=JsonMapper.builder().build();
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"summary-test"); r.add("scenecrew.auth.audience",()->"summary-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired FakeSummary ai;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    UUID chief, worker, outsider, event, group, zone, workerMember;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        ai.reset();
        ai.answer(this::ok);
        chief=account(); worker=account(); outsider=account();
        event=event("ENDED");
        jdbc.update("UPDATE event SET open_at='2026-11-14T09:00:00Z', close_at='2026-11-14T12:00:00Z', ended_at='2026-11-14T13:10:00Z', location_name='합성 시연장', total_capacity=12 WHERE id=?",event);
        group=group(event,"무대 운영"); zone=zone(event,"무대");
        var chiefMember=member(event,chief,"CHIEF",null,"김총괄");
        workerMember=member(event,worker,"WORKER",group,"오하늘");
        task(event,group,workerMember,zone,"DONE"); task(event,group,workerMember,zone,"CANCELED");
        var issue=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO issue (id,event_id,work_group_id,task_id,zone_id,title,reporter_member_id,status,related_issue_id,created_at,closed_at)
                VALUES (?,?,?,NULL,?,'프로젝터 꺼짐',?,'CLOSED',NULL,'2026-11-14T09:02:00Z','2026-11-14T09:30:00Z')""",issue,event,group,zone,workerMember);
        jdbc.update("""
                INSERT INTO report (id,issue_id,author_member_id,kind,text,normalized_text,audio_id,quick_template_key,analysis,created_at,participation_revision)
                VALUES (?,?,?,'INITIAL','오하늘입니다 프로젝터가 안 나와요','x',NULL,NULL,NULL,now(),1)""",UUID.randomUUID(),issue,workerMember);
        jdbc.update("""
                INSERT INTO operational_situation (id,event_id,issue_id,primary_type,related_types,source_kind,source_refs,confirmed_by_member_id,confirmed_at,reason)
                VALUES (?,?,?,'EQUIPMENT_ANOMALY','[]'::jsonb,'FORMAL_REPORT','[]'::jsonb,?,now(),'확인')""",UUID.randomUUID(),event,issue,chiefMember);
        var other=event("ENDED"); member(other,outsider,"CHIEF",null,"외부");
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
    }
    @AfterEach void cleanup() {
        for (var e:events) {
            jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
            jdbc.update("DELETE FROM outbox_event WHERE event_id=?",e);
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

    @Test void summaryIsReturnedOnceAndNeverStored() throws Exception {
        String key=UUID.randomUUID().toString();
        var r=summarize(key,rev(),chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactly("text","format","contextRevision");
        assertThat(body.get("text").asString()).isEqualTo(TEXT);
        assertThat(body.get("format").asString()).isEqualTo("MARKDOWN");
        assertThat(body.get("contextRevision").asString()).isEqualTo(Long.toString(rev()));
        // AI에는 한 번, 사실만(이름·보고 원문 없음) 보냈다
        assertThat(ai.calls).hasSize(1);
        var sent=ai.calls.get(0);
        assertThat(ai.paths).containsExactly("/internal/summarize-event");
        assertThat(sent.get("kind").asString()).isEqualTo("SUMMARIZE_EVENT");
        var payload=sent.get("payload");
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("event","workGroups","programs","issues","changes");
        assertThat(payload.get("workGroups").get(0).get("tasks").get("done").asLong()).isEqualTo(1);
        assertThat(payload.get("workGroups").get(0).get("tasks").get("canceled").asLong()).isEqualTo(1);
        assertThat(payload.get("issues").get(0).get("situation").asString()).isEqualTo("EQUIPMENT_ANOMALY");
        assertThat(payload.get("issues").get(0).get("reports").asLong()).isEqualTo(1);
        assertThat(sent.toString()).doesNotContain("오하늘").doesNotContain("김총괄").doesNotContain(workerMember.toString());
        // 시도 기록은 남고 요약문은 어디에도 없다
        var job=jdbc.queryForMap("SELECT state, result::text AS result, result_schema_version FROM ai_job WHERE event_id=? AND kind='SUMMARIZE_EVENT'",event);
        assertThat(job).containsEntry("state","SUCCEEDED").containsEntry("result_schema_version","2");
        assertThat(job.get("result")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=? AND row_to_json(ai_job)::text LIKE ?",Long.class,event,"%가을 공연을 마쳤습니다%")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE event_id=?",Long.class,event)).isZero();

        // 같은 키: AI를 다시 부르지 않고 410. 본문이 다르면 409 MISMATCH
        error(summarize(key,rev(),chiefCookie),410,"TEXT_NOT_RETAINED");
        error(summarize(key.toUpperCase(),rev(),chiefCookie),410,"TEXT_NOT_RETAINED");
        error(summarize(key,rev()+1,chiefCookie),409,"IDEMPOTENCY_MISMATCH");
        assertThat(ai.calls).hasSize(1);
        // 새 키는 새로 만든다
        assertThat(summarize(UUID.randomUUID().toString(),rev(),chiefCookie).statusCode()).isEqualTo(200);
        assertThat(ai.calls).hasSize(2);
    }

    @Test void definiteAiFailureIs503AndSameKeyDoesNotCallAgain() throws Exception {
        ai.answer(req->new AiResponse(503,"{\"jobId\":\""+req.get("jobId").asString()+"\",\"attempt\":1,\"requestSchemaVersion\":\"4\","
                +"\"inputHash\":\""+req.get("inputHash").asString()+"\",\"contextRevision\":"+req.get("contextRevision").asLong()+","
                +"\"error\":{\"code\":\"AI_UNAVAILABLE\",\"message\":\"m\",\"retryable\":true,\"details\":{}}}"));
        String key=UUID.randomUUID().toString();
        error(summarize(key,rev(),chiefCookie),503,"SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT state FROM ai_job WHERE event_id=? AND kind='SUMMARIZE_EVENT'",String.class,event)).isEqualTo("FAILED");
        error(summarize(key,rev(),chiefCookie),503,"SERVICE_UNAVAILABLE");
        assertThat(ai.calls).hasSize(1);
    }

    @Test void unknownOutcomeAndInvalidResultAreNotStoredOrRetried() throws Exception {
        // 응답 시간 초과: AI가 만들었는지 모른다 → 같은 키는 409 OUTCOME_UNKNOWN
        ai.fail(true);
        String key=UUID.randomUUID().toString();
        error(summarize(key,rev(),chiefCookie),503,"SERVICE_UNAVAILABLE");
        error(summarize(key,rev(),chiefCookie),409,"IDEMPOTENCY_OUTCOME_UNKNOWN");
        assertThat(ai.calls).hasSize(1);
        // 연결 실패는 확실한 실패 → 같은 키는 503
        ai.fail(false);
        String key2=UUID.randomUUID().toString();
        error(summarize(key2,rev(),chiefCookie),503,"SERVICE_UNAVAILABLE");
        error(summarize(key2,rev(),chiefCookie),503,"SERVICE_UNAVAILABLE");
        // 계약과 다른 응답(다른 작업·빈 요약·모르는 형식·버전)은 FAILED, 요약문을 내보내지 않는다
        ai.fail(null);
        List<Function<JsonNode,AiResponse>> bad=List.of(
                req->{ var b=okBody(req); b.put("jobId",UUID.randomUUID().toString()); return new AiResponse(200,b.toString()); },
                req->{ var b=okBody(req); ((ObjectNode)b.get("result")).put("text"," "); return new AiResponse(200,b.toString()); },
                req->{ var b=okBody(req); ((ObjectNode)b.get("result")).put("format","HTML"); return new AiResponse(200,b.toString()); },
                req->{ var b=okBody(req); b.put("resultSchemaVersion","1"); return new AiResponse(200,b.toString()); },
                req->new AiResponse(200,"not json"));
        for (var answer:bad) {
            ai.answer(answer);
            error(summarize(UUID.randomUUID().toString(),rev(),chiefCookie),503,"SERVICE_UNAVAILABLE");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=? AND kind='SUMMARIZE_EVENT' AND state='SUCCEEDED'",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=? AND result IS NOT NULL",Long.class,event)).isZero();
    }

    @Test void runningRequestWithSameKeyIsInProgress() throws Exception {
        String key=UUID.randomUUID().toString();
        assertThat(summarize(key,rev(),chiefCookie).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE ai_job SET state='RUNNING', finished_at=NULL, deadline_at=now()+interval '1 minute' WHERE event_id=? AND kind='SUMMARIZE_EVENT'",event);
        error(summarize(key,rev(),chiefCookie),409,"REQUEST_IN_PROGRESS");
        jdbc.update("UPDATE ai_job SET deadline_at=now()-interval '1 second' WHERE event_id=? AND kind='SUMMARIZE_EVENT'",event);
        error(summarize(key,rev(),chiefCookie),409,"IDEMPOTENCY_OUTCOME_UNKNOWN");
        assertThat(ai.calls).hasSize(1);
    }

    @Test void rejectsWithoutAccessStateRevisionConsentOrKey() throws Exception {
        error(summarize(UUID.randomUUID().toString(),rev(),workerCookie),403,"FORBIDDEN");
        error(summarize(UUID.randomUUID().toString(),rev(),outsiderCookie),404,"NOT_FOUND");
        error(summarize(UUID.randomUUID().toString(),rev()+1,chiefCookie),409,"VERSION_CONFLICT");
        jdbc.update("UPDATE event SET state='LIVE' WHERE id=?",event);
        error(summarize(UUID.randomUUID().toString(),rev(),chiefCookie),409,"INVALID_STATE");
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        jdbc.update("UPDATE account SET consent='{}'::jsonb WHERE id=?",chief);
        var denied=summarize(UUID.randomUUID().toString(),rev(),chiefCookie);
        error(denied,403,"FORBIDDEN");
        assertThat(json.readTree(denied.body()).get("details").get("reason").asString()).isEqualTo("EXTERNAL_AI_CONSENT_REQUIRED");
        jdbc.update("UPDATE account SET consent='{\"externalAi\":true}'::jsonb WHERE id=?",chief);
        for (String bad:List.of("{}","{\"expectedEventRevision\":"+rev()+"}","{\"expectedEventRevision\":\""+rev()+"\",\"x\":1}"))
            error(send(post("/api/v1/events/"+event+"/summary",bad).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(post("/api/v1/events/"+event+"/summary","{\"expectedEventRevision\":\""+rev()+"\"}").header("Cookie",chiefCookie)),400,"INVALID_REQUEST");
        error(send(post("/api/v1/events/"+event+"/summary","{\"expectedEventRevision\":\""+rev()+"\"}")),401,"UNAUTHENTICATED");
        assertThat(ai.calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isZero();
    }

    // ---- 도우미 -------------------------------------------------------------------------------------------

    ObjectNode okBody(JsonNode req) {
        var b=(ObjectNode)json.readTree("{\"resultSchemaVersion\":\"2\",\"modelVersion\":\"summary-gpt-4o\",\"result\":{\"text\":\"\",\"format\":\"MARKDOWN\"}}");
        b.put("jobId",req.get("jobId").asString()).put("attempt",1).put("requestSchemaVersion","4")
                .put("inputHash",req.get("inputHash").asString()).put("contextRevision",req.get("contextRevision").asLong());
        ((ObjectNode)b.get("result")).put("text",TEXT);
        return b;
    }
    AiResponse ok(JsonNode req) { return new AiResponse(200,okBody(req).toString()); }
    HttpResponse<String> summarize(String key,long revision,String cookie) throws Exception {
        return send(post("/api/v1/events/"+event+"/summary","{\"expectedEventRevision\":\""+revision+"\"}").header("Cookie",cookie).header("Idempotency-Key",key));
    }
    long rev() { return jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event); }
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
                id,"가을 공연",state,7L,LocalDate.of(2026,11,14));
        return id;
    }
    UUID group(UUID event,String name) { var id=UUID.randomUUID(); jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?, ?, ?, '', true)",id,event,name); return id; }
    UUID zone(UUID event,String name) { var id=UUID.randomUUID(); jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,?,NULL,'[]'::jsonb)",id,event,name); return id; }
    void task(UUID event,UUID group,UUID assignee,UUID zone,String status) {
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status,first_assigned_at)
                VALUES (?,?,?,'b1',?,?,NULL,NULL,'업무','2026-11-14T09:00:00Z','2026-11-14T10:00:00Z','["진행"]'::jsonb,'[]'::jsonb,?,now())""",
                UUID.randomUUID(),event,group,assignee,zone,status);
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
    HttpRequest.Builder post(String path,String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json").header("Origin",WEB)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);
        assertThat(r.body()).doesNotContain("가을 공연을 마쳤습니다");
    }

    /** 받은 요청을 남기고 정해 둔 답을 돌려주는 가짜 AI 서버. fail(true)=응답 시간 초과, fail(false)=연결 실패 */
    static final class FakeSummary implements AiClient {
        final List<JsonNode> calls=new CopyOnWriteArrayList<>();
        final List<String> paths=new CopyOnWriteArrayList<>();
        private volatile Function<JsonNode,AiResponse> answer;
        private volatile Boolean failTimeout;
        void answer(Function<JsonNode,AiResponse> answer) { this.answer=answer; }
        void fail(Boolean timeout) { this.failTimeout=timeout; }
        void reset() { calls.clear(); paths.clear(); answer=null; failTimeout=null; }
        @Override public AiResponse post(String path,String jsonBody,Duration timeout) throws AiTransportException {
            JsonNode request=MAPPER.readTree(jsonBody);
            calls.add(request); paths.add(path);
            if (failTimeout!=null) throw new AiTransportException("fake",failTimeout,null);
            return answer.apply(request);
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Beans {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
        @Bean @Primary FakeSummary fakeSummary() { return new FakeSummary(); }
    }
}
