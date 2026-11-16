package com.ssafy.scenecrew.aijob;

import java.net.URI;
import java.net.http.*;
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
import static org.assertj.core.api.Assertions.*;

/** SC-API-063 GET /ai-jobs/{j}의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis. 작업·음성·보고는 SQL로 만든다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AiJobReadHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AiJobReadHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"job-test"); r.add("scenecrew.auth.audience",()->"job-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>();
    UUID event, zone, group, chiefMember, uploaderMember, otherMember, chief, uploader, other, outsider;
    String chiefCookie, uploaderCookie, otherCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); uploader=account(); other=account(); outsider=account();
        event=UUID.randomUUID();
        jdbc.update("INSERT INTO event (id,name,state,revision,last_input_modified_at) VALUES (?,'공연','LIVE',5,now())",event);
        zone=UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,'무대',NULL,'[]'::jsonb)",zone,event);
        group=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'안내조','',true)",group,event);
        chiefMember=member(chief,"CHIEF",null,1); uploaderMember=member(uploader,"WORKER",group,2); otherMember=member(other,"WORKER",group,3);
        chiefCookie=login(chief); uploaderCookie=login(uploader); otherCookie=login(other); outsiderCookie=login(outsider);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM ai_job WHERE event_id=?",event);
        jdbc.update("DELETE FROM report WHERE issue_id IN (SELECT id FROM issue WHERE event_id=?)",event);
        jdbc.update("DELETE FROM issue WHERE event_id=?",event);
        jdbc.update("DELETE FROM audio_asset WHERE event_id=?",event);
        jdbc.update("DELETE FROM member WHERE event_id=?",event);
        jdbc.update("DELETE FROM work_group WHERE event_id=?",event);
        jdbc.update("DELETE FROM zone WHERE event_id=?",event);
        jdbc.update("DELETE FROM event WHERE id=?",event);
        for (var a:accounts) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        accounts.clear();
    }

    @Test void transcriptionIsVisibleOnlyToUploaderWithFullResult() throws Exception {
        UUID audio=audio("now()",true,"무대 조명 꺼짐");
        UUID job=job("TRANSCRIBE",uploaderMember,"QUEUED",0,null,null,"{}");
        var queued=read(job,uploaderCookie);
        assertThat(queued.propertyNames()).containsExactly("jobId","kind","state","attempt","result","error");
        assertThat(queued.get("state").asString()).isEqualTo("QUEUED");
        assertThat(queued.get("attempt").asString()).isEqualTo("0");
        assertThat(queued.get("result").isNull()).isTrue();
        assertThat(queued.get("error").isNull()).isTrue();
        // 총괄·다른 팀원·다른 행사 계정은 존재도 모른다.
        for (String cookie:List.of(chiefCookie,otherCookie,outsiderCookie))
            error(send(get("/api/v1/ai-jobs/"+job).header("Cookie",cookie)),404,"NOT_FOUND");

        jdbc.update("UPDATE ai_job SET state='SUCCEEDED', attempt=1, result=?::jsonb, execution_meta=?::jsonb WHERE id=?",
                "{\"audioId\":\""+audio+"\"}","{\"pipeline\":\"RAW\",\"fallbackReason\":\"DENOISE_FAILED\"}",job);
        var done=read(job,uploaderCookie);
        assertThat(done.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("error").isNull()).isTrue();
        var result=done.get("result");
        assertThat(result.propertyNames()).containsExactly("audioId","text","durationSec","segments","pipeline","fallbackReason");
        assertThat(result.get("audioId").asString()).isEqualTo(audio.toString());
        assertThat(result.get("text").asString()).isEqualTo("무대 조명 꺼짐");
        assertThat(result.get("durationSec").isNumber()).isTrue();
        assertThat(result.get("durationSec").asDouble()).isEqualTo(4.5);
        assertThat(result.get("segments").get(0).get("text").asString()).isEqualTo("무대 조명 꺼짐");
        assertThat(result.get("pipeline").asString()).isEqualTo("RAW");
        assertThat(result.get("fallbackReason").asString()).isEqualTo("DENOISE_FAILED");
        error(send(get("/api/v1/ai-jobs/"+job).header("Cookie",chiefCookie)),404,"NOT_FOUND");

        // Android Bearer도 같은 결과를 받는다.
        var android=send(post("/api/v1/auth/login","{\"email\":\""+uploader+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .header("X-SceneCrew-Client","ANDROID").header("Origin",ANDROID));
        String token=json.readTree(android.body()).get("accessToken").asString();
        var bearer=send(get("/api/v1/ai-jobs/"+job).header("Authorization","Bearer "+token).header("Origin",ANDROID));
        assertThat(bearer.statusCode()).as(bearer.body()).isEqualTo(200);
        assertThat(json.readTree(bearer.body())).isEqualTo(done);
    }

    @Test void transcriptionWhoseTextIsGoneIs404() throws Exception {
        UUID deleted=audio("now()",false,"지운 전사");                       // 본인 삭제(061): 원본 키 없음
        UUID expired=audio("now() - interval '25 hours'",true,"오래된 전사");  // 24시간 경과
        UUID none=audio("now()",true,null);                                  // 전사문 없음(제출 뒤 정리 등)
        for (UUID audio:List.of(deleted,expired,none)) {
            UUID job=job("TRANSCRIBE",uploaderMember,"SUCCEEDED",1,"{\"audioId\":\""+audio+"\"}",null,"{\"pipeline\":\"DENOISED\"}");
            error(send(get("/api/v1/ai-jobs/"+job).header("Cookie",uploaderCookie)),404,"NOT_FOUND");
        }
    }

    @Test void failuresShowOnlyPublicCodeAndRetryable() throws Exception {
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("{\"code\":\"NO_SPEECH\",\"message\":\"m\",\"retryable\":false}","NO_SPEECH:false");
        cases.put("{\"code\":\"AUDIO_INVALID\",\"message\":\"m\",\"retryable\":false,\"details\":{\"reason\":\"TOO_LONG\"}}","AUDIO_INVALID:false");
        cases.put("{\"code\":\"CONSENT_WITHDRAWN\",\"message\":\"m\",\"retryable\":false}","AUDIO_INVALID:false");
        cases.put("{\"code\":\"AUDIO_DELETED\",\"message\":\"m\",\"retryable\":false}","AUDIO_INVALID:false");
        cases.put("{\"code\":\"INVALID_REQUEST\",\"message\":\"m\",\"retryable\":false}","AI_UNAVAILABLE:false");
        cases.put("{\"code\":\"AI_UNAVAILABLE\",\"message\":\"m\",\"retryable\":true,\"details\":{\"reason\":\"CONNECT\"}}","AI_UNAVAILABLE:true");
        cases.put("{\"code\":\"AI_UNAVAILABLE\",\"message\":\"m\",\"retryable\":false,\"details\":{\"reason\":\"INVALID_RESULT\"}}","AI_UNAVAILABLE:false");
        cases.put("{\"code\":\"AI_TIMEOUT\",\"message\":\"m\",\"retryable\":false}","AI_TIMEOUT:false");
        cases.put("{\"code\":\"SOMETHING_NEW\",\"message\":\"m\",\"retryable\":true}","AI_UNAVAILABLE:false");
        for (var c:cases.entrySet()) {
            UUID job=job("TRANSCRIBE",uploaderMember,"FAILED",3,null,c.getKey(),"{}");
            var body=read(job,uploaderCookie);
            assertThat(body.get("state").asString()).isEqualTo("FAILED");
            assertThat(body.get("result").isNull()).isTrue();
            assertThat(body.get("error").propertyNames()).containsExactly("code","retryable");
            assertThat(body.get("error").get("code").asString()+":"+body.get("error").get("retryable").asBoolean())
                    .as(c.getKey()).isEqualTo(c.getValue());
            assertThat(body.toString()).doesNotContain("TOO_LONG","CONNECT","INVALID_RESULT","\"m\"");
        }
        // 저장 STALE_CONTEXT는 공개 FAILED·STALE_CONTEXT
        UUID stale=job("TRANSCRIBE",uploaderMember,"STALE_CONTEXT",1,null,
                "{\"code\":\"READ_CONTEXT_CHANGED\",\"message\":\"m\",\"retryable\":false,\"details\":{\"expectedRevision\":4}}","{}");
        var s=read(stale,uploaderCookie);
        assertThat(s.get("state").asString()).isEqualTo("FAILED");
        assertThat(s.get("error").get("code").asString()).isEqualTo("STALE_CONTEXT");
        assertThat(s.get("error").get("retryable").asBoolean()).isFalse();
        // 재시도 대기(QUEUED)는 마지막 오류가 있어도 error=null
        UUID retrying=job("TRANSCRIBE",uploaderMember,"QUEUED",2,null,"{\"code\":\"AI_UNAVAILABLE\",\"message\":\"m\",\"retryable\":true}","{}");
        var q=read(retrying,uploaderCookie);
        assertThat(q.get("error").isNull()).isTrue();
        assertThat(q.get("attempt").asString()).isEqualTo("2");
    }

    @Test void reportAnalysisIsChiefOnlyAndShowsValidity() throws Exception {
        UUID issue=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO issue (id,event_id,work_group_id,task_id,zone_id,title,reporter_member_id,status,related_issue_id,created_at,closed_at)
                VALUES (?,?,?,NULL,?,'조명',?,'RECEIVED',NULL,now(),NULL)""",issue,event,group,zone,uploaderMember);
        UUID report=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO report (id,issue_id,author_member_id,kind,text,normalized_text,audio_id,quick_template_key,analysis,created_at,participation_revision)
                VALUES (?,?,?,'INITIAL','조명 꺼짐','조명 꺼짐',NULL,NULL,NULL,now(),1)""",report,issue,uploaderMember);
        UUID job=job("ANALYZE_REPORT",uploaderMember,"SUCCEEDED",1,"{\"reportId\":\""+report+"\"}",null,"{}");
        jdbc.update("UPDATE report SET analysis=?::jsonb WHERE id=?","{\"jobId\":\""+job+"\",\"contextRevision\":5,\"category\":\"EQUIPMENT\","
                +"\"reportedState\":\"OCCURRED\",\"zoneCandidates\":[{\"zoneId\":\""+zone+"\",\"source\":\"ZONE\",\"sourceRef\":\""+zone+"\",\"contextRevision\":5}]}",report);
        var body=read(job,chiefCookie);
        var result=body.get("result");
        assertThat(result.propertyNames()).containsExactly("analysisValidity","category","reportedState","zoneCandidates");
        assertThat(result.get("analysisValidity").asString()).isEqualTo("CURRENT");
        assertThat(result.get("category").asString()).isEqualTo("EQUIPMENT");
        assertThat(result.get("zoneCandidates").get(0).get("contextRevision").asString()).isEqualTo("5");
        // 분석은 총괄만 본다. 056으로 jobId를 받은 작성자(팀원)도 404다.
        error(send(get("/api/v1/ai-jobs/"+job).header("Cookie",uploaderCookie)),404,"NOT_FOUND");
        // 계획이 바뀌면 STALE
        jdbc.update("UPDATE event SET revision=6 WHERE id=?",event);
        assertThat(read(job,chiefCookie).get("result").get("analysisValidity").asString()).isEqualTo("STALE");
        // 같은 보고를 다시 분석해 더 새 분석으로 바뀌면 이 작업의 결과는 남아 있지 않다.
        UUID newer=job("ANALYZE_REPORT",chiefMember,"SUCCEEDED",1,"{\"reportId\":\""+report+"\"}",null,"{}");
        jdbc.update("UPDATE report SET analysis=jsonb_set(analysis,'{jobId}',to_jsonb(?::text)) WHERE id=?",newer.toString(),report);
        error(send(get("/api/v1/ai-jobs/"+job).header("Cookie",chiefCookie)),404,"NOT_FOUND");
        assertThat(read(newer,chiefCookie).get("state").asString()).isEqualTo("SUCCEEDED");
    }

    @Test void rejectsBadInputUnknownKindsAndUnauthenticated() throws Exception {
        // 아직 공개 모양이 없는 종류는 총괄에게도 404. GENERATE_CHANGE는 #343에서 공개했으므로 배정(ASSIGN_INITIAL)으로 본다.
        UUID plan=job("ASSIGN_INITIAL",chiefMember,"QUEUED",0,null,null,"{}");
        error(send(get("/api/v1/ai-jobs/"+plan).header("Cookie",chiefCookie)),404,"NOT_FOUND");
        error(send(get("/api/v1/ai-jobs/"+UUID.randomUUID()).header("Cookie",chiefCookie)),404,"NOT_FOUND");
        for (String path:List.of("not-a-uuid",plan+"?x=1"))
            error(send(get("/api/v1/ai-jobs/"+path).header("Cookie",chiefCookie)),400,"INVALID_REQUEST");
        error(send(get("/api/v1/ai-jobs/"+plan)),401,"UNAUTHENTICATED");
        // 탈퇴한 업로더는 자기 작업도 볼 수 없다.
        UUID job=job("TRANSCRIBE",uploaderMember,"QUEUED",0,null,null,"{}");
        jdbc.update("UPDATE member SET active=false WHERE id=?",uploaderMember);
        error(send(get("/api/v1/ai-jobs/"+job).header("Cookie",uploaderCookie)),404,"NOT_FOUND");
    }

    // ---- 도우미 -------------------------------------------------------------------------------------------

    UUID job(String kind,UUID requester,String state,long attempt,String result,String error,String meta) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_job (id,event_id,requested_by_member_id,kind,state,input_hash,context_revision,request_schema_version,
                                    input,result,error,attempt,execution_meta,auto_reanalysis_count,created_at,deadline_at)
                VALUES (?,?,?,?,?,'h',5,'4','{}'::jsonb,?::jsonb,?::jsonb,?,?::jsonb,0,now(),now()+interval '90 seconds')""",
                id,event,requester,kind,state,result,error,attempt,meta);
        return id;
    }
    UUID audio(String createdAt,boolean original,String transcript) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO audio_asset (id,event_id,uploader_member_id,storage_key,mime,bytes,duration_sec,transcript,transcript_segments,created_at)"
                +" VALUES (?,?,?,?,'audio/mp4',1000,4.5,?,?::jsonb,"+createdAt+")",
                id,event,uploaderMember,original?"audio/"+id:null,transcript,
                transcript==null?null:"[{\"startSec\":0,\"endSec\":4.5,\"text\":\""+transcript+"\"}]");
        return id;
    }
    UUID member(UUID account,String role,UUID group,long crew) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,?,'fixture',true,false,true,1)""",id,event,account,group,crew,role);
        return id;
    }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    String login(UUID account) throws Exception {
        var r=send(post("/api/v1/auth/login","{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .header("X-SceneCrew-Client","WEB").header("Origin",WEB).header("X-SceneCrew-CSRF","1"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    JsonNode read(UUID job,String cookie) throws Exception {
        var r=send(get("/api/v1/ai-jobs/"+job).header("Cookie",cookie));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        return json.readTree(r.body());
    }
    HttpRequest.Builder get(String path) { return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET(); }
    HttpRequest.Builder post(String path,String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json")
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
