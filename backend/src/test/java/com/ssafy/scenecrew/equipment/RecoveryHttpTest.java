package com.ssafy.scenecrew.equipment;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
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
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** 복구 대안 EXT06·EXT07·Q1과 후보로 만든 변경안(066 simulationRefs)·적용의 실제 HTTP·Security·PostgreSQL·Redis. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RecoveryHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RecoveryHttpTest {
    static final String WEB="https://web.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"recovery-test"); r.add("scenecrew.auth.audience",()->"recovery-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    UUID chief, worker, outsider, event, group, zone, chiefMember, workerMember, program, mic, spare, cable, requirement, held, issue;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); worker=account(); outsider=account();
        event=event("LIVE");
        group=uuid(); jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'음향','',true)",group,event);
        zone=uuid(); jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,'무대',NULL,'[]'::jsonb)",zone,event);
        chiefMember=member(event,chief,"CHIEF",null,"김하준"); workerMember=member(event,worker,"WORKER",group,"오하늘");
        program=uuid(); jdbc.update("INSERT INTO program (id,event_id,zone_id,name,start_at,end_at) VALUES (?,?,?,'1부 공연','2026-11-14T09:00:00Z','2026-11-14T10:00:00Z')",program,event,zone);
        mic=asset("무선 마이크 1","UNUSABLE"); spare=asset("예비 마이크","VERIFIED_USABLE"); cable=asset("XLR 케이블","VERIFIED_USABLE");
        requirement=uuid();
        jdbc.update("""
                INSERT INTO equipment_requirement (id,event_id,program_id,task_ids,requirements,ready_by,schedule_policy,time_evidence,revision)
                VALUES (?,?,?,'[]'::jsonb,?::jsonb,'2026-11-14T08:50:00Z','{"mode":"FIXED"}'::jsonb,'{}'::jsonb,1)""",
                requirement,event,program,"[{\"assetIds\":[\""+mic+"\"],\"compatibleAssetIds\":[\""+spare+"\"],\"quantity\":1,\"componentAssetIds\":[],\"requiredPortKeys\":[]}]");
        held=uuid();
        jdbc.update("INSERT INTO equipment_reservation (id,event_id,program_id,task_id,start_at,end_at,status,source_change_set_id,revision) VALUES (?,?,?,NULL,'2026-11-14T09:00:00Z','2026-11-14T10:00:00Z','PLANNED',NULL,1)",held,event,program);
        jdbc.update("INSERT INTO equipment_reservation_asset VALUES (?,?),(?,?)",held,mic,held,cable);
        issue=uuid();
        jdbc.update("""
                INSERT INTO issue (id,event_id,work_group_id,task_id,zone_id,title,reporter_member_id,status,related_issue_id,created_at,closed_at)
                VALUES (?,?,?,NULL,?,'무선 마이크 고장',?,'RECEIVED',NULL,now(),NULL)""",issue,event,group,zone,workerMember);
        var other=event("LIVE"); member(other,outsider,"CHIEF",null,"외부");
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
    }
    @AfterEach void cleanup() {
        for (var e:events) {
            jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
            jdbc.update("DELETE FROM change_acknowledgment WHERE event_id=?",e);
            jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
            jdbc.update("ALTER TABLE change_set_evidence_revision DISABLE TRIGGER USER");
            jdbc.update("DELETE FROM change_set_evidence_revision WHERE change_set_id IN (SELECT id FROM change_set WHERE event_id=?)",e);
            jdbc.update("ALTER TABLE change_set_evidence_revision ENABLE TRIGGER USER");
            jdbc.update("DELETE FROM equipment_reservation_asset WHERE reservation_id IN (SELECT id FROM equipment_reservation WHERE event_id=?)",e);
            jdbc.update("DELETE FROM equipment_reservation WHERE event_id=?",e);
            jdbc.update("DELETE FROM change_set WHERE event_id=?",e);
            jdbc.update("DELETE FROM recovery_simulation WHERE event_id=?",e);
            jdbc.update("DELETE FROM ai_job WHERE event_id=?",e);
            jdbc.update("DELETE FROM equipment_requirement WHERE event_id=?",e);
            jdbc.update("DELETE FROM equipment_asset WHERE event_id=?",e);
            jdbc.update("DELETE FROM issue WHERE event_id=?",e);
            jdbc.update("DELETE FROM program WHERE event_id=?",e);
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

    String body(String targets) {
        return "{\"expectedEventRevision\":\""+rev()+"\",\"issueId\":\""+issue+"\",\"targetAssetIds\":["+targets+"],\"evidenceRefs\":[],\"assumptions\":[]}";
    }
    HttpRequest.Builder simulate(String body,String cookie,String key) {
        var r=post("/api/v1/events/"+event+"/recovery-simulations",body).header("Cookie",cookie);
        return key==null?r:r.header("Idempotency-Key",key);
    }

    @Test void calculateReadAndApplyCandidate() throws Exception {
        String revBefore=rev(), key=UUID.randomUUID().toString();
        var accepted=send(simulate(body("\""+mic+"\""),chiefCookie,key));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        var acceptedBody=json.readTree(accepted.body());
        assertThat(acceptedBody.propertyNames()).containsExactly("jobId","statusUrl");
        String jobId=acceptedBody.get("jobId").asString();
        assertThat(acceptedBody.get("statusUrl").asString()).isEqualTo("/api/v1/ai-jobs/"+jobId);
        assertThat(json.readTree(send(simulate(body("\""+mic+"\""),chiefCookie,key)).body())).isEqualTo(acceptedBody);
        assertThat(rev()).isEqualTo(revBefore); // 계산은 운영 상태를 바꾸지 않는다
        assertThat(count("SELECT count(*) FROM recovery_simulation WHERE event_id=?",event)).isEqualTo(1);

        var job=json.readTree(ok(send(get("/api/v1/ai-jobs/"+jobId).header("Cookie",chiefCookie))).body());
        assertThat(job.get("kind").asString()).isEqualTo("SIMULATE_RECOVERY");
        assertThat(job.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(job.get("result").propertyNames()).containsExactly("simulationId","calculatedAt","expiresAt");
        assertThat(job.get("error").isNull()).isTrue();
        error(send(get("/api/v1/ai-jobs/"+jobId).header("Cookie",workerCookie)),404,"NOT_FOUND");
        String simId=job.get("result").get("simulationId").asString();
        Instant calculated=Instant.parse(job.get("result").get("calculatedAt").asString());
        assertThat(Instant.parse(job.get("result").get("expiresAt").asString())).isEqualTo(calculated.plus(Duration.ofDays(7)));

        var sim=json.readTree(ok(send(get("/api/v1/recovery-simulations/"+simId).header("Cookie",chiefCookie))).body());
        assertThat(sim.propertyNames()).containsExactly("id","eventId","issueId","targetAssetIds","createdByMemberId","baseRevision",
                "inputHash","evidenceRefs","policyVersion","assumptions","calculatedAt","state","candidates","expiresAt");
        assertThat(sim.get("state").asString()).isEqualTo("READY");
        assertThat(sim.get("policyVersion").asString()).isEqualTo("recovery-rule-v1");
        assertThat(sim.get("baseRevision").asString()).isEqualTo(revBefore);
        assertThat(sim.get("candidates").size()).isEqualTo(1);
        var candidate=sim.get("candidates").get(0);
        assertThat(candidate.propertyNames()).containsExactly("candidateId","status","reasons","evidenceRefs","equipmentIds","componentIds",
                "steps","occupancy","readyAt","requiredReadyAt","slackSec","shortfallSec","programImpacts","conflicts","operations","preview");
        assertThat(candidate.get("status").asString()).isEqualTo("CONDITIONAL");
        assertThat(candidate.get("readyAt").isNull()).isTrue();
        assertThat(candidate.get("operations").get(0).get("type").asString()).isEqualTo("UPDATE_EQUIPMENT_RESERVATION");
        assertThat(candidate.get("operations").get(0).get("payload").propertyNames()).containsExactly("reservationId","expectedRevision","after","evidenceRefs");
        assertThat(candidate.get("preview").get(0).propertyNames()).containsExactly("operationIndex","type","targetId","summary","before","after","evidenceRefs");
        error(send(get("/api/v1/recovery-simulations/"+simId).header("Cookie",workerCookie)),403,"FORBIDDEN");
        error(send(get("/api/v1/recovery-simulations/"+simId).header("Cookie",outsiderCookie)),404,"NOT_FOUND");
        var pointer=json.readTree(ok(send(get("/api/v1/issues/"+issue+"/recovery-simulations/latest").header("Cookie",chiefCookie))).body());
        assertThat(pointer.propertyNames()).containsExactly("simulationId","calculatedAt","expiresAt","expired");
        assertThat(pointer.get("simulationId").asString()).isEqualTo(simId);
        assertThat(pointer.get("expired").asBoolean()).isFalse();

        // 후보의 연산을 그대로 보내고 근거를 simulationRefs로 묶는다.
        String ops=json.writeValueAsString(candidate.get("operations"));
        String ref="[{\"simulationId\":\""+simId+"\",\"candidateId\":\""+candidate.get("candidateId").asString()+"\",\"inputHash\":\""+sim.get("inputHash").asString()+"\"}]";
        // 다른 해시·다른 후보·바꾼 연산·후보 2개는 422
        error(createChange(ops,ref.replace(sim.get("inputHash").asString(),"b".repeat(64))),422,"INVALID_REFERENCE");
        error(createChange(ops,ref.replace(candidate.get("candidateId").asString(),"other")),422,"INVALID_REFERENCE");
        var changed=createChange(ops.replace("\"expectedRevision\":\"1\"","\"expectedRevision\":\"2\""),ref);
        error(changed,422,"INVALID_REFERENCE");
        assertThat(json.readTree(changed.body()).get("details").get("reason").asString()).isEqualTo("CANDIDATE_MISMATCH");
        error(createChange(ops,"["+ref.substring(1,ref.length()-1)+","+ref.substring(1,ref.length()-1)+"]"),422,"INVALID_REFERENCE");
        var created=createChange(ops,ref);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var cs=json.readTree(created.body());
        assertThat(cs.get("simulationRefs")).isEqualTo(json.readTree(ref));
        String csId=cs.get("id").asString();
        var second=json.readTree(createChange(ops,ref).body()).get("id").asString();

        // 만료되면 결과는 410, 위치는 expired=true, 미리보기도 410
        jdbc.update("UPDATE recovery_simulation SET calculated_at = calculated_at - interval '8 days' WHERE id=?::uuid",simId);
        error(send(get("/api/v1/recovery-simulations/"+simId).header("Cookie",chiefCookie)),410,"RECOVERY_RESULT_EXPIRED");
        assertThat(json.readTree(ok(send(get("/api/v1/issues/"+issue+"/recovery-simulations/latest").header("Cookie",chiefCookie))).body())
                .get("expired").asBoolean()).isTrue();
        error(send(post("/api/v1/change-sets/"+second+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie)),410,"RECOVERY_RESULT_EXPIRED");
        var expiredCreate=createChange(ops,ref);
        error(expiredCreate,422,"INVALID_REFERENCE");
        assertThat(json.readTree(expiredCreate.body()).get("details").get("reason").asString()).isEqualTo("RECOVERY_RESULT_EXPIRED");
        jdbc.update("UPDATE recovery_simulation SET calculated_at = calculated_at + interval '8 days' WHERE id=?::uuid",simId);

        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+csId+"/preview","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"1\"}").header("Cookie",chiefCookie))).body());
        assertThat(preview.get("diff").get(0).get("after")).isEqualTo(candidate.get("preview").get(0).get("after"));
        ok(send(post("/api/v1/change-sets/"+csId+"/apply","{\"expectedEventRevision\":\""+rev()+"\",\"expectedChangeSetRevision\":\"2\",\"previewHash\":\""+preview.get("previewHash").asString()+"\"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())));
        assertThat(jdbc.queryForList("SELECT asset_id FROM equipment_reservation_asset WHERE reservation_id=? ORDER BY asset_id",UUID.class,held))
                .containsExactlyInAnyOrder(spare,cable);
        assertThat(jdbc.queryForObject("SELECT revision FROM equipment_reservation WHERE id=?",Long.class,held)).isEqualTo(2);
    }

    @Test void requestRules() throws Exception {
        error(send(simulate(body("\""+mic+"\""),workerCookie,UUID.randomUUID().toString())),403,"FORBIDDEN");
        error(send(simulate(body("\""+mic+"\""),outsiderCookie,UUID.randomUUID().toString())),404,"NOT_FOUND");
        error(send(simulate(body("\""+mic+"\""),chiefCookie,null)),400,"INVALID_REQUEST");
        for (String bad:List.of(body(""), body("\""+mic+"\",\""+mic+"\""), body("\""+mic+"\"").replace("\"assumptions\":[]","\"assumptions\":[{\"key\":\"k\",\"description\":\"d\",\"confirmed\":true}]"),
                body("\""+mic+"\"").replace(",\"evidenceRefs\":[]",""), body("\""+mic+"\"").replace("}",",\"x\":1}")))
            error(send(simulate(bad,chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(simulate(body("\""+mic+"\"").replace("\"expectedEventRevision\":\""+rev()+"\"","\"expectedEventRevision\":\"99\""),chiefCookie,UUID.randomUUID().toString())),409,"VERSION_CONFLICT");
        error(send(simulate(body("\""+UUID.randomUUID()+"\""),chiefCookie,UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
        error(send(simulate(body("\""+mic+"\"").replace(issue.toString(),UUID.randomUUID().toString()),chiefCookie,UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        error(send(simulate(body("\""+mic+"\""),chiefCookie,UUID.randomUUID().toString())),409,"INVALID_STATE");
        // 필요 조건이 없는 장비는 후보 없이 NEEDS_INFO다(종료 전으로 되돌린 뒤).
        jdbc.update("UPDATE event SET state='LIVE' WHERE id=?",event);
        var job=json.readTree(send(simulate(body("\""+cable+"\""),chiefCookie,UUID.randomUUID().toString())).body()).get("jobId").asString();
        String simId=json.readTree(ok(send(get("/api/v1/ai-jobs/"+job).header("Cookie",chiefCookie))).body()).get("result").get("simulationId").asString();
        var sim=json.readTree(ok(send(get("/api/v1/recovery-simulations/"+simId).header("Cookie",chiefCookie))).body());
        assertThat(sim.get("state").asString()).isEqualTo("NEEDS_INFO");
        assertThat(sim.get("candidates").isEmpty()).isTrue();
        assertThat(sim.get("assumptions").get(0).get("confirmed").asBoolean()).isFalse();
        // 결과가 없는 보고는 404
        jdbc.update("DELETE FROM recovery_simulation WHERE event_id=?",event);
        error(send(get("/api/v1/issues/"+issue+"/recovery-simulations/latest").header("Cookie",chiefCookie)),404,"NOT_FOUND");
    }

    HttpResponse<String> createChange(String ops,String refs) throws Exception {
        return send(post("/api/v1/events/"+event+"/change-sets","{\"mode\":\"MANUAL\",\"operations\":"+ops+",\"issueId\":\""+issue+"\",\"rationale\":\"복구\",\"expectedEventRevision\":\""+rev()+"\",\"simulationRefs\":"+refs+"}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
    }

    UUID uuid() { return UUID.randomUUID(); }
    UUID asset(String name,String state) {
        var id=uuid();
        jdbc.update("""
                INSERT INTO equipment_asset (id,event_id,name,model,technical_state,state_evidence,component_asset_ids,ports,verified_compatibility,
                                             storage_zone_id,holder_member_id,revision)
                VALUES (?,?,?,NULL,?,'[]'::jsonb,'[]'::jsonb,'[]'::jsonb,'[]'::jsonb,NULL,NULL,1)""",id,event,name,state);
        return id;
    }
    String rev() { return String.valueOf(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)); }
    long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    HttpResponse<String> ok(HttpResponse<String> r) { assertThat(r.statusCode()).as(r.body()).isEqualTo(200); return r; }
    UUID account() {
        var id=uuid(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",uuid(),id,id.toString(),
                new BCryptPasswordEncoder(4).encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    UUID event(String state) {
        var id=uuid(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",
                id,"공연",state,3L,LocalDate.of(2026,11,14));
        return id;
    }
    UUID member(UUID event,UUID account,String role,UUID group,String name) {
        var id=uuid();
        Long crew=jdbc.queryForObject("SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?",Long.class,event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,?,?,true,false,true,1)""",id,event,account,group,crew,role,name);
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
