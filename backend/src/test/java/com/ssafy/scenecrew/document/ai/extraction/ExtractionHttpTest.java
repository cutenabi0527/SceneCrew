package com.ssafy.scenecrew.document.ai.extraction;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.support.FakeFileStorage;
import com.ssafy.scenecrew.support.FileTestBeans;
import static org.assertj.core.api.Assertions.*;

/**
 * #236 SC-API-049 문서 추출 접수·AI 서버용 문서 내려받기·063 결과 조회의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis.
 * 저장소는 가짜(FileTestBeans)이고 outbox 주기 실행이 꺼져 있어 AI 서버를 부르지 않는다. 결과는 SQL로 작업을 끝내 확인한다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import({FileTestBeans.class, ExtractionHttpTest.Beans.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ExtractionHttpTest {
    static final String WEB="https://web.example.invalid", TOKEN="test-service-token";
    static final byte[] MD="# 공연 안내\n날짜 2026-11-18, 장소 합성 시연장".getBytes(StandardCharsets.UTF_8);
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"extraction-test"); r.add("scenecrew.auth.audience",()->"extraction-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
        r.add("scenecrew.ai.server.service-token",()->TOKEN);
        r.add("scenecrew.ai.extraction.document-base-url",()->"https://app.example.invalid/");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired FakeFileStorage storage;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>();
    UUID chief, worker, outsider, event, document;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        storage.reset();
        chief=account(); worker=account(); outsider=account();
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
        event=create();
        var r=upload(event,MD);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        document=UUID.fromString(json.readTree(r.body()).get("files").get(0).get("document").get("id").asString());
        UUID group=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'안내조','',true)",group,event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,2,'WORKER','팀원',true,false,true,1)""",UUID.randomUUID(),event,worker,group);
    }
    @AfterEach void cleanup() {
        storage.reset();
        for (var a:accounts) {
            for (var e:jdbc.queryForList("SELECT DISTINCT event_id FROM member WHERE account_id=?",UUID.class,a)) {
                jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
                jdbc.update("DELETE FROM outbox_event WHERE event_id=?",e);
                jdbc.update("DELETE FROM ai_job WHERE event_id=?",e);
                jdbc.update("DELETE FROM intake_document WHERE event_id=?",e);
                jdbc.update("DELETE FROM member WHERE event_id=?",e);
                jdbc.update("DELETE FROM work_group WHERE event_id=?",e);
                jdbc.update("DELETE FROM event WHERE id=?",e);
            }
        }
        for (var a:accounts) {
            jdbc.update("DELETE FROM idempotency_record WHERE actor_account_id=?",a);
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        accounts.clear();
    }

    @Test void acceptQueuesOneExtractionAndSameKeyReturnsFirstResponse() throws Exception {
        String key=UUID.randomUUID().toString();
        var r=extract(event,key,revision(),chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("jobId","statusUrl");
        UUID job=UUID.fromString(body.get("jobId").asString());
        assertThat(body.get("statusUrl").asString()).isEqualTo("/api/v1/ai-jobs/"+job);
        var row=jdbc.queryForMap("SELECT kind,state,context_revision,input::text AS input FROM ai_job WHERE id=?",job);
        assertThat(row).containsEntry("kind","EXTRACT_EVENT_INPUT").containsEntry("state","QUEUED");
        assertThat((Long)row.get("context_revision")).isEqualTo(revision());
        var input=json.readTree((String)row.get("input")).get("documents").get(0);
        assertThat(input.get("documentId").asString()).isEqualTo(document.toString());
        assertThat(input.get("name").asString()).isEqualTo("안내.md");
        assertThat(input.get("url").asString()).isEqualTo("https://app.example.invalid/internal/intake-documents/"+document);
        // 같은 키(대소문자만 다름)는 새 작업 없이 최초 202
        var again=extract(event,key.toUpperCase(),revision(),chiefCookie);
        assertThat(again.statusCode()).isEqualTo(202);
        assertThat(json.readTree(again.body())).isEqualTo(body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isEqualTo(1);
        // 진행 중인 추출이 있으면 새 키는 429(계정당 1개)
        error(extract(event,UUID.randomUUID().toString(),revision(),chiefCookie),429,"RATE_LIMITED");
        // 추출 중에는 문서를 바꾸지 못한다(기존 규칙)
        error(upload(event,MD),409,"INVALID_STATE");
    }

    @Test void rejectsWithoutAccessStateRevisionDocumentsOrConsent() throws Exception {
        long rev=revision();
        error(extract(event,UUID.randomUUID().toString(),rev,workerCookie),403,"FORBIDDEN");
        error(extract(event,UUID.randomUUID().toString(),rev,outsiderCookie),404,"NOT_FOUND");
        error(extract(UUID.randomUUID(),UUID.randomUUID().toString(),rev,chiefCookie),404,"NOT_FOUND");
        var stale=extract(event,UUID.randomUUID().toString(),rev-1,chiefCookie);
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("event");
        // 외부 AI 동의가 없으면 403
        jdbc.update("UPDATE account SET consent='{}'::jsonb WHERE id=?",chief);
        var denied=extract(event,UUID.randomUUID().toString(),rev,chiefCookie);
        error(denied,403,"FORBIDDEN");
        assertThat(json.readTree(denied.body()).get("details").get("reason").asString()).isEqualTo("EXTERNAL_AI_CONSENT_REQUIRED");
        jdbc.update("UPDATE account SET consent='{\"externalAi\":true}'::jsonb WHERE id=?",chief);
        // 문서가 없으면 409
        jdbc.update("UPDATE intake_document SET storage_key=NULL WHERE event_id=?",event);
        error(extract(event,UUID.randomUUID().toString(),rev,chiefCookie),409,"INVALID_STATE");
        // DRAFT가 아니면 409
        jdbc.update("UPDATE intake_document SET storage_key='restored' WHERE event_id=?",event);
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        error(extract(event,UUID.randomUUID().toString(),rev,chiefCookie),409,"INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isZero();
        // 형식 오류
        for (String body:List.of("{}","{\"expectedEventRevision\":1}","{\"expectedEventRevision\":\"01\"}",
                "{\"expectedEventRevision\":\"1\",\"x\":1}","[]"))
            error(send(post("/api/v1/events/"+event+"/extractions",body).header("Cookie",chiefCookie)
                    .header("Idempotency-Key",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(post("/api/v1/events/"+event+"/extractions","{\"expectedEventRevision\":\"1\"}").header("Cookie",chiefCookie)),
                400,"INVALID_REQUEST");
        error(send(post("/api/v1/events/"+event+"/extractions","{\"expectedEventRevision\":\"1\"}")
                .header("Idempotency-Key",UUID.randomUUID().toString())),401,"UNAUTHENTICATED");
    }

    @Test void aiServerDownloadsOnlyWithRunningJobAndAttempt() throws Exception {
        var r=extract(event,UUID.randomUUID().toString(),revision(),chiefCookie);
        UUID job=UUID.fromString(json.readTree(r.body()).get("jobId").asString());
        // 아직 실행 전(QUEUED)이면 내려받을 수 없다.
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE ai_job SET state='RUNNING', attempt=1, lease_until=now()+interval '10 minutes' WHERE id=?",job);

        var ok=client.send(downloadRequest(document,TOKEN,job.toString(),"1"),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo(MD);
        assertThat(ok.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(ok.headers().firstValue("Content-Length")).hasValue(String.valueOf(MD.length));

        // 서비스 인증 실패는 401, 인증 뒤 조건이 맞지 않으면 이유와 관계없이 404다.
        assertThat(download(document,"wrong",job.toString(),"1").statusCode()).isEqualTo(401);
        assertThat(download(document,null,job.toString(),"1").statusCode()).isEqualTo(401);
        assertThat(download(document,TOKEN,null,"1").statusCode()).isEqualTo(404);
        assertThat(download(document,TOKEN,job.toString(),null).statusCode()).isEqualTo(404);
        assertThat(download(document,TOKEN,UUID.randomUUID().toString(),"1").statusCode()).isEqualTo(404);
        assertThat(download(document,TOKEN,job.toString(),"2").statusCode()).isEqualTo(404);
        assertThat(download(document,TOKEN,job.toString(),"01").statusCode()).isEqualTo(404);
        assertThat(download(UUID.randomUUID(),TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);

        // 행사가 DRAFT가 아니거나, 요청한 사람이 더는 총괄이 아니거나, 문서가 만료되면 404다.
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE event SET state='DRAFT' WHERE id=?",event);
        jdbc.update("UPDATE member SET event_role='WORKER' WHERE event_id=? AND account_id=?",event,chief);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE member SET event_role='CHIEF' WHERE event_id=? AND account_id=?",event,chief);
        jdbc.update("UPDATE intake_document SET expires_at=now()-interval '1 second' WHERE id=?",document);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE intake_document SET expires_at=now()+interval '1 day' WHERE id=?",document);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(200);

        // 작업이 끝나거나 마감이 지나면 더는 내려받을 수 없다.
        jdbc.update("UPDATE ai_job SET deadline_at=now()-interval '1 second' WHERE id=?",job);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE ai_job SET deadline_at=now()+interval '10 minutes', state='FAILED' WHERE id=?",job);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
    }

    /** V9-D54(#391): 계획 생성 작업은 입력 floorPlan의 문서만 내려받는다. 나머지 확인은 추출과 같다. */
    @Test void planJobDownloadsOnlyItsFloorPlanDocument() throws Exception {
        UUID chiefMember=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,event,chief);
        UUID job=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_job (id,event_id,requested_by_member_id,kind,state,input_hash,context_revision,request_schema_version,
                                    input,attempt,execution_meta,auto_reanalysis_count,created_at,deadline_at,lease_until)
                VALUES (?,?,?,'GENERATE_PLAN','RUNNING',?,0,'4',?::jsonb,1,'{}'::jsonb,0,now(),now()+interval '10 minutes',
                        now()+interval '10 minutes')""",
                job,event,chiefMember,"a".repeat(64),"{\"floorPlan\":{\"documentId\":\""+document+"\",\"pageNumber\":1}}");
        var ok=client.send(downloadRequest(document,TOKEN,job.toString(),"1"),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo(MD);
        // 다른 문서, 평면도 없는 계획 작업, 문서 목록만 있는 계획 작업은 404
        assertThat(download(UUID.randomUUID(),TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE ai_job SET input='{\"floorPlan\":null}'::jsonb WHERE id=?",job);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE ai_job SET input=?::jsonb WHERE id=?","{\"documents\":[{\"documentId\":\""+document+"\"}]}",job);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
        // 만료·작업 종료면 404
        jdbc.update("UPDATE ai_job SET input=?::jsonb WHERE id=?","{\"floorPlan\":{\"documentId\":\""+document+"\",\"pageNumber\":1}}",job);
        jdbc.update("UPDATE intake_document SET expires_at=now()-interval '1 second' WHERE id=?",document);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE intake_document SET expires_at=now()+interval '1 day' WHERE id=?",document);
        jdbc.update("UPDATE ai_job SET state='SUCCEEDED' WHERE id=?",job);
        assertThat(download(document,TOKEN,job.toString(),"1").statusCode()).isEqualTo(404);
    }

    @Test void chiefReadsFloorPlanPagesOnlyWhenTheResultHasThem() throws Exception {
        var r=extract(event,UUID.randomUUID().toString(),revision(),chiefCookie);
        UUID job=UUID.fromString(json.readTree(r.body()).get("jobId").asString());
        String result="{\"fields\":{},\"missingRequired\":[],\"conflictingValues\":[],\"unreadableFiles\":[],"
                +"\"floorPlanPages\":[{\"documentId\":\""+document+"\",\"pageNumber\":2}],\"resultStatus\":\"COMPLETE\"}";
        jdbc.update("UPDATE ai_job SET state='SUCCEEDED', attempt=1, result=?::jsonb WHERE id=?",result,job);
        var body=read(job,chiefCookie).get("result");
        assertThat(body.propertyNames()).containsExactly("fields","missingRequired","conflictingValues","unreadableFiles",
                "floorPlanPages","resultStatus");
        assertThat(body.get("floorPlanPages").get(0).get("pageNumber").asInt()).isEqualTo(2);
    }

    @Test void chiefReadsExtractionResultIncludingPartial() throws Exception {
        var r=extract(event,UUID.randomUUID().toString(),revision(),chiefCookie);
        UUID job=UUID.fromString(json.readTree(r.body()).get("jobId").asString());
        var queued=read(job,chiefCookie);
        assertThat(queued.get("kind").asString()).isEqualTo("EXTRACT_EVENT_INPUT");
        assertThat(queued.get("state").asString()).isEqualTo("QUEUED");
        error(send(get("/api/v1/ai-jobs/"+job).header("Cookie",workerCookie)),404,"NOT_FOUND");

        String result="{\"fields\":{\"date\":\"2026-11-18\",\"locationName\":\"합성 시연장\"},\"missingRequired\":[\"totalCapacity\"],"
                +"\"conflictingValues\":[{\"field\":\"openAt\",\"values\":[\"18:00\",\"18:30\"]}],"
                +"\"unreadableFiles\":[{\"documentId\":\""+document+"\",\"code\":\"OCR_FAILED\"}],\"resultStatus\":\"PARTIAL\"}";
        jdbc.update("UPDATE ai_job SET state='PARTIAL', attempt=1, result=?::jsonb WHERE id=?",result,job);
        var partial=read(job,chiefCookie);
        assertThat(partial.get("state").asString()).isEqualTo("PARTIAL");
        assertThat(partial.get("error").isNull()).isTrue();
        var body=partial.get("result");
        assertThat(body.propertyNames()).containsExactly("fields","missingRequired","conflictingValues","unreadableFiles","resultStatus");
        assertThat(body.get("fields").get("locationName").asString()).isEqualTo("합성 시연장");
        assertThat(body.get("conflictingValues").get(0).get("values").size()).isEqualTo(2);
        assertThat(body.get("unreadableFiles").get(0).get("code").asString()).isEqualTo("OCR_FAILED");

        jdbc.update("UPDATE ai_job SET state='STALE_CONTEXT', result=NULL, error=?::jsonb WHERE id=?",
                "{\"code\":\"READ_CONTEXT_CHANGED\",\"message\":\"m\",\"retryable\":false}",job);
        var stale=read(job,chiefCookie);
        assertThat(stale.get("state").asString()).isEqualTo("FAILED");
        assertThat(stale.get("error").get("code").asString()).isEqualTo("STALE_CONTEXT");
    }

    // ---- 도우미 -------------------------------------------------------------------------------------------

    long revision() { return jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event); }
    HttpResponse<String> extract(UUID event,String key,long revision,String cookie) throws Exception {
        return send(post("/api/v1/events/"+event+"/extractions","{\"expectedEventRevision\":\""+revision+"\"}")
                .header("Cookie",cookie).header("Idempotency-Key",key));
    }
    HttpRequest downloadRequest(UUID document,String token,String jobId,String attempt) {
        var b=HttpRequest.newBuilder(URI.create(url("/internal/intake-documents/"+document))).GET();
        if (token!=null) b.header("Authorization","Bearer "+token);
        if (jobId!=null) b.header("X-SceneCrew-Job-Id",jobId);
        if (attempt!=null) b.header("X-SceneCrew-Job-Attempt",attempt);
        return b.build();
    }
    HttpResponse<String> download(UUID document,String token,String jobId,String attempt) throws Exception {
        return client.send(downloadRequest(document,token,jobId,attempt),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode read(UUID job,String cookie) throws Exception {
        var r=send(get("/api/v1/ai-jobs/"+job).header("Cookie",cookie));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
    HttpResponse<String> upload(UUID event,byte[] content) throws Exception {
        String boundary="----scenecrew"+UUID.randomUUID().toString().replace("-","");
        var out=new ByteArrayOutputStream();
        out.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\"안내.md\"\r\nContent-Type: text/markdown\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/documents")))
                .header("Content-Type","multipart/form-data; boundary="+boundary).header("Origin",WEB).header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())));
    }
    UUID create() throws Exception {
        var r=send(post("/api/v1/events","{\"name\":\"추출 시험 공연\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(r.body()).get("id").asString());
    }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{\"externalAi\":true}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    String login(UUID account) throws Exception {
        var r=send(post("/api/v1/auth/login","{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1"));
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
    @TestConfiguration(proxyBeanMethods=false) static class Beans {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}
