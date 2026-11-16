package com.ssafy.scenecrew.plan;

import java.net.URI;
import java.net.http.*;
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
import static org.assertj.core.api.Assertions.*;

/** Q3-A 미확정 장비·장소 초안 조회·저장(#355)의 실제 HTTP·Security·PostgreSQL·Redis. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(UnconfirmedDraftHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class UnconfirmedDraftHttpTest {
    static final String WEB="https://web.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"draft-test"); r.add("scenecrew.auth.audience",()->"draft-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    UUID event, mic, otherAsset;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        UUID chief=account(), worker=account(), outsider=account();
        event=event("DRAFT");
        UUID group=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'음향','',true)",group,event);
        member(event,chief,"CHIEF",null); member(event,worker,"WORKER",group);
        mic=asset(event,"무선 마이크 1");
        UUID other=event("DRAFT"); member(other,outsider,"CHIEF",null); otherAsset=asset(other,"다른 행사 장비");
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
    }
    @AfterEach void cleanup() {
        for (var e:events) {
            jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
            jdbc.update("DELETE FROM event_unconfirmed_draft WHERE event_id=?",e);
            jdbc.update("DELETE FROM equipment_asset WHERE event_id=?",e);
            jdbc.update("DELETE FROM member WHERE event_id=?",e);
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

    static final String EVIDENCE="{\"sourceKind\":\"MANUAL_CONFIRMATION\",\"sourceRef\":\"현장 확인\",\"observedAt\":null}";
    static final String PROFILE="{\"zoneId\":\"tmp-zone-a\",\"expectedProfileRevision\":null,\"effectiveAreaM2\":120.5,"
            +"\"areaEvidence\":"+EVIDENCE+",\"riskRule\":null,\"minimumCrewRule\":null}";

    /** 계약(FinalUnconfirmedDraftWrite)을 지키는 초안: 장비 2개(실제 1·임시 1)와 연결·요구·예약·의존, 평면도 */
    String draft(String revision) {
        return """
                {"expectedRevision":"%1$s","sourceJobId":null,
                 "equipmentDraft":{"assets":[{"id":"%2$s","name":"무선 마이크 1","model":null,"componentAssetIds":[],"ports":[]},
                                             {"tempId":"tmp-spare","name":"예비 마이크","model":"무선","componentAssetIds":[],
                                              "ports":[{"key":"out","kind":"XLR"}]}],
                                   "connections":[{"fromAssetId":"tmp-spare","fromPort":"out","toAssetId":"%2$s","toPort":"in","evidence":%3$s}],
                                   "requirements":[{"programId":null,"taskIds":["tmp-task-1"],
                                                    "requirements":[{"assetIds":["tmp-spare"],"compatibleAssetIds":["%2$s"],"quantity":1,
                                                                     "componentAssetIds":[],"requiredPortKeys":["out"]}],
                                                    "readyBy":"2026-11-14T08:30:00Z",
                                                    "schedulePolicy":{"mode":"FIXED","allowedStartAt":null,"allowedEndAt":null,"evidence":%3$s},
                                                    "timeEvidence":%3$s}],
                                   "reservations":[{"assetIds":["tmp-spare"],"programId":null,"taskId":null,
                                                    "startAt":"2026-11-14T09:00:00Z","endAt":"2026-11-14T10:00:00+09:00"}],
                                   "dependencies":[{"from":{"kind":"TASK","id":"tmp-task-1"},"to":{"kind":"PROGRAM","id":"tmp-program-1"},
                                                    "lagSec":0,"evidence":%3$s}]},
                 "venueDraft":{"layout":{"coordinateSystem":"normalized-image","width":1920,"height":1080,
                                         "zonePolygons":[{"zoneId":"tmp-zone-a","points":[{"x":0.1,"y":0.2},{"x":0.4,"y":0.2},{"x":0.4,"y":0.6}]}],
                                         "source":%3$s},
                               "profiles":[]}}"""
                .formatted(revision,mic,EVIDENCE);
    }

    @Test void chiefSavesReadsAndReplacesWithRevisionWithoutTouchingEventRevision() throws Exception {
        error(send(get(chiefCookie)),404,"NOT_FOUND"); // 아직 저장한 적이 없다
        long eventRevision=rev();
        String key=UUID.randomUUID().toString();
        var saved=send(put(draft("0"),chiefCookie,key));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(saved.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode receipt=json.readTree(saved.body());
        assertThat(receipt.propertyNames()).containsExactly("id","revision","eventRevision");
        assertThat(receipt.get("revision").asString()).isEqualTo("1");
        assertThat(receipt.get("eventRevision").asString()).isEqualTo(Long.toString(eventRevision));
        assertThat(rev()).isEqualTo(eventRevision); // 초안 저장은 Event.revision을 올리지 않는다
        // 같은 키 재요청은 첫 결과를 그대로 돌려주고 다시 저장하지 않는다
        assertThat(json.readTree(send(put(draft("0"),chiefCookie,key)).body())).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT revision FROM event_unconfirmed_draft WHERE event_id=?",Long.class,event)).isEqualTo(1L);

        var read=send(get(chiefCookie));
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        JsonNode view=json.readTree(read.body());
        assertThat(view.propertyNames()).containsExactly("id","eventId","revision","sourceJobId","equipmentDraft","venueDraft",
                "savedByMemberId","savedAt");
        assertThat(view.get("id")).isEqualTo(receipt.get("id"));
        assertThat(view.get("revision").asString()).isEqualTo("1");
        assertThat(view.get("equipmentDraft").get("assets").get(1).get("tempId").asString()).isEqualTo("tmp-spare");
        assertThat(view.get("venueDraft").get("layout").get("zonePolygons").get(0).get("zoneId").asString()).isEqualTo("tmp-zone-a");
        // 저장한 그대로 돌려준다(FE가 같은 계약 스키마로 다시 검사한다)
        assertThat(view.get("equipmentDraft")).isEqualTo(json.readTree(draft("0")).get("equipmentDraft"));
        assertThat(view.get("venueDraft")).isEqualTo(json.readTree(draft("0")).get("venueDraft"));

        // 전체 교체: 낡은 revision은 409, 현재 revision이면 바뀐다
        error(send(put(draft("0"),chiefCookie,UUID.randomUUID().toString())),409,"VERSION_CONFLICT");
        String replaced=draft("1").replace("\"profiles\":[]}}","\"profiles\":["+PROFILE+"]}}");
        var second=send(put(replaced,chiefCookie,UUID.randomUUID().toString()));
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(json.readTree(second.body()).get("revision").asString()).isEqualTo("2");
        assertThat(json.readTree(send(get(chiefCookie)).body()).get("venueDraft").get("profiles").size()).isEqualTo(1);
    }

    @Test void onlyCurrentChiefAndDraftStateAndKnownReferences() throws Exception {
        error(send(get(workerCookie)),403,"FORBIDDEN");
        error(send(get(outsiderCookie)),404,"NOT_FOUND");
        error(send(put(draft("0"),workerCookie,UUID.randomUUID().toString())),403,"FORBIDDEN");
        error(send(put(draft("0"),outsiderCookie,UUID.randomUUID().toString())),404,"NOT_FOUND");
        // 키 없음·모양 위반은 400
        error(send(put(draft("0"),chiefCookie,null)),400,"INVALID_REQUEST");
        error(send(put(draft("0").replace("\"sourceJobId\":null,",""),chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(put(draft("0").replace("\"dependencies\":[","\"x\":[],\"dependencies\":["),chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(put(draft("01"),chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        // 초안에 없는 임시 키·다른 행사 장비·이 행사에 없는 Job은 422
        error(send(put(draft("0").replace("\"assetIds\":[\"tmp-spare\"]","\"assetIds\":[\"tmp-missing\"]"),chiefCookie,UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
        error(send(put(draft("0").replace("\"toAssetId\":\""+mic+"\"","\"toAssetId\":\""+otherAsset+"\""),chiefCookie,UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
        error(send(put(draft("0").replace("\"sourceJobId\":null","\"sourceJobId\":\""+UUID.randomUUID()+"\""),chiefCookie,UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_unconfirmed_draft WHERE event_id=?",Long.class,event)).isZero();

        // 발행(RECRUITING) 뒤에는 읽기만 한다
        assertThat(send(put(draft("0"),chiefCookie,UUID.randomUUID().toString())).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        error(send(put(draft("1"),chiefCookie,UUID.randomUUID().toString())),409,"INVALID_STATE");
        assertThat(send(get(chiefCookie)).statusCode()).isEqualTo(200);
    }

    /** 하위 스키마 위반은 저장하지 않고 400. 저장되면 FE가 조회 응답을 계약 위반으로 거절해 초안을 열 수 없다. */
    @Test void rejectsEveryNestedContractViolation() throws Exception {
        String ok=draft("0");
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("asset id와 tempId 둘 다", ok.replace("{\"tempId\":\"tmp-spare\",","{\"id\":\""+UUID.randomUUID()+"\",\"tempId\":\"tmp-spare\","));
        cases.put("asset id·tempId 없음", ok.replace("{\"tempId\":\"tmp-spare\",","{"));
        cases.put("asset 추가 키", ok.replace("\"name\":\"예비 마이크\"","\"name\":\"예비 마이크\",\"note\":\"x\""));
        cases.put("asset name 빈 문자열", ok.replace("\"name\":\"예비 마이크\"","\"name\":\"\""));
        cases.put("asset model 빠짐", ok.replace("\"model\":\"무선\",",""));
        cases.put("tempId 형식", ok.replace("\"tempId\":\"tmp-spare\"","\"tempId\":\"spare\""));
        cases.put("port kind 빠짐", ok.replace("{\"key\":\"out\",\"kind\":\"XLR\"}","{\"key\":\"out\"}"));
        cases.put("connection evidence 빠짐", ok.replace("\"toPort\":\"in\",\"evidence\":"+EVIDENCE,"\"toPort\":\"in\""));
        cases.put("evidence sourceKind enum", ok.replace("MANUAL_CONFIRMATION","GUESS"));
        cases.put("evidence observedAt 초 없음", ok.replace("\"observedAt\":null","\"observedAt\":\"2026-11-14T09:00Z\""));
        cases.put("참조 형식", ok.replace("\"toAssetId\":\""+mic+"\"","\"toAssetId\":\"mic-1\""));
        cases.put("requirement quantity 0", ok.replace("\"quantity\":1","\"quantity\":0"));
        cases.put("requirement quantity 실수", ok.replace("\"quantity\":1","\"quantity\":1.5"));
        cases.put("requirement requiredPortKeys 빈 문자열", ok.replace("\"requiredPortKeys\":[\"out\"]","\"requiredPortKeys\":[\"\"]"));
        cases.put("schedulePolicy mode enum", ok.replace("\"mode\":\"FIXED\"","\"mode\":\"ANY\""));
        cases.put("readyBy 시간대 없음", ok.replace("2026-11-14T08:30:00Z","2026-11-14T08:30:00"));
        cases.put("reservation assetIds 비어 있음", ok.replace("\"reservations\":[{\"assetIds\":[\"tmp-spare\"]","\"reservations\":[{\"assetIds\":[]"));
        cases.put("reservation programId 빠짐", ok.replace("\"programId\":null,\"taskId\":null,",""));
        cases.put("dependency kind enum", ok.replace("{\"kind\":\"TASK\"","{\"kind\":\"ZONE\""));
        cases.put("dependency lagSec 음수", ok.replace("\"lagSec\":0","\"lagSec\":-1"));
        cases.put("layout coordinateSystem const", ok.replace("normalized-image","pixel"));
        cases.put("layout width 0", ok.replace("\"width\":1920","\"width\":0"));
        cases.put("layout source 빠짐", ok.replaceAll(",\\s*\"source\":"+java.util.regex.Pattern.quote(EVIDENCE),""));
        cases.put("polygon 점 2개", ok.replace(",{\"x\":0.4,\"y\":0.6}",""));
        cases.put("point 범위", ok.replace("{\"x\":0.4,\"y\":0.6}","{\"x\":1.4,\"y\":0.6}"));
        cases.put("profile 추가 키", ok.replace("\"profiles\":[]","\"profiles\":["+PROFILE.replace("\"riskRule\":null","\"riskRule\":null,\"zoneName\":\"A\"")+"]"));
        cases.put("profile revision 숫자", ok.replace("\"profiles\":[]","\"profiles\":["+PROFILE.replace("\"expectedProfileRevision\":null","\"expectedProfileRevision\":1")+"]"));
        cases.put("profile effectiveAreaM2 0", ok.replace("\"profiles\":[]","\"profiles\":["+PROFILE.replace("120.5","0")+"]"));
        cases.put("JSON 키 중복", ok.replace("\"sourceJobId\":null,","\"sourceJobId\":null,\"sourceJobId\":null,"));
        for (var c:cases.entrySet()) {
            assertThat(c.getValue()).as(c.getKey()).isNotEqualTo(ok);
            var r=send(put(c.getValue(),chiefCookie,UUID.randomUUID().toString()));
            assertThat(r.statusCode()).as(c.getKey()+" "+r.body()).isEqualTo(400);
            assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo("INVALID_REQUEST");
        }
        // 계약상 허용: 실제 id 장비, 정상 프로필, 19자리 signed64 안의 revision 형식(현재 revision과 달라 409)
        String withProfile=ok.replace("\"profiles\":[]","\"profiles\":["+PROFILE.replace("\"expectedProfileRevision\":null","\"expectedProfileRevision\":\"3\"")+"]");
        assertThat(send(put(withProfile,chiefCookie,UUID.randomUUID().toString())).statusCode()).isEqualTo(200);
        error(send(put(draft("9223372036854775807"),chiefCookie,UUID.randomUUID().toString())),409,"VERSION_CONFLICT");
        error(send(put(draft("9223372036854775808"),chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT revision FROM event_unconfirmed_draft WHERE event_id=?",Long.class,event)).isEqualTo(1L);
    }

    /** 장비 요구 항목(V9DraftRequirementItem)의 장비 참조도 이 초안·이 행사 안이어야 한다. */
    @Test void requirementItemAssetReferencesMustBeKnown() throws Exception {
        String ok=draft("0");
        for (String field:List.of("assetIds","compatibleAssetIds","componentAssetIds")) {
            String needle=field.equals("assetIds")?"\"assetIds\":[\"tmp-spare\"],\"compatibleAssetIds\"":"\""+field+"\":[";
            String target=field.equals("assetIds")?"\"assetIds\":[\"%s\"],\"compatibleAssetIds\"":"\""+field+"\":[\"%s\"";
            if (field.equals("componentAssetIds")) { needle="\"componentAssetIds\":[],\"requiredPortKeys\""; target="\"componentAssetIds\":[\"%s\"],\"requiredPortKeys\""; }
            if (field.equals("compatibleAssetIds")) { needle="\"compatibleAssetIds\":[\""+mic+"\"]"; target="\"compatibleAssetIds\":[\"%s\"]"; }
            for (String bad:List.of("tmp-missing",otherAsset.toString())) {
                String body=ok.replace(needle,target.formatted(bad));
                assertThat(body).as(field).isNotEqualTo(ok);
                error(send(put(body,chiefCookie,UUID.randomUUID().toString())),422,"INVALID_REFERENCE");
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_unconfirmed_draft WHERE event_id=?",Long.class,event)).isZero();
    }

    /** 512KB 상한은 받은 원문 바이트 기준이다(계약에 없는 서버 규칙). */
    @Test void rejectsBodyOverRawByteLimit() throws Exception {
        int limit=512*1024;
        String ok=draft("0");
        // 공백으로만 부풀려 파싱 뒤 재직렬화 크기는 작아도 원문이 상한을 넘으면 거절한다
        String padded=ok+" ".repeat(limit-ok.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1);
        error(send(put(padded,chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        String exact=ok+" ".repeat(limit-ok.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        var r=send(put(exact,chiefCookie,UUID.randomUUID().toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                new BCryptPasswordEncoder(4).encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    UUID event(String state) {
        var id=UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",
                id,"공연",state,3L,LocalDate.of(2026,11,14));
        return id;
    }
    void member(UUID event,UUID account,String role,UUID group) {
        Long crew=jdbc.queryForObject("SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?",Long.class,event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,?,'fixture',true,false,true,1)""",UUID.randomUUID(),event,account,group,crew,role);
    }
    UUID asset(UUID event,String name) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipment_asset (id,event_id,name,model,technical_state,state_evidence,component_asset_ids,ports,verified_compatibility,
                                             storage_zone_id,holder_member_id,revision)
                VALUES (?,?,?,NULL,'UNKNOWN','{}'::jsonb,'[]'::jsonb,'[]'::jsonb,'[]'::jsonb,NULL,NULL,1)""",id,event,name);
        return id;
    }
    long rev() { return jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event); }
    String login(UUID account) throws Exception {
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type","application/json")
                .header("X-SceneCrew-Client","WEB").header("Origin",WEB).header("X-SceneCrew-CSRF","1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    String url(String path) { return "http://localhost:"+port+path; }
    HttpRequest.Builder get(String cookie) {
        return HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/unconfirmed-draft"))).header("Cookie",cookie).GET();
    }
    HttpRequest.Builder put(String body,String cookie,String key) {
        var r=HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/unconfirmed-draft"))).header("Content-Type","application/json")
                .header("Origin",WEB).header("Cookie",cookie).PUT(HttpRequest.BodyPublishers.ofString(body));
        return key==null?r:r.header("Idempotency-Key",key);
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
