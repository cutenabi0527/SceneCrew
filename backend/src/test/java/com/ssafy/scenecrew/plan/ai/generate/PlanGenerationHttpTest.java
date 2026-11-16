package com.ssafy.scenecrew.plan.ai.generate;

import java.net.URI;
import java.net.http.*;
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
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.aijob.repository.AiJobRepository;
import com.ssafy.scenecrew.support.FileTestBeans;
import static org.assertj.core.api.Assertions.*;

/**
 * #280 SC-API-043 최초 계획 생성 접수와 063 결과 조회의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis.
 * outbox 주기 실행이 꺼져 있어 AI 서버를 부르지 않는다. 결과는 SQL로 작업을 끝내 확인한다(응답 변환은 GeneratePlanKindReadTest).
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import({FileTestBeans.class, PlanGenerationHttpTest.Beans.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PlanGenerationHttpTest {
    static final String WEB="https://web.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"plan-generation-test"); r.add("scenecrew.auth.audience",()->"plan-generation-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
        r.add("scenecrew.ai.extraction.document-base-url",()->"https://app.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired GeneratePlanKind kind;
    @Autowired AiJobRepository jobs;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>();
    UUID chief, worker, outsider, event, zone, group, program;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); worker=account(); outsider=account();
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
        event=create();
        jdbc.update("""
                UPDATE event SET date='2026-11-18', prep_start_at='2026-11-18T00:00:00Z', open_at='2026-11-18T01:00:00Z',
                       close_at='2026-11-18T10:00:00Z', teardown_end_at='2026-11-18T11:00:00Z', location_name='합성 시연장',
                       address='합성 주소', total_capacity=10, expected_visitors=50, desired_reserve_count=1,
                       confirmed_input='{"entryRule":"QR 확인"}'::jsonb WHERE id=?""",event);
        zone=UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,'메인홀','무대와 객석','[]'::jsonb)",zone,event);
        program=UUID.randomUUID();
        jdbc.update("INSERT INTO program (id,event_id,zone_id,name,start_at,end_at) VALUES (?,?,?,'오프닝','2026-11-18T01:00:00Z','2026-11-18T02:00:00Z')",
                program,event,zone);
        group=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'안내조','',true)",group,event);
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'옛조','',false)",UUID.randomUUID(),event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,2,'WORKER','팀원이름',true,false,true,1)""",UUID.randomUUID(),event,worker,group);
    }
    @AfterEach void cleanup() {
        for (var a:accounts) {
            for (var e:jdbc.queryForList("SELECT DISTINCT event_id FROM member WHERE account_id=?",UUID.class,a)) {
                jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
                jdbc.update("DELETE FROM outbox_event WHERE event_id=?",e);
                jdbc.update("DELETE FROM ai_job WHERE event_id=?",e);
                jdbc.update("DELETE FROM intake_document WHERE event_id=?",e);
                jdbc.update("DELETE FROM member WHERE event_id=?",e);
                jdbc.update("DELETE FROM program WHERE event_id=?",e);
                jdbc.update("DELETE FROM work_group WHERE event_id=?",e);
                jdbc.update("DELETE FROM zone WHERE event_id=?",e);
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

    @Test void acceptQueuesOneGenerationFromCurrentDraftAndSameKeyReturnsFirstResponse() throws Exception {
        String key=UUID.randomUUID().toString();
        var r=generate(event,key,revision(),chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("jobId","statusUrl");
        UUID job=UUID.fromString(body.get("jobId").asString());
        assertThat(body.get("statusUrl").asString()).isEqualTo("/api/v1/ai-jobs/"+job);
        var row=jdbc.queryForMap("SELECT kind,state,context_revision,input::text AS input FROM ai_job WHERE id=?",job);
        assertThat(row).containsEntry("kind","GENERATE_PLAN").containsEntry("state","QUEUED");
        assertThat((Long)row.get("context_revision")).isEqualTo(revision());
        String raw=(String)row.get("input");
        var input=json.readTree(raw);
        // 문서 추출 결과가 없으면 평면도도 없고 키를 넣지 않는다(평면도를 모르는 이전 AI 서버도 받는다)
        assertThat(input.propertyNames()).containsExactlyInAnyOrder("event","zones","programs","workGroups");
        var e=input.get("event");
        assertThat(e.get("name").asString()).isEqualTo("계획 시험 공연");
        assertThat(e.get("date").asString()).isEqualTo("2026-11-18");
        assertThat(e.get("prepStartAt").asString()).isEqualTo("2026-11-18T00:00:00Z");
        assertThat(e.get("totalCapacity").asInt()).isEqualTo(10);
        assertThat(e.get("peakVisitors").isNull()).isTrue();
        assertThat(e.get("confirmedInput").get("entryRule").asString()).isEqualTo("QR 확인");
        assertThat(input.get("zones").get(0).get("id").asString()).isEqualTo(zone.toString());
        assertThat(input.get("programs").get(0).get("zoneId").asString()).isEqualTo(zone.toString());
        // 활성 그룹만 보내고, 팀원 이름·계정 같은 개인 정보는 넣지 않는다.
        assertThat(input.get("workGroups").size()).isEqualTo(1);
        assertThat(input.get("workGroups").get(0).get("id").asString()).isEqualTo(group.toString());
        assertThat(raw).doesNotContain("팀원이름").doesNotContain(worker.toString()).doesNotContain(chief.toString());
        // 같은 키(대소문자만 다름)는 새 작업 없이 최초 202
        var again=generate(event,key.toUpperCase(),revision(),chiefCookie);
        assertThat(again.statusCode()).isEqualTo(202);
        assertThat(json.readTree(again.body())).isEqualTo(body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isEqualTo(1);
        // 같은 행사에 진행 중인 생성이 있으면 새 키는 429(행사당 1개)
        error(generate(event,UUID.randomUUID().toString(),revision(),chiefCookie),429,"RATE_LIMITED");
    }

    @Test void rejectsWithoutAccessStateRevisionCompletenessOrConsent() throws Exception {
        long rev=revision();
        error(generate(event,UUID.randomUUID().toString(),rev,workerCookie),403,"FORBIDDEN");
        error(generate(event,UUID.randomUUID().toString(),rev,outsiderCookie),404,"NOT_FOUND");
        error(generate(UUID.randomUUID(),UUID.randomUUID().toString(),rev,chiefCookie),404,"NOT_FOUND");
        var stale=generate(event,UUID.randomUUID().toString(),rev+1,chiefCookie);
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("event");
        // 기본정보가 모자라면 422이고 작업을 만들지 않는다(publish와 같은 검사).
        jdbc.update("UPDATE event SET location_name=NULL, close_at='2026-11-18T00:30:00Z' WHERE id=?",event);
        var incomplete=generate(event,UUID.randomUUID().toString(),rev,chiefCookie);
        error(incomplete,422,"EVENT_INCOMPLETE");
        var fields=new ArrayList<String>();
        for (var f:json.readTree(incomplete.body()).get("details").get("fields"))
            fields.add(f.get("field").asString()+":"+f.get("reason").asString());
        assertThat(fields).contains("locationName:MISSING","closeAt:ORDER");
        jdbc.update("UPDATE event SET location_name='합성 시연장', close_at='2026-11-18T10:00:00Z' WHERE id=?",event);
        // 외부 AI 동의가 없으면 403
        jdbc.update("UPDATE account SET consent='{}'::jsonb WHERE id=?",chief);
        var denied=generate(event,UUID.randomUUID().toString(),rev,chiefCookie);
        error(denied,403,"FORBIDDEN");
        assertThat(json.readTree(denied.body()).get("details").get("reason").asString()).isEqualTo("EXTERNAL_AI_CONSENT_REQUIRED");
        jdbc.update("UPDATE account SET consent='{\"externalAi\":true}'::jsonb WHERE id=?",chief);
        // DRAFT가 아니면 409
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        error(generate(event,UUID.randomUUID().toString(),rev,chiefCookie),409,"INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isZero();
        // 형식 오류
        for (String body:List.of("{}","{\"expectedEventRevision\":1}","{\"expectedEventRevision\":\"01\"}",
                "{\"expectedEventRevision\":\"1\",\"x\":1}","[]"))
            error(send(post("/api/v1/events/"+event+"/plan-generations",body).header("Cookie",chiefCookie)
                    .header("Idempotency-Key",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(post("/api/v1/events/"+event+"/plan-generations","{\"expectedEventRevision\":\"1\"}").header("Cookie",chiefCookie)),
                400,"INVALID_REQUEST");
        error(send(post("/api/v1/events/"+event+"/plan-generations","{\"expectedEventRevision\":\"1\"}")
                .header("Idempotency-Key",UUID.randomUUID().toString())),401,"UNAUTHENTICATED");
    }

    @Test void chiefReadsPlanResultAndPublicErrors() throws Exception {
        var r=generate(event,UUID.randomUUID().toString(),revision(),chiefCookie);
        UUID job=UUID.fromString(json.readTree(r.body()).get("jobId").asString());
        var queued=read(job,chiefCookie);
        assertThat(queued.get("kind").asString()).isEqualTo("GENERATE_PLAN");
        assertThat(queued.get("state").asString()).isEqualTo("QUEUED");
        error(send(get("/api/v1/ai-jobs/"+job).header("Cookie",workerCookie)),404,"NOT_FOUND");
        error(send(get("/api/v1/ai-jobs/"+job).header("Cookie",outsiderCookie)),404,"NOT_FOUND");

        String ready="""
                {"plan":{"tasks":[{"tempId":"tmp-t1","workGroupId":"%s","bundleKey":"b1","zoneId":"%s","programId":null,
                 "title":"입구 안내","startAt":"2026-11-18T00:30:00Z","endAt":"2026-11-18T01:30:00Z","steps":["안내판 설치"],"checklist":[]}],
                 "zones":[{"id":"%s","name":"메인홀","description":"무대와 객석"}],"programs":[],
                 "workGroups":[{"id":"%s","name":"안내조","instructions":"입구 안내"}],"instructions":[]},
                 "status":"READY","questions":[]}""".formatted(group,zone,zone,group);
        jdbc.update("UPDATE ai_job SET state='SUCCEEDED', attempt=1, result=?::jsonb WHERE id=?",ready,job);
        var done=read(job,chiefCookie);
        assertThat(done.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("error").isNull()).isTrue();
        var result=done.get("result");
        assertThat(result.propertyNames()).containsExactly("status","questions","plan");
        assertThat(result.get("plan").get("tasks").get(0).get("tempId").asString()).isEqualTo("tmp-t1");

        String needsInfo="{\"status\":\"NEEDS_INFO\",\"questions\":[{\"key\":\"q1\",\"field\":\"totalCapacity\",\"question\":\"몇 명인가요?\"}],\"plan\":null}";
        jdbc.update("UPDATE ai_job SET result=?::jsonb WHERE id=?",needsInfo,job);
        var question=read(job,chiefCookie).get("result");
        assertThat(question.get("status").asString()).isEqualTo("NEEDS_INFO");
        assertThat(question.get("plan").isNull()).isTrue();

        // 실패는 공개 코드로. AI_REVIEW_FAILED·INVALID_REQUEST는 그대로, 계약 위반 응답은 AI_UNAVAILABLE(재시도 아님)
        assertThat(failed(job,"AI_REVIEW_FAILED",false)).isEqualTo("AI_REVIEW_FAILED:false");
        assertThat(failed(job,"INVALID_REQUEST",false)).isEqualTo("INVALID_REQUEST:false");
        assertThat(failed(job,"AI_UNAVAILABLE",true)).isEqualTo("AI_UNAVAILABLE:true");
        // 계약 위반 응답은 엔진이 AI_UNAVAILABLE(details.reason=INVALID_RESULT, retryable=false)로 저장한다.
        assertThat(failed(job,"AI_UNAVAILABLE",false)).isEqualTo("AI_UNAVAILABLE:false");
        assertThat(failed(job,"AI_TIMEOUT",false)).isEqualTo("AI_TIMEOUT:false");
        jdbc.update("UPDATE ai_job SET state='STALE_CONTEXT', error=?::jsonb WHERE id=?",
                "{\"code\":\"READ_CONTEXT_CHANGED\",\"message\":\"m\",\"retryable\":false}",job);
        var stale=read(job,chiefCookie);
        assertThat(stale.get("state").asString()).isEqualTo("FAILED");
        assertThat(stale.get("error").get("code").asString()).isEqualTo("STALE_CONTEXT");
    }

    /**
     * AI 결과에 안내가 비어 있으면 기록 직전(apply)에 지금 DRAFT 안내로 채운다. 028이 안내를 요청 값으로 덮어써서,
     * 그대로 두면 후보를 저장하는 순간 총괄이 써 둔 안내가 지워진다(#280 리뷰). AI가 낸 안내는 그대로 둔다.
     */
    @Test void applyKeepsCurrentInstructionsWhenTheCandidateLeavesThemEmpty() throws Exception {
        jdbc.update("UPDATE work_group SET instructions='입구에서 QR 확인' WHERE id=?",group);
        jdbc.update("UPDATE event SET instructions='행사 공통 안내' WHERE id=?",event);
        var r=generate(event,UUID.randomUUID().toString(),revision(),chiefCookie);
        var job=jobs.findById(UUID.fromString(json.readTree(r.body()).get("jobId").asString())).orElseThrow();
        String empty="""
                {"status":"READY","questions":[],"plan":{"workGroups":[{"id":"%s","name":"안내조","instructions":""},
                 {"tempId":"tmp-g","name":"새조","instructions":""}],"zones":[],"programs":[],"tasks":[],"instructions":[]}}"""
                .formatted(group);
        var filled=json.readTree(kind.apply(job,AiJobOutcome.succeeded("5","m",empty)).resultJson()).get("plan");
        assertThat(filled.get("workGroups").get(0).get("instructions").asString()).isEqualTo("입구에서 QR 확인");
        assertThat(filled.get("workGroups").get(1).get("instructions").asString()).isEmpty();   // 새 그룹은 안내가 없다
        assertThat(filled.get("instructions").size()).isEqualTo(1);
        assertThat(filled.get("instructions").get(0).get("scope").asString()).isEqualTo("EVENT");
        assertThat(filled.get("instructions").get(0).get("body").asString()).isEqualTo("행사 공통 안내");

        String fromAi="""
                {"status":"READY","questions":[],"plan":{"workGroups":[{"id":"%s","name":"안내조","instructions":"AI 안내"}],
                 "zones":[],"programs":[],"tasks":[],"instructions":[{"scope":"EVENT","workGroupId":null,"body":"AI 행사 안내"}]}}"""
                .formatted(group);
        var kept=json.readTree(kind.apply(job,AiJobOutcome.succeeded("5","m",fromAi)).resultJson()).get("plan");
        assertThat(kept.get("workGroups").get(0).get("instructions").asString()).isEqualTo("AI 안내");
        assertThat(kept.get("instructions").get(0).get("body").asString()).isEqualTo("AI 행사 안내");

        String question="{\"status\":\"NEEDS_INFO\",\"questions\":[{\"key\":\"q\",\"field\":\"f\",\"question\":\"?\"}],\"plan\":null}";
        assertThat(json.readTree(kind.apply(job,AiJobOutcome.succeeded("5","m",question)).resultJson()).get("plan").isNull()).isTrue();
    }

    /** 관객 수는 BIGINT 그대로 보낸다. 32비트로 줄이지 않는다(#280 리뷰). */
    /**
     * V9-D54(#391): 가장 최근에 끝난 추출의 floorPlanPages에서 원본이 남아 있고 만료 전·쪽수 안인 첫 PDF 후보를 보낸다.
     * MD·TXT(쪽수 없음)는 앞에 있어도 건너뛴다(AI는 PDF·그림만 받는다).
     */
    @Test void floorPlanIsTheFirstValidPdfPageOfTheLatestExtraction() throws Exception {
        UUID expired=document(3,"now()-interval '1 second'",true), ranged=document(3,"now()+interval '1 day'",true),
                deleted=document(3,"now()+interval '1 day'",false), older=document(3,"now()+interval '1 day'",true),
                markdown=document(null,"now()+interval '1 day'",true,"text/markdown");
        extraction("[{\"documentId\":\""+older+"\",\"pageNumber\":1}]","now()-interval '1 hour'","SUCCEEDED");
        extraction("[{\"documentId\":\""+markdown+"\",\"pageNumber\":1},{\"documentId\":\""+expired+"\",\"pageNumber\":1},"
                +"{\"documentId\":\""+deleted+"\",\"pageNumber\":1},"
                +"{\"documentId\":\""+ranged+"\",\"pageNumber\":4},{\"documentId\":\""+ranged+"\",\"pageNumber\":2}]",
                "now()-interval '1 minute'","PARTIAL");
        var floorPlan=floorPlanOf(generate(event,UUID.randomUUID().toString(),revision(),chiefCookie));
        // ai_job.input은 jsonb라 키 순서를 보지 않는다
        assertThat(floorPlan.propertyNames()).containsExactlyInAnyOrder("documentId","pageNumber","mime","sha256","url");
        assertThat(floorPlan.get("documentId").asString()).isEqualTo(ranged.toString());
        assertThat(floorPlan.get("pageNumber").asInt()).isEqualTo(2);
        assertThat(floorPlan.get("mime").asString()).isEqualTo("application/pdf");
        assertThat(floorPlan.get("sha256").asString()).isEqualTo("c".repeat(64));
        assertThat(floorPlan.get("url").asString()).isEqualTo("https://app.example.invalid/internal/intake-documents/"+ranged);

        // 가장 최근 추출에 평면도가 없으면(예전 추출에 있어도) 보내지 않는다
        jdbc.update("UPDATE ai_job SET state='SUCCEEDED', finished_at=now() WHERE event_id=? AND kind='GENERATE_PLAN'",event);
        extraction(null,"now()","SUCCEEDED");
        assertThat(floorPlanOf(generate(event,UUID.randomUUID().toString(),revision(),chiefCookie))).isNull();   // 키 없음
    }

    /** V9-D66: 가장 최근에 끝난 추출의 scheduleItems를 그대로 싣고, 없으면 키를 넣지 않는다 */
    @Test void scheduleItemsComeFromTheLatestExtraction() throws Exception {
        String item="{\"key\":\"d1p7r1\",\"title\":\"A구역 동선 점검\",\"startAt\":\"2026-11-13T11:30:00+09:00\","
                +"\"endAt\":\"2026-11-13T11:50:00+09:00\",\"timeText\":\"11:30~11:50\",\"workGroupName\":\"안전 통제\","
                +"\"zoneName\":\"A구역\",\"bundle\":\"안전 A\",\"source\":{\"documentId\":\""+UUID.randomUUID()+"\",\"pageNumber\":7}}";
        // 시각 없이(문서에 날짜가 없어) 저장된 행
        String untimed="{\"key\":\"d1p7r2\",\"title\":\"입장 안내\",\"startAt\":null,\"endAt\":null,"
                +"\"timeText\":\"12:00~12:20\",\"workGroupName\":null,\"zoneName\":null,\"bundle\":null,"
                +"\"source\":{\"documentId\":\""+UUID.randomUUID()+"\",\"pageNumber\":7}}";
        extractionWith("\"scheduleItems\":["+item+","+untimed+"],","now()-interval '1 minute'");
        JsonNode schedule=inputOf(generate(event,UUID.randomUUID().toString(),revision(),chiefCookie)).get("scheduleItems");
        assertThat(schedule.size()).isEqualTo(2);
        // 시각은 원문 표기와 지금 공연 날짜(2026-11-18)로 다시 만든다
        assertThat(schedule.get(0).get("startAt").asString()).isEqualTo("2026-11-18T11:30:00+09:00");
        assertThat(schedule.get(0).get("endAt").asString()).isEqualTo("2026-11-18T11:50:00+09:00");
        assertThat(schedule.get(1).get("startAt").asString()).isEqualTo("2026-11-18T12:00:00+09:00");
        assertThat(schedule.get(0).get("workGroupName").asString()).isEqualTo("안전 통제");

        jdbc.update("UPDATE ai_job SET state='SUCCEEDED', finished_at=now() WHERE event_id=? AND kind='GENERATE_PLAN'",event);
        extraction(null,"now()","SUCCEEDED");
        assertThat(inputOf(generate(event,UUID.randomUUID().toString(),revision(),chiefCookie)).has("scheduleItems")).isFalse();
    }

    JsonNode inputOf(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        UUID job=UUID.fromString(json.readTree(r.body()).get("jobId").asString());
        return json.readTree(jdbc.queryForObject("SELECT input::text FROM ai_job WHERE id=?",String.class,job));
    }
    void extractionWith(String extra,String finishedAt) {
        String result="{\"fields\":{},\"missingRequired\":[],\"conflictingValues\":[],\"unreadableFiles\":[],"+extra
                +"\"resultStatus\":\"COMPLETE\"}";
        jdbc.update("""
                INSERT INTO ai_job (id,event_id,requested_by_member_id,kind,state,input_hash,context_revision,request_schema_version,
                                    result_schema_version,model_version,input,result,attempt,execution_meta,auto_reanalysis_count,
                                    created_at,deadline_at,finished_at)
                VALUES (?,?,NULL,'EXTRACT_EVENT_INPUT','SUCCEEDED',?,0,'4','3','m','{"documents":[]}'::jsonb,?::jsonb,1,'{}'::jsonb,0,
                        now()-interval '2 hours',now(),"""+finishedAt+")",
                UUID.randomUUID(),event,"a".repeat(64),result);
    }

    JsonNode floorPlanOf(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        UUID job=UUID.fromString(json.readTree(r.body()).get("jobId").asString());
        return json.readTree(jdbc.queryForObject("SELECT input::text FROM ai_job WHERE id=?",String.class,job)).get("floorPlan");
    }
    UUID document(Integer pageCount,String expiresAt,boolean stored) { return document(pageCount,expiresAt,stored,"application/pdf"); }
    UUID document(Integer pageCount,String expiresAt,boolean stored,String mime) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO intake_document (id,event_id,owner_account_id,original_name,mime,bytes,page_count,sha256,storage_key,created_at,expires_at)"
                +" VALUES (?,?,?,'평면도',?,10,?,?,?,now(),"+expiresAt+")",
                id,event,chief,mime,pageCount,"c".repeat(64),stored?"intake/"+id:null);
        return id;
    }
    void extraction(String floorPlanPages,String finishedAt,String state) {
        String result="{\"fields\":{},\"missingRequired\":[],\"conflictingValues\":[],\"unreadableFiles\":[],"
                +(floorPlanPages==null?"":"\"floorPlanPages\":"+floorPlanPages+",")+"\"resultStatus\":\"COMPLETE\"}";
        jdbc.update("""
                INSERT INTO ai_job (id,event_id,requested_by_member_id,kind,state,input_hash,context_revision,request_schema_version,
                                    result_schema_version,model_version,input,result,attempt,execution_meta,auto_reanalysis_count,
                                    created_at,deadline_at,finished_at)
                VALUES (?,?,NULL,'EXTRACT_EVENT_INPUT',?,?,0,'4','3','m','{"documents":[]}'::jsonb,?::jsonb,1,'{}'::jsonb,0,
                        now()-interval '2 hours',now(),"""+finishedAt+")",
                UUID.randomUUID(),event,state,"a".repeat(64),result);
    }

    @Test void visitorCountsAreSentAsSigned64() throws Exception {
        jdbc.update("UPDATE event SET expected_visitors=3000000000, peak_visitors=2147483648 WHERE id=?",event);
        var r=generate(event,UUID.randomUUID().toString(),revision(),chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        UUID job=UUID.fromString(json.readTree(r.body()).get("jobId").asString());
        var e=json.readTree(jdbc.queryForObject("SELECT input::text FROM ai_job WHERE id=?",String.class,job)).get("event");
        assertThat(e.get("expectedVisitors").asLong()).isEqualTo(3_000_000_000L);
        assertThat(e.get("peakVisitors").asLong()).isEqualTo(2_147_483_648L);
    }

    // ---- 도우미 -------------------------------------------------------------------------------------------

    String failed(UUID job,String code,boolean retryable) throws Exception {
        jdbc.update("UPDATE ai_job SET state='FAILED', result=NULL, error=?::jsonb WHERE id=?",
                "{\"code\":\""+code+"\",\"message\":\"m\",\"retryable\":"+retryable+"}",job);
        var r=read(job,chiefCookie);
        assertThat(r.get("state").asString()).isEqualTo("FAILED");
        assertThat(r.get("result").isNull()).isTrue();
        return r.get("error").get("code").asString()+":"+r.get("error").get("retryable").asBoolean();
    }
    long revision() { return jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event); }
    HttpResponse<String> generate(UUID event,String key,long revision,String cookie) throws Exception {
        return send(post("/api/v1/events/"+event+"/plan-generations","{\"expectedEventRevision\":\""+revision+"\"}")
                .header("Cookie",cookie).header("Idempotency-Key",key));
    }
    JsonNode read(UUID job,String cookie) throws Exception {
        var r=send(get("/api/v1/ai-jobs/"+job).header("Cookie",cookie));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
    UUID create() throws Exception {
        var r=send(post("/api/v1/events","{\"name\":\"계획 시험 공연\"}").header("Cookie",chiefCookie)
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
