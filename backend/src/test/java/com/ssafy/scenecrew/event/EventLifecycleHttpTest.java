package com.ssafy.scenecrew.event;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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
import com.ssafy.scenecrew.event.service.InviteTokens;
import static org.assertj.core.api.Assertions.*;

/** SC-API-022·024·029·030·031의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis. 초대 열기는 기록용 InviteTokens로 확인한다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({EventLifecycleHttpTest.Beans.class, com.ssafy.scenecrew.support.FileTestBeans.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EventLifecycleHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final List<UUID> ISSUED=new CopyOnWriteArrayList<>(), ACTIVATED=new CopyOnWriteArrayList<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"event-test"); r.add("scenecrew.auth.audience",()->"event-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.ssafy.scenecrew.support.FakeFileStorage storage;
    @Autowired PasswordEncoder encoder;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>();
    UUID chief, worker, outsider;
    String chiefCookie;

    @BeforeEach void fixture() throws Exception {
        ISSUED.clear(); ACTIVATED.clear();
        chief=account(); worker=account(); outsider=account();
        chiefCookie=login(chief);
    }
    @AfterEach void cleanup() {
        for (var a:accounts) {
            for (var e:jdbc.queryForList("SELECT event_id FROM member WHERE account_id=?",UUID.class,a)) {
                jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
                jdbc.update("DELETE FROM ai_job WHERE event_id=?",e);
                jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
                jdbc.update("DELETE FROM venue_layout_background WHERE event_id=?",e);
                jdbc.update("DELETE FROM intake_document WHERE event_id=?",e);
                jdbc.update("DELETE FROM venue_layout WHERE event_id=?",e);
                jdbc.update("DELETE FROM event_unconfirmed_draft WHERE event_id=?",e);
                jdbc.update("DELETE FROM safety_camera WHERE event_id=?",e);
                jdbc.update("DELETE FROM equipment_asset WHERE event_id=?",e);
                jdbc.update("DELETE FROM task WHERE event_id=?",e);
                jdbc.update("DELETE FROM program WHERE event_id=?",e);
                jdbc.update("DELETE FROM zone WHERE event_id=?",e);
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

    @Test void createPatchPublishStartEnd() throws Exception {
        String key=UUID.randomUUID().toString();
        var created=send(post("/api/v1/events","{\"name\":\"  가을 공연  \",\"description\":\"설명\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",key));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).hasValue("no-store");
        var event=json.readTree(created.body());
        assertThat(event.propertyNames()).containsExactlyInAnyOrder("id","name","state","revision","date","prepStartAt",
                "openAt","closeAt","teardownEndAt","locationName","address","totalCapacity","expectedVisitors","peakVisitors",
                "desiredReserveCount","myParticipation","description","instructions");
        assertThat(event.get("name").asString()).isEqualTo("가을 공연");
        assertThat(event.get("state").asString()).isEqualTo("DRAFT");
        assertThat(event.get("revision").asString()).isEqualTo("1");
        assertThat(event.get("desiredReserveCount").asString()).isEqualTo("0");
        assertThat(event.get("date").isNull()).isTrue();
        assertThat(event.get("instructions").asString()).isEmpty();
        var me=event.get("myParticipation");
        assertThat(me.get("eventRole").asString()).isEqualTo("CHIEF");
        assertThat(me.get("participationRevision").asString()).isEqualTo("1");
        assertThat(me.get("workGroupId").isNull()).isTrue();
        UUID id=UUID.fromString(event.get("id").asString());
        assertThat(jdbc.queryForObject("SELECT display_name FROM member WHERE event_id=? AND account_id=?",String.class,id,chief))
                .isEqualTo("fixture");

        // 같은 키(대소문자만 다름) 재요청은 최초 응답 그대로, 행사는 하나다.
        var again=send(post("/api/v1/events","{\"name\":\"  가을 공연  \",\"description\":\"설명\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",key.toUpperCase()));
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(json.readTree(again.body())).isEqualTo(event);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE account_id=?",Long.class,chief)).isEqualTo(1);
        // 초대 토큰은 생성 때 한 번 발급한다. 재요청은 다시 발급하지 않는다.
        assertThat(ISSUED).containsExactly(id);

        var patched=send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie));
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        var p=json.readTree(patched.body());
        assertThat(p.get("revision").asString()).isEqualTo("2");
        assertThat(p.get("date").asString()).isEqualTo("2026-11-18");
        assertThat(p.get("prepStartAt").asString()).isEqualTo("2026-11-18T00:00:00Z");
        assertThat(p.get("totalCapacity").asString()).isEqualTo("10");
        assertThat(p.get("peakVisitors").isNull()).isTrue();
        // 같은 값만 다시 보내면 revision을 올리지 않는다.
        var same=send(patch("/api/v1/events/"+id,complete("2")).header("Cookie",chiefCookie));
        assertThat(json.readTree(same.body()).get("revision").asString()).isEqualTo("2");
        plan(id);

        String publishKey=UUID.randomUUID().toString();
        var published=send(post("/api/v1/events/"+id+"/publish","{\"expectedEventRevision\":\"2\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",publishKey));
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        assertThat(json.readTree(published.body()).get("state").asString()).isEqualTo("RECRUITING");
        assertThat(json.readTree(published.body()).get("revision").asString()).isEqualTo("3");
        assertThat(ACTIVATED).containsExactly(id);
        // 이미 RECRUITING이어도 같은 키는 최초 응답을 돌려받고 초대를 다시 열지 않는다.
        var replay=send(post("/api/v1/events/"+id+"/publish","{\"expectedEventRevision\":\"2\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",publishKey));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(published.body()));
        assertThat(ACTIVATED).hasSize(1);
        assertThat(ISSUED).containsExactly(id);
        // 공개 뒤에는 기본정보를 이 API로 고치지 않는다.
        error(send(patch("/api/v1/events/"+id,"{\"name\":\"새이름\",\"expectedEventRevision\":\"3\"}").header("Cookie",chiefCookie)),
                409,"INVALID_STATE");

        var started=send(post("/api/v1/events/"+id+"/start","{\"expectedEventRevision\":\"3\"}").header("Cookie",chiefCookie));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        assertThat(json.readTree(started.body()).get("state").asString()).isEqualTo("LIVE");
        assertThat(json.readTree(started.body()).get("revision").asString()).isEqualTo("4");

        var ended=send(post("/api/v1/events/"+id+"/end","{\"reason\":\"  우천 취소 \",\"expectedEventRevision\":\"4\"}")
                .header("Cookie",chiefCookie));
        assertThat(ended.statusCode()).as(ended.body()).isEqualTo(200);
        assertThat(json.readTree(ended.body()).get("state").asString()).isEqualTo("ENDED");
        assertThat(json.readTree(ended.body()).get("revision").asString()).isEqualTo("5");
        var row=jdbc.queryForMap("SELECT end_reason, ended_at FROM event WHERE id=?",id);
        assertThat(row.get("end_reason")).isEqualTo("우천 취소");
        assertThat(row.get("ended_at")).isNotNull();
        error(send(post("/api/v1/events/"+id+"/end","{\"expectedEventRevision\":\"5\"}").header("Cookie",chiefCookie)),
                409,"INVALID_STATE");
    }

    /** 만든 행사에서 접근을 잃으면 같은 키 재요청도 최초 본문을 돌려주지 않는다(#378). */
    @Test void createReplayRequiresCurrentAccessToTheEvent() throws Exception {
        String key=UUID.randomUUID().toString();
        String body="{\"name\":\"재요청\"}";
        var first=send(post("/api/v1/events",body).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        UUID id=UUID.fromString(json.readTree(first.body()).get("id").asString());
        jdbc.update("UPDATE member SET active=false WHERE event_id=? AND account_id=?",id,chief);
        var lost=send(post("/api/v1/events",body).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        error(lost,404,"NOT_FOUND");
        assertThat(lost.body()).doesNotContain(id.toString()).doesNotContain("재요청");
        // 다시 활성이면 최초 201을 그대로 받는다. 행사는 처음 하나뿐이다.
        jdbc.update("UPDATE member SET active=true WHERE event_id=? AND account_id=?",id,chief);
        var again=send(post("/api/v1/events",body).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(json.readTree(again.body())).isEqualTo(json.readTree(first.body()));
        // 나갔다가 다시 들어와 참여 회차가 바뀌면, 활성이어도 옛 회차의 최초 본문은 보이지 않는다.
        jdbc.update("UPDATE member SET participation_revision=2 WHERE event_id=? AND account_id=?",id,chief);
        var rejoined=send(post("/api/v1/events",body).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        error(rejoined,404,"NOT_FOUND");
        assertThat(rejoined.body()).doesNotContain(id.toString()).doesNotContain("재요청");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE account_id=?",Long.class,chief)).isEqualTo(1);
    }

    @Test void createRejectsBadInputAndKeyMisuse() throws Exception {
        String key=UUID.randomUUID().toString();
        assertThat(send(post("/api/v1/events","{\"name\":\"하나\"}").header("Cookie",chiefCookie).header("Idempotency-Key",key))
                .statusCode()).isEqualTo(201);
        error(send(post("/api/v1/events","{\"name\":\"둘\"}").header("Cookie",chiefCookie).header("Idempotency-Key",key)),
                409,"IDEMPOTENCY_MISMATCH");
        // 이모지 20개(코드포인트 20)는 되고 21개는 400이다.
        String twenty="😀".repeat(20);
        assertThat(send(post("/api/v1/events","{\"name\":\""+twenty+"\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())).statusCode()).isEqualTo(201);
        for (String body:List.of("{\"name\":\""+twenty+"😀\"}","{\"name\":\"   \"}","{\"name\":\"\"}","{}","{\"name\":1}",
                "{\"name\":\"x\",\"state\":\"LIVE\"}","{\"name\":\"x\",\"description\":null}","[]"))
            error(send(post("/api/v1/events",body).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),
                    400,"INVALID_REQUEST");
        error(send(post("/api/v1/events","{\"name\":\"x\"}").header("Cookie",chiefCookie)),400,"INVALID_REQUEST");
        error(send(post("/api/v1/events","{\"name\":\"x\"}").header("Cookie",chiefCookie).header("Idempotency-Key","not-uuid")),
                400,"INVALID_REQUEST");
        error(send(post("/api/v1/events","{\"name\":\"x\"}").header("Idempotency-Key",UUID.randomUUID().toString())),
                401,"UNAUTHENTICATED");
        error(send(post("/api/v1/events","{\"name\":\"x\"}").setHeader("Origin","https://evil.example.invalid")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),403,"CSRF_REJECTED");
        error(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events"))).header("Content-Type","text/plain").header("Origin",WEB)
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}"))),415,"UNSUPPORTED_MEDIA_TYPE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE account_id=?",Long.class,chief)).isEqualTo(2);

        // Android Bearer도 생성할 수 있다.
        var login=send(post("/api/v1/auth/login","{\"email\":\""+chief+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .setHeader("Origin",ANDROID).header("X-SceneCrew-Client","ANDROID"));
        String token=json.readTree(login.body()).get("accessToken").asString();
        var android=send(HttpRequest.newBuilder(URI.create(url("/api/v1/events"))).header("Content-Type","application/json")
                .header("Authorization","Bearer "+token).header("Origin",ANDROID).header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"앱 행사\"}")));
        assertThat(android.statusCode()).as(android.body()).isEqualTo(201);
    }

    @Test void publishReportsEveryIncompleteFieldAndChangesNothing() throws Exception {
        UUID id=create("미완성");
        plan(id);
        var r=send(patch("/api/v1/events/"+id,"""
                {"date":"2026-11-18","prepStartAt":"2026-11-17T23:00:00+09:00","openAt":"2026-11-18T10:00:00+09:00",
                 "closeAt":"2026-11-18T10:00:00+09:00","teardownEndAt":"2026-11-18T21:00:00+09:00","locationName":"  ",
                 "totalCapacity":"40","expectedVisitors":"-1","expectedEventRevision":"1"}""").header("Cookie",chiefCookie));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String key=UUID.randomUUID().toString();
        var rejected=send(post("/api/v1/events/"+id+"/publish","{\"expectedEventRevision\":\"2\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",key));
        error(rejected,422,"EVENT_INCOMPLETE");
        var fields=new ArrayList<String>();
        for (JsonNode f:json.readTree(rejected.body()).get("details").get("fields"))
            fields.add(f.get("field").asString()+":"+f.get("reason").asString());
        assertThat(fields).containsExactlyInAnyOrder("locationName:BLANK","address:MISSING","closeAt:ORDER",
                "prepStartAt:DATE_MISMATCH","totalCapacity:RANGE","expectedVisitors:RANGE");
        assertThat(jdbc.queryForMap("SELECT state, revision FROM event WHERE id=?",id))
                .containsEntry("state","DRAFT").containsEntry("revision",2L);
        assertThat(ACTIVATED).isEmpty();
        // 실패는 기록을 남기지 않으므로 고친 뒤 같은 키로 다시 공개할 수 있다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE event_id=?",Long.class,id)).isZero();
        send(patch("/api/v1/events/"+id,complete("2")).header("Cookie",chiefCookie));
        var ok=send(post("/api/v1/events/"+id+"/publish","{\"expectedEventRevision\":\"3\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",key));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
    }

    @Test void onlyCurrentChiefInRightStateAndRevision() throws Exception {
        UUID id=create("권한");
        UUID group=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group VALUES (?, ?, '그룹', '', true)",group,id);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,2,'WORKER','fixture',true,false,true,1)""",UUID.randomUUID(),id,worker,group);
        String workerCookie=login(worker), outsiderCookie=login(outsider);
        String rev="{\"expectedEventRevision\":\"1\"}";
        error(send(patch("/api/v1/events/"+id,rev).header("Cookie",workerCookie)),403,"FORBIDDEN");
        error(send(post("/api/v1/events/"+id+"/publish",rev).header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString())),
                403,"FORBIDDEN");
        for (String cookie:List.of(outsiderCookie,chiefCookie)) {
            UUID target=cookie.equals(chiefCookie)?UUID.randomUUID():id;
            error(send(patch("/api/v1/events/"+target,rev).header("Cookie",cookie)),404,"NOT_FOUND");
            error(send(post("/api/v1/events/"+target+"/start",rev).header("Cookie",cookie)),404,"NOT_FOUND");
            error(send(post("/api/v1/events/"+target+"/end",rev).header("Cookie",cookie)),404,"NOT_FOUND");
        }
        error(send(post("/api/v1/events/"+id+"/start",rev).header("Cookie",chiefCookie)),409,"INVALID_STATE");
        error(send(post("/api/v1/events/"+id+"/end",rev).header("Cookie",chiefCookie)),409,"INVALID_STATE");
        var stale=send(patch("/api/v1/events/"+id,"{\"name\":\"x\",\"expectedEventRevision\":\"0\"}").header("Cookie",chiefCookie));
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("event");

        send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie));
        plan(id);
        // 문서 추출 중에는 공개하지 않는다.
        jdbc.update("""
                INSERT INTO ai_job (id,event_id,kind,state,input_hash,context_revision,request_schema_version,input,attempt,
                                    execution_meta,auto_reanalysis_count,created_at,deadline_at)
                VALUES (?,?,'EXTRACT_EVENT_INPUT','RUNNING','h',2,'v1','{}'::jsonb,1,'{}'::jsonb,0,now(),now()+interval '10 minutes')""",
                UUID.randomUUID(),id);
        error(send(post("/api/v1/events/"+id+"/publish","{\"expectedEventRevision\":\"2\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),409,"INVALID_STATE");
        jdbc.update("UPDATE ai_job SET state='SUCCEEDED' WHERE event_id=?",id);
        error(send(post("/api/v1/events/"+id+"/publish","{\"expectedEventRevision\":\"1\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),409,"VERSION_CONFLICT");
        assertThat(send(post("/api/v1/events/"+id+"/publish","{\"expectedEventRevision\":\"2\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())).statusCode()).isEqualTo(200);
        error(send(post("/api/v1/events/"+id+"/start",rev).header("Cookie",workerCookie)),403,"FORBIDDEN");
        // 모집 중에도 종료할 수 있다.
        var ended=send(post("/api/v1/events/"+id+"/end","{\"reason\":null,\"expectedEventRevision\":\"3\"}").header("Cookie",chiefCookie));
        assertThat(ended.statusCode()).as(ended.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT end_reason FROM event WHERE id=?",String.class,id)).isNull();
    }

    @Test void patchChecksFormatsAndClearsNullableFields() throws Exception {
        UUID id=create("형식");
        for (String body:List.of("{\"name\":null,\"expectedEventRevision\":\"1\"}","{\"desiredReserveCount\":null,\"expectedEventRevision\":\"1\"}",
                "{\"totalCapacity\":10,\"expectedEventRevision\":\"1\"}","{\"totalCapacity\":\"-0\",\"expectedEventRevision\":\"1\"}",
                "{\"totalCapacity\":\"010\",\"expectedEventRevision\":\"1\"}","{\"openAt\":\"2026-11-18T10:00:00\",\"expectedEventRevision\":\"1\"}",
                "{\"date\":\"2026-02-30\",\"expectedEventRevision\":\"1\"}","{\"date\":\"20261118\",\"expectedEventRevision\":\"1\"}",
                "{\"name\":\"x\"}","{\"name\":\"x\",\"expectedEventRevision\":1}","{\"state\":\"LIVE\",\"expectedEventRevision\":\"1\"}"))
            error(send(patch("/api/v1/events/"+id,body).header("Cookie",chiefCookie)),400,"INVALID_REQUEST");
        error(send(patch("/api/v1/events/not-a-uuid","{\"expectedEventRevision\":\"1\"}").header("Cookie",chiefCookie)),
                400,"INVALID_REQUEST");
        send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie));
        var cleared=send(patch("/api/v1/events/"+id,"{\"locationName\":null,\"peakVisitors\":\"5\",\"expectedEventRevision\":\"2\"}")
                .header("Cookie",chiefCookie));
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        var body=json.readTree(cleared.body());
        assertThat(body.get("locationName").isNull()).isTrue();
        assertThat(body.get("peakVisitors").asString()).isEqualTo("5");
        assertThat(body.get("address").asString()).isEqualTo("합성 장소 주소");
        assertThat(body.get("revision").asString()).isEqualTo("3");
    }

    @Test void numbersAcceptWholeSigned64Range() throws Exception {
        UUID id=create("범위");
        // 계약 DecimalBigInt는 signed64 전체다. 19자리도 받고, 업무 범위(음수 등)는 공개 때 본다.
        var r=send(patch("/api/v1/events/"+id,"""
                {"expectedVisitors":"1000000000000000000","peakVisitors":"9223372036854775807",
                 "totalCapacity":"-9223372036854775808","expectedEventRevision":"1"}""").header("Cookie",chiefCookie));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var body=json.readTree(r.body());
        assertThat(body.get("expectedVisitors").asString()).isEqualTo("1000000000000000000");
        assertThat(body.get("peakVisitors").asString()).isEqualTo("9223372036854775807");
        assertThat(body.get("totalCapacity").asString()).isEqualTo("-9223372036854775808");
        for (String value:List.of("9223372036854775808","-9223372036854775809","10000000000000000000","00","-0"," 1"))
            error(send(patch("/api/v1/events/"+id,"{\"peakVisitors\":\""+value+"\",\"expectedEventRevision\":\"2\"}")
                    .header("Cookie",chiefCookie)),400,"INVALID_REQUEST");
        // revision도 같은 범위: 최댓값은 형식이 맞아 409, 넘으면 400
        error(send(patch("/api/v1/events/"+id,"{\"expectedEventRevision\":\"9223372036854775807\"}").header("Cookie",chiefCookie)),
                409,"VERSION_CONFLICT");
        error(send(patch("/api/v1/events/"+id,"{\"expectedEventRevision\":\"9223372036854775808\"}").header("Cookie",chiefCookie)),
                400,"INVALID_REQUEST");
    }

    @Test void publishReplayStillWorksAfterChiefTransfer() throws Exception {
        UUID id=create("인계");
        send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie));
        plan(id);
        String key=UUID.randomUUID().toString();
        String body="{\"expectedEventRevision\":\"2\"}";
        var first=send(post("/api/v1/events/"+id+"/publish",body).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        // 총괄을 다른 활성 멤버에게 넘기고 이전 총괄은 같은 참여 회차의 팀원으로 남는다.
        UUID group=group(id,"인계조",true);
        UUID successor=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,2,'WORKER','fixture',true,false,true,1)""",successor,id,worker,group);
        jdbc.update("UPDATE member SET event_role='WORKER', work_group_id=? WHERE event_id=? AND account_id=?",group,id,chief);
        jdbc.update("UPDATE member SET event_role='CHIEF' WHERE id=?",successor);
        // 같은 키·같은 본문은 새로 실행하지 않고 최초 200을 돌려준다.
        var replay=send(post("/api/v1/events/"+id+"/publish",body).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(first.body()));
        assertThat(ACTIVATED).containsExactly(id);
        // 새 키는 새 실행이라 총괄이 아니면 403이다.
        error(send(post("/api/v1/events/"+id+"/publish",body).header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),403,"FORBIDDEN");
    }

    @Test void emptyPlanCannotBePublished() throws Exception {
        UUID id=create("빈계획");
        send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie));
        assertThat(publishFields(id,"2")).containsExactlyInAnyOrder("workGroups:MISSING","tasks:MISSING");
        // 비활성 그룹의 업무만 있으면 여전히 빈 계획이다. 취소된 업무도 세지 않는다.
        UUID zone=zone(id,"무대");
        UUID inactive=group(id,"철수조",false), active=group(id,"안내조",true);
        UUID orphan=task(id,inactive,zone,"b1","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        UUID canceled=task(id,active,zone,"b2","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        jdbc.update("UPDATE task SET status='CANCELED' WHERE id=?",canceled);
        assertThat(publishFields(id,"2")).containsExactlyInAnyOrder("tasks:MISSING","tasks.workGroupId:INVALID_REFERENCE:"+orphan);
        jdbc.update("UPDATE task SET status='WAITING' WHERE id=?",canceled);
        jdbc.update("DELETE FROM task WHERE id=?",orphan);
        assertThat(publish(id,"2").statusCode()).isEqualTo(200);
    }

    @Test void publishAcceptsUpperBoundaries() throws Exception {
        UUID id=create("경계");
        send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie));
        // 상한은 저장된 전체 건수: 구역10·프로그램20·그룹31(비활성 포함)·업무200(취소 포함). 안내는 모두 비어 있다.
        var zones=new ArrayList<UUID>();
        for (int i=0;i<10;i++) zones.add(zone(id,"z"+i));
        for (int i=0;i<20;i++) program(id,zones.get(0),"p"+i,"2026-11-18T01:00:00Z","2026-11-18T11:00:00Z");
        var groups=new ArrayList<UUID>();
        for (int i=0;i<31;i++) groups.add(group(id,"g"+i,i<2));
        UUID g0=groups.get(0);
        // 묶음 수는 정원(10)과 무관하다. 업무 200개를 각자 다른 묶음으로 두고 그중 하나는 취소한다.
        for (int i=0;i<199;i++) task(id,g0,zones.get(0),"b"+i,"2026-11-18T00:00:00Z","2026-11-18T12:00:00Z");
        UUID edge=task(id,groups.get(1),zones.get(1),"edge","2026-11-18T00:00:00Z","2026-11-18T12:00:00Z");
        jdbc.update("UPDATE task SET status='CANCELED' WHERE id=(SELECT id FROM task WHERE event_id=? AND bundle_key='b0')",id);
        String steps=json.writeValueAsString(Collections.nCopies(6,"가".repeat(120)));
        var checklist=new ArrayList<Map<String,Object>>();
        for (int i=0;i<6;i++) checklist.add(Map.of("key","k"+i,"text","나".repeat(80),"required",i==0));
        jdbc.update("UPDATE task SET steps=?::jsonb, checklist=?::jsonb WHERE id=?",steps,json.writeValueAsString(checklist),edge);
        // 같은 묶음 안에서 끝과 시작이 맞닿는 것은 겹침이 아니다.
        task(id,g0,zones.get(0),"chain","2026-11-18T00:00:00Z","2026-11-18T01:00:00Z");
        jdbc.update("DELETE FROM task WHERE event_id=? AND bundle_key='b1'",id);
        task(id,g0,zones.get(0),"chain","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        jdbc.update("DELETE FROM task WHERE event_id=? AND bundle_key='b2'",id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE event_id=?",Long.class,id)).isEqualTo(200);
        var r=publish(id,"2");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    @Test void publishRejectsJustOverBoundaries() throws Exception {
        UUID id=create("초과");
        send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie));
        var zones=new ArrayList<UUID>();
        for (int i=0;i<11;i++) zones.add(zone(id,"z"+i));
        for (int i=0;i<21;i++) program(id,zones.get(0),"p"+i,"2026-11-18T01:00:00Z","2026-11-18T11:00:00Z");
        var groups=new ArrayList<UUID>();
        for (int i=0;i<32;i++) groups.add(group(id,"g"+i,i<2));
        UUID g0=groups.get(0), g1=groups.get(1), z=zones.get(0);
        for (int i=0;i<195;i++) task(id,g0,z,"b"+i,"2026-11-18T00:00:00Z","2026-11-18T12:00:00Z");
        jdbc.update("UPDATE task SET status='CANCELED' WHERE id=(SELECT id FROM task WHERE event_id=? AND bundle_key='b0')",id);
        UUID manySteps=task(id,g0,z,"s1","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        jdbc.update("UPDATE task SET steps=?::jsonb WHERE id=?",json.writeValueAsString(Collections.nCopies(7,"a")),manySteps);
        UUID longStep=task(id,g0,z,"s2","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        jdbc.update("UPDATE task SET steps=?::jsonb WHERE id=?",json.writeValueAsString(List.of("가".repeat(121))),longStep);
        UUID longCheck=task(id,g0,z,"s3","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        jdbc.update("UPDATE task SET checklist=?::jsonb WHERE id=?",
                json.writeValueAsString(List.of(Map.of("key","k","text","나".repeat(81),"required",true))),longCheck);
        UUID mixed=task(id,g1,z,"b1","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        UUID late=task(id,g0,z,"s4","2026-11-18T11:00:00Z","2026-11-18T12:00:01Z");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE event_id=?",Long.class,id)).isEqualTo(200);
        UUID overflow=task(id,g0,z,"b2","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        UUID lateProgram=program(id,null,"늦은공연","2026-11-18T10:00:00Z","2026-11-18T11:00:01Z");
        assertThat(publishFields(id,"2")).contains("zones:LIMIT","programs:LIMIT","workGroups:LIMIT","tasks:LIMIT",
                "tasks.steps:RANGE:"+manySteps,"tasks.steps:RANGE:"+longStep,"tasks.checklist:RANGE:"+longCheck,
                "tasks.bundleKey:BUNDLE_MISMATCH:"+mixed,"tasks.time:TIME_OUT_OF_RANGE:"+late,
                "tasks.bundleKey:TIME_OVERLAP:"+overflow,"programs.time:TIME_OUT_OF_RANGE:"+lateProgram)
                .doesNotContain("workGroups:MISSING","tasks:MISSING");
        assertThat(jdbc.queryForObject("SELECT state FROM event WHERE id=?",String.class,id)).isEqualTo("DRAFT");
    }

    /**
     * #280 V9-D45: 미확정 초안의 평면도는 발행할 때 안전 지도로 확정된다. 지금 구역을 가리키는 도형만 남긴다.
     * 대문자 UUID도 같은 구역으로 보고, 점이 50개를 넘는 도형은 안전 지도 저장(EXT11) 상한에 맞춰 뺀다.
     */
    @Test void publishSavesUnconfirmedVenueLayoutAsSafetyMap() throws Exception {
        UUID id=create("평면도 공연");
        assertThat(send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie)).statusCode()).isEqualTo(200);
        UUID main=zone(id,"메인홀");
        UUID lobby=zone(id,"로비");
        task(id,group(id,"기본조",true),main,"base","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        UUID chiefMember=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,id,chief);
        String square="[{\"x\":0.1,\"y\":0.1},{\"x\":0.5,\"y\":0.1},{\"x\":0.5,\"y\":0.4},{\"x\":0.1,\"y\":0.4}]";
        String layout="""
                {"coordinateSystem":"normalized-image","width":1200,"height":800,
                 "zonePolygons":[{"zoneId":"%s","points":%s},{"zoneId":"%s","points":%s},{"zoneId":"tmp-z-new","points":%s},
                                 {"zoneId":"%s","points":%s},{"zoneId":"%s","points":%s},{"zoneId":"%s","points":%s}],
                 "source":{"sourceKind":"MANUAL_CONFIRMATION","sourceRef":"plan-generation","observedAt":null}}"""
                .formatted(main,manyPoints(51),main,square,square,UUID.randomUUID(),square,main,square,
                        lobby.toString().toUpperCase(),square);
        draft(id,chiefMember,layout);

        var published=publish(id,"2");
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        assertThat(json.readTree(published.body()).get("revision").asString()).isEqualTo("3");
        var row=jdbc.queryForMap("""
                SELECT revision, coordinate_system, width, height, zone_polygons::text AS polygons, source::text AS source,
                       confirmed_by_member_id FROM venue_layout WHERE event_id=?""",id);
        assertThat(row.get("revision")).isEqualTo(1L);
        assertThat(row.get("coordinate_system")).isEqualTo("normalized-image");
        assertThat(((java.math.BigDecimal)row.get("width")).intValue()).isEqualTo(1200);
        assertThat(row.get("confirmed_by_member_id")).isEqualTo(chiefMember);
        // 점 51개 도형·임시 id·없는 구역·같은 구역 두 번째 도형은 빠지고, 메인홀과 대문자로 적은 로비가 남는다.
        var polygons=json.readTree((String)row.get("polygons"));
        assertThat(polygons.size()).isEqualTo(2);
        assertThat(polygons.get(0).get("zoneId").asString()).isEqualTo(main.toString());
        assertThat(polygons.get(0).get("points").size()).isEqualTo(4);
        assertThat(polygons.get(1).get("zoneId").asString()).isEqualTo(lobby.toString());
        var source=json.readTree((String)row.get("source"));
        assertThat(source.get("confirmed").asBoolean()).isTrue();
        assertThat(source.get("confirmedByMemberId").asString()).isEqualTo(chiefMember.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_log WHERE event_id=? AND type='SAFETY_MAP_SAVED'",
                Long.class,id)).isEqualTo(1);
    }

    /** V9-D62: 평면도 근거가 입력 PDF 한 쪽이면 발행 뒤 그 쪽 그림을 안전 지도 배경으로 저장하고, 총괄이 내려받는다. */
    @Test void publishSavesPdfPageAsSafetyMapBackground() throws Exception {
        UUID id=create("배경 공연");
        assertThat(send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie)).statusCode()).isEqualTo(200);
        UUID main=zone(id,"메인홀");
        task(id,group(id,"기본조",true),main,"base","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        UUID chiefMember=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,id,chief);
        UUID document=UUID.randomUUID();
        String key="intake/"+document;
        storage.put(key,landscapePdf(),"application/pdf");
        jdbc.update("""
                INSERT INTO intake_document (id, event_id, owner_account_id, original_name, mime, bytes, page_count, sha256,
                                             storage_key, created_at, expires_at)
                VALUES (?, ?, ?, '계획서.pdf', 'application/pdf', 1, 1, ?, ?, now(), now() + interval '7 days')""",
                document,id,chief,"0".repeat(64),key);
        draft(id,chiefMember,"""
                {"coordinateSystem":"normalized-image","width":1600,"height":1131,
                 "zonePolygons":[{"zoneId":"%s","points":[{"x":0.1,"y":0.1},{"x":0.5,"y":0.1},{"x":0.5,"y":0.4}]}],
                 "source":{"sourceKind":"AUTOMATIC_MEASUREMENT","sourceRef":"document:%s#page=1","observedAt":null}}"""
                .formatted(main,document));

        assertThat(publish(id,"2").statusCode()).isEqualTo(200);
        var row=jdbc.queryForMap("SELECT b.width, b.height, b.source_ref FROM venue_layout_background b JOIN venue_layout l"
                + " ON l.id=b.venue_layout_id WHERE b.event_id=?",id);
        assertThat(row.get("width")).isEqualTo(1600);
        assertThat(row.get("source_ref")).isEqualTo("document:"+document+"#page=1");
        var image=client.send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+id+"/safety-map/background")))
                .header("Cookie",chiefCookie).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(image.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(image.body()[1]).isEqualTo((byte)'P');
        assertThat(send(get("/api/v1/events/"+id+"/safety-map/background").header("Cookie",login(worker))).statusCode())
                .isIn(403,404);
    }

    @Test void publishWithoutPdfSourceHasNoBackground() throws Exception {
        UUID id=create("배경 없음");
        assertThat(send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie)).statusCode()).isEqualTo(200);
        UUID main=zone(id,"메인홀");
        task(id,group(id,"기본조",true),main,"base","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        UUID chiefMember=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,id,chief);
        // 구역 이름 기본 배치(문서 근거 없음)와 만료된 원본을 가리키는 근거는 배경 없이 발행된다.
        draft(id,chiefMember,"""
                {"coordinateSystem":"normalized-image","width":1,"height":1,
                 "zonePolygons":[{"zoneId":"%s","points":[{"x":0.1,"y":0.1},{"x":0.5,"y":0.1},{"x":0.5,"y":0.4}]}],
                 "source":{"sourceKind":"MANUAL_CONFIRMATION","sourceRef":"document:%s#page=1","observedAt":null}}"""
                .formatted(main,UUID.randomUUID()));
        assertThat(publish(id,"2").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_layout WHERE event_id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_layout_background WHERE event_id=?",Long.class,id)).isZero();
        var r=send(get("/api/v1/events/"+id+"/safety-map/background").header("Cookie",chiefCookie));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
    }

    static byte[] landscapePdf() throws Exception {
        try (var doc=new org.apache.pdfbox.pdmodel.PDDocument(); var out=new java.io.ByteArrayOutputStream()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(842,595));
            doc.addPage(page);
            try (var c=new org.apache.pdfbox.pdmodel.PDPageContentStream(doc,page)) {
                c.setNonStrokingColor(0.9f,0.2f,0.2f);
                c.addRect(500,300,120,80);
                c.fill();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test void publishWithoutUsableVenueDraftSavesNoSafetyMap() throws Exception {
        // 미확정 초안이 없음 / 평면도 null / 도형이 모두 임시 구역
        for (String layout:Arrays.asList(null,"null",
                "{\"coordinateSystem\":\"normalized-image\",\"width\":1,\"height\":1,\"zonePolygons\":[{\"zoneId\":\"tmp-z\","
                + "\"points\":[{\"x\":0,\"y\":0},{\"x\":1,\"y\":0},{\"x\":1,\"y\":1}]}],"
                + "\"source\":{\"sourceKind\":\"MANUAL_CONFIRMATION\",\"sourceRef\":\"r\",\"observedAt\":null}}")) {
            UUID id=create("평면도 없음");
            assertThat(send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie)).statusCode()).isEqualTo(200);
            plan(id);
            if (layout!=null) draft(id,jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",
                    UUID.class,id,chief),layout);
            assertThat(publish(id,"2").statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_layout WHERE event_id=?",Long.class,id)).isZero();
        }
    }

    // ---- 도우미 -------------------------------------------------------------------------------------------

    /** 원 위의 점 n개(0~1 정규화 좌표) */
    /**
     * #395 V9-D58·D59: 발행할 때 가장 최근 READY 계획 생성 결과의 카메라·장비로 칸을 만들고, 묶음 키로 찾은 연결 업무를 담당 업무로 둔다.
     * 같은 이름의 장비는 건너뛰고, 자동 관측 종류가 없는 장비에는 연결 업무를 두지 않는다.
     */
    @Test void publishCreatesCameraAndEquipmentSlotsFromPlanGeneration() throws Exception {
        UUID id=create("연결 칸 공연");
        assertThat(send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie)).statusCode()).isEqualTo(200);
        UUID main=zone(id,"메인홀");
        UUID safety=group(id,"안전",true), sound=group(id,"음향",true);
        task(id,safety,main,"base","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
        UUID cameraTask=task(id,safety,main,"camera-link","2026-11-18T00:30:00Z","2026-11-18T01:00:00Z");
        UUID equipmentTask=task(id,sound,main,"equipment-link","2026-11-18T00:30:00Z","2026-11-18T01:00:00Z");
        UUID chiefMember=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,id,chief);
        jdbc.update("""
                INSERT INTO equipment_asset (id,event_id,name,model,technical_state,state_evidence,component_asset_ids,ports,
                                             verified_compatibility,storage_zone_id,holder_member_id,revision)
                VALUES (?,?,'믹서',NULL,'UNKNOWN','{}'::jsonb,'[]'::jsonb,'[]'::jsonb,'[]'::jsonb,NULL,NULL,1)""",UUID.randomUUID(),id);
        planJob(id,"2026-11-12T01:00:00Z","""
                {"cameras":[{"name":"옛 카메라","zoneName":"메인홀"}],"assets":[]}""");
        UUID latest=planJob(id,"2026-11-12T02:00:00Z","""
                {"cameras":[{"name":"객석 카메라 1","zoneName":"메인홀"}],
                 "assets":[{"name":"QLXD4 수신기 1","kind":"무선 수신기","model":"QLXD4","location":"메인홀","autoObservationKind":"QLXD4"},
                           {"name":" 믹서 ","kind":"믹서","model":null,"location":null,"autoObservationKind":null},
                           {"name":"스피커","kind":"스피커","model":null,"location":null,"autoObservationKind":null}]}""");

        var published=publish(id,"2");
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        var cameras=jdbc.queryForList("""
                SELECT name, source_binding_ref, enabled, coverage::text AS coverage, connection_state, input_kind, link_task_id,
                       configured_by_member_id FROM safety_camera WHERE event_id=?""",id);
        assertThat(cameras).hasSize(1);
        var camera=cameras.get(0);
        assertThat(camera.get("name")).isEqualTo("객석 카메라 1");
        assertThat(camera.get("source_binding_ref")).isNull();
        assertThat(camera.get("enabled")).isEqualTo(true);
        assertThat(camera.get("coverage")).isEqualTo("[]");
        assertThat(camera.get("connection_state")).isEqualTo("UNLINKED");
        assertThat(camera.get("input_kind")).isNull();
        assertThat(camera.get("link_task_id")).isEqualTo(cameraTask);
        assertThat(camera.get("configured_by_member_id")).isEqualTo(chiefMember);

        var assets=jdbc.queryForList("""
                SELECT name, model, technical_state, state_evidence::text AS evidence, auto_observation_kind, link_task_id
                  FROM equipment_asset WHERE event_id=? ORDER BY name""",id);
        assertThat(assets).extracting(a->a.get("name")).containsExactly("QLXD4 수신기 1","믹서","스피커");
        var receiver=assets.get(0);
        assertThat(receiver.get("model")).isEqualTo("QLXD4");
        assertThat(receiver.get("technical_state")).isEqualTo("UNKNOWN");
        assertThat(receiver.get("auto_observation_kind")).isEqualTo("QLXD4");
        assertThat(receiver.get("link_task_id")).isEqualTo(equipmentTask);
        JsonNode evidence=json.readTree((String) receiver.get("evidence"));
        assertThat(evidence.get("sourceKind").asString()).isEqualTo("SIMULATION_ASSUMPTION");
        assertThat(evidence.get("sourceRef").asString()).isEqualTo("ai-job:"+latest);
        assertThat(evidence.get("confirmed").asBoolean()).isFalse();
        assertThat(assets.get(2).get("auto_observation_kind")).isNull();
        assertThat(assets.get(2).get("link_task_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_log WHERE event_id=? AND type='SAFETY_CAMERA_CREATED'",
                Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_log WHERE event_id=? AND type='EQUIPMENT_REGISTERED'",
                Long.class,id)).isEqualTo(2);
        // 발행은 Event revision을 한 번만 올린다
        assertThat(json.readTree(published.body()).get("revision").asString()).isEqualTo("3");
    }

    /** 미확정 초안의 출처 작업이 READY 계획 생성이면 가장 최근 결과보다 그 결과를 쓴다(#395 리뷰). */
    @Test void publishUsesTheUnconfirmedDraftSourceJob() throws Exception {
        UUID id=create("출처 공연");
        assertThat(send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie)).statusCode()).isEqualTo(200);
        plan(id);
        UUID chiefMember=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,id,chief);
        UUID applied=planJob(id,"2026-11-12T01:00:00Z","""
                {"cameras":[{"name":"적용한 카메라","zoneName":"기본구역"}],"assets":[]}""");
        planJob(id,"2026-11-12T02:00:00Z","""
                {"cameras":[{"name":"나중 카메라","zoneName":"기본구역"}],"assets":[]}""");
        jdbc.update("""
                INSERT INTO event_unconfirmed_draft (id,event_id,revision,source_job_id,payload,saved_by_member_id,saved_at)
                VALUES (?,?,1,?,?::jsonb,?,now())""",UUID.randomUUID(),id,applied,
                "{\"equipmentDraft\":{\"assets\":[],\"connections\":[],\"requirements\":[],\"reservations\":[],\"dependencies\":[]},"
                + "\"venueDraft\":{\"layout\":null,\"profiles\":[]}}",chiefMember);
        assertThat(publish(id,"2").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT name FROM safety_camera WHERE event_id=?",String.class,id)).containsExactly("적용한 카메라");
    }

    /** 계획 생성이 없거나 deviceDraft가 없으면 칸을 만들지 않는다. */
    @Test void publishWithoutDeviceDraftCreatesNoSlots() throws Exception {
        UUID id=create("칸 없는 공연");
        assertThat(send(patch("/api/v1/events/"+id,complete("1")).header("Cookie",chiefCookie)).statusCode()).isEqualTo(200);
        plan(id);
        planJob(id,"2026-11-12T02:00:00Z",null);
        assertThat(publish(id,"2").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_camera WHERE event_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_asset WHERE event_id=?",Long.class,id)).isZero();
    }

    /** 끝난 READY 계획 생성 작업. deviceDraft가 null이면 키를 넣지 않는다. */
    UUID planJob(UUID event,String finishedAt,String deviceDraft) {
        var id=UUID.randomUUID();
        String result="{\"status\":\"READY\",\"questions\":[],\"plan\":{}"
                +(deviceDraft==null?"":",\"deviceDraft\":"+deviceDraft)+"}";
        jdbc.update("""
                INSERT INTO ai_job (id,event_id,requested_by_member_id,kind,state,input_hash,context_revision,request_schema_version,
                                    result_schema_version,model_version,input,result,error,attempt,execution_meta,trigger_key,
                                    analysis_flow_id,auto_reanalysis_count,created_at,first_started_at,deadline_at,lease_until,finished_at)
                VALUES (?,?,NULL,'GENERATE_PLAN','SUCCEEDED',?,1,'4','5','fixture','{}'::jsonb,?::jsonb,NULL,1,'{}'::jsonb,NULL,
                        NULL,0,?::timestamptz,?::timestamptz,?::timestamptz,NULL,?::timestamptz)""",
                id,event,"a".repeat(64),result,finishedAt,finishedAt,finishedAt,finishedAt);
        return id;
    }

    static String manyPoints(int n) {
        var points=new ArrayList<String>();
        for (int i=0;i<n;i++) {
            double a=2*Math.PI*i/n;
            points.add(String.format(Locale.ROOT,"{\"x\":%.4f,\"y\":%.4f}",0.5+0.3*Math.cos(a),0.5+0.3*Math.sin(a)));
        }
        return "["+String.join(",",points)+"]";
    }

    void draft(UUID event,UUID member,String layout) {
        jdbc.update("""
                INSERT INTO event_unconfirmed_draft (id,event_id,revision,source_job_id,payload,saved_by_member_id,saved_at)
                VALUES (?,?,1,NULL,?::jsonb,?,now())""",UUID.randomUUID(),event,
                "{\"equipmentDraft\":{\"assets\":[],\"connections\":[],\"requirements\":[],\"reservations\":[],\"dependencies\":[]},"
                + "\"venueDraft\":{\"layout\":"+layout+",\"profiles\":[]}}",member);
    }

    /** 최소 계획: 구역 1·활성 그룹 1·업무 1 */
    void plan(UUID event) {
        task(event,group(event,"기본조",true),zone(event,"기본구역"),"base","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
    }
    UUID zone(UUID event,String name) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,?,NULL,'[]'::jsonb)",id,event,name);
        return id;
    }
    UUID group(UUID event,String name,boolean active) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,?,'',?)",id,event,name,active);
        return id;
    }
    UUID program(UUID event,UUID zone,String name,String start,String end) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO program (id,event_id,zone_id,name,start_at,end_at) VALUES (?,?,?,?,?::timestamptz,?::timestamptz)",
                id,event,zone,name,start,end);
        return id;
    }
    UUID task(UUID event,UUID group,UUID zone,String bundle,String start,String end) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?,?,?,?,NULL,?,NULL,NULL,'업무',?::timestamptz,?::timestamptz,'["준비"]'::jsonb,'[]'::jsonb,'WAITING')""",
                id,event,group,bundle,zone,start,end);
        return id;
    }
    HttpResponse<String> publish(UUID event,String revision) throws Exception {
        return send(post("/api/v1/events/"+event+"/publish","{\"expectedEventRevision\":\""+revision+"\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
    }
    /** 공개 거절의 details.fields를 "field:reason[:id]"로 */
    List<String> publishFields(UUID event,String revision) throws Exception {
        var r=publish(event,revision);
        error(r,422,"EVENT_INCOMPLETE");
        var out=new ArrayList<String>();
        for (JsonNode f:json.readTree(r.body()).get("details").get("fields"))
            out.add(f.get("field").asString()+":"+f.get("reason").asString()+(f.has("id")?":"+f.get("id").asString():""));
        return out;
    }

    static String complete(String revision) {
        return """
                {"date":"2026-11-18","prepStartAt":"2026-11-18T09:00:00+09:00","openAt":"2026-11-18T10:00:00+09:00",
                 "closeAt":"2026-11-18T20:00:00+09:00","teardownEndAt":"2026-11-18T21:00:00+09:00","locationName":"합성 시연장",
                 "address":"합성 장소 주소","totalCapacity":"10","expectedVisitors":"50","peakVisitors":null,
                 "desiredReserveCount":"1","expectedEventRevision":"%s"}""".formatted(revision);
    }
    UUID create(String name) throws Exception {
        var r=send(post("/api/v1/events","{\"name\":\""+name+"\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(r.body()).get("id").asString());
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
    HttpRequest.Builder patch(String path,String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json").header("Origin",WEB)
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Beans {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
        @Bean @Primary InviteTokens recordingInviteTokens() {
            return new InviteTokens() {
                @Override public void issue(UUID eventId) { ISSUED.add(eventId); }
                @Override public void activate(UUID eventId) { ACTIVATED.add(eventId); }
            };
        }
    }
}
