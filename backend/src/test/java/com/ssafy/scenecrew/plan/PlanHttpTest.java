package com.ssafy.scenecrew.plan;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/**
 * SC-API-027 계획 조회·028 초안 저장의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis.
 * 응답 키는 계약(PlanView·DraftPlanSaved, FE의 v9 schemas.json과 같은 모양)과 정확히 같은지 본다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PlanHttpTest.Beans.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PlanHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final Set<String> PLAN_KEYS=Set.of("eventId","eventRevision","viewerRole","workGroups","zones","programs","tasks","instructions");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"plan-test"); r.add("scenecrew.auth.audience",()->"plan-test");
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
    UUID chief, worker, outsider;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); worker=account(); outsider=account();
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
    }
    @AfterEach void cleanup() {
        for (var a:accounts) {
            for (var e:jdbc.queryForList("SELECT DISTINCT event_id FROM member WHERE account_id=?",UUID.class,a)) {
                jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
                jdbc.update("DELETE FROM operational_dependency WHERE event_id=?",e);
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

    // ---- 027·028 기본 흐름 ------------------------------------------------------------------------------------

    @Test void emptyPlanThenSaveAndReadBack() throws Exception {
        UUID event=create();
        var empty=get("/api/v1/events/"+event+"/plan",chiefCookie);
        assertThat(empty.statusCode()).as(empty.body()).isEqualTo(200);
        assertThat(empty.headers().firstValue("Cache-Control")).hasValue("no-store");
        var plan=json.readTree(empty.body());
        assertThat(plan.propertyNames()).containsExactlyInAnyOrderElementsOf(PLAN_KEYS);
        assertThat(plan.get("eventId").asString()).isEqualTo(event.toString());
        assertThat(plan.get("eventRevision").asString()).isEqualTo("1");
        assertThat(plan.get("viewerRole").asString()).isEqualTo("CHIEF");
        for (String a:List.of("workGroups","zones","programs","tasks","instructions")) assertThat(plan.get(a)).isEmpty();

        var saved=put(event,draft("1"));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(saved.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(saved.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("plan","idMappings");
        var p=body.get("plan");
        assertThat(p.propertyNames()).containsExactlyInAnyOrderElementsOf(PLAN_KEYS);
        assertThat(p.get("eventRevision").asString()).isEqualTo("2");
        // 새 tempId마다 서버 UUID 하나
        Map<String,String> ids=new HashMap<>();
        for (JsonNode m:body.get("idMappings")) {
            assertThat(m.propertyNames()).containsExactlyInAnyOrder("tempId","id");
            ids.put(m.get("tempId").asString(),m.get("id").asString());
        }
        assertThat(ids).containsOnlyKeys("tmp-sound","tmp-light","tmp-stage","tmp-booth","tmp-opening","tmp-mic","tmp-cable","tmp-light-check");

        // 그룹은 이름 순, 안내 포함
        assertThat(texts(p.get("workGroups"),"name")).containsExactly("음향","조명");
        var sound=p.get("workGroups").get(0);
        assertThat(sound.propertyNames()).containsExactlyInAnyOrder("id","name","instructions");
        assertThat(sound.get("id").asString()).isEqualTo(ids.get("tmp-sound"));
        assertThat(sound.get("instructions").asString()).isEqualTo("무선 마이크 예비 배터리를 챙깁니다.");
        // 구역 설명은 누락하면 null
        var zones=p.get("zones");
        assertThat(texts(zones,"name")).containsExactly("메인 무대","음향 부스");
        assertThat(zones.get(0).propertyNames()).containsExactlyInAnyOrder("id","name","description");
        assertThat(zones.get(0).get("description").asString()).isEqualTo("관객석에서 보이는 무대");
        assertThat(zones.get(1).get("description").isNull()).isTrue();
        var program=p.get("programs").get(0);
        assertThat(program.propertyNames()).containsExactlyInAnyOrder("id","name","zoneId","startAt","endAt");
        assertThat(program.get("zoneId").asString()).isEqualTo(ids.get("tmp-stage"));
        assertThat(program.get("startAt").asString()).isEqualTo("2026-11-18T10:00:00Z");
        // 업무는 시작 순 공통 요약만(담당자·절차·점검 없음)
        var tasks=p.get("tasks");
        assertThat(texts(tasks,"title")).containsExactly("오프닝 마이크 점검","무대 조명 점검","케이블 정리");
        assertThat(tasks.get(0).propertyNames()).containsExactlyInAnyOrder("id","workGroupId","title","startAt","endAt","zoneId","programId");
        assertThat(tasks.get(0).get("programId").asString()).isEqualTo(ids.get("tmp-opening"));
        assertThat(tasks.get(2).get("programId").isNull()).isTrue();
        var instruction=p.get("instructions").get(0);
        assertThat(instruction.propertyNames()).containsExactlyInAnyOrder("scope","workGroupId","body");
        assertThat(instruction.get("scope").asString()).isEqualTo("EVENT");
        assertThat(instruction.get("workGroupId").isNull()).isTrue();

        // 저장 직후 응답과 다시 조회한 계획이 같다.
        var read=json.readTree(get("/api/v1/events/"+event+"/plan",chiefCookie).body());
        assertThat(read).isEqualTo(p);

        // 저장 사실: 새 업무는 WAITING·담당 없음, 점검 항목은 미체크로 저장한다.
        UUID mic=UUID.fromString(ids.get("tmp-mic"));
        var row=jdbc.queryForMap("SELECT status, assignee_id, first_assigned_at, bundle_key, steps::text AS steps, checklist::text AS checklist FROM task WHERE id=?",mic);
        assertThat(row.get("status")).isEqualTo("WAITING");
        assertThat(row.get("assignee_id")).isNull();
        assertThat(row.get("first_assigned_at")).isNull();
        assertThat(row.get("bundle_key")).isEqualTo("sound-a");
        assertThat(json.readTree((String)row.get("steps"))).isEqualTo(json.readTree("[\"무선 마이크 전원 확인\",\"수신기 채널 확인\"]"));
        var check=json.readTree((String)row.get("checklist")).get(0);
        assertThat(check.get("key").asString()).isEqualTo("battery");
        assertThat(check.get("checked").asBoolean()).isFalse();
        assertThat(check.get("checkedAt").isNull()).isTrue();
        assertThat(check.get("checkedByMemberId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT instructions FROM event WHERE id=?",String.class,event)).isEqualTo("입장 전 무전기를 켭니다.");
        // 계획 저장은 확인한 입력(026)이 아니라 last_input_modified_at을 바꾸지 않는다.
        assertThat(jdbc.queryForObject("SELECT last_input_modified_at = (SELECT min(last_input_modified_at) FROM event WHERE id=?) FROM event WHERE id=?",
                Boolean.class,event,event)).isTrue();
    }

    @Test void unchangedSaveKeepsRevisionAndEditsUpdateInPlace() throws Exception {
        UUID event=create();
        var first=json.readTree(put(event,draft("1")).body());
        var current=fromPlan(event,first.get("plan"));
        // 같은 내용을 기존 id로 다시 보내면 바뀐 것이 없으므로 revision을 그대로 둔다.
        var same=put(event,current.toString());
        assertThat(same.statusCode()).as(same.body()).isEqualTo(200);
        assertThat(json.readTree(same.body()).get("plan").get("eventRevision").asString()).isEqualTo("2");
        assertThat(json.readTree(same.body()).get("idMappings")).isEmpty();

        // 첫 점검 항목을 수행한 상태를 만든다. 남는 key는 수행값을 유지해야 한다.
        UUID mic=taskId(event,"오프닝 마이크 점검");
        jdbc.update("""
                UPDATE task SET checklist='[{"key":"battery","text":"배터리 교체","required":true,"checked":true,
                  "checkedAt":"2026-11-18T09:00:00Z","checkedByMemberId":null}]'::jsonb WHERE id=?""",mic);
        // 두 그룹 이름 맞바꾸기 + 업무 제목·점검 문구 수정
        var edited=current.deepCopy();
        var groups=(ArrayNode)edited.get("workGroups");
        String a=groups.get(0).get("name").asString(), b=groups.get(1).get("name").asString();
        ((ObjectNode)groups.get(0)).put("name",b); ((ObjectNode)groups.get(1)).put("name",a);
        for (JsonNode t:edited.get("tasks")) if (t.get("id").asString().equals(mic.toString())) {
            ((ObjectNode)t).put("title","오프닝 마이크 최종 점검");
            ((ObjectNode)t.get("checklist").get(0)).put("text","배터리 새것으로 교체");
        }
        var r=put(event,edited.toString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json.readTree(r.body()).get("plan").get("eventRevision").asString()).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT name FROM work_group WHERE id=?",String.class,UUID.fromString(groups.get(0).get("id").asString()))).isEqualTo(b);
        assertThat(jdbc.queryForObject("SELECT name FROM work_group WHERE id=?",String.class,UUID.fromString(groups.get(1).get("id").asString()))).isEqualTo(a);
        var check=json.readTree(jdbc.queryForObject("SELECT checklist::text FROM task WHERE id=?",String.class,mic)).get(0);
        assertThat(check.get("text").asString()).isEqualTo("배터리 새것으로 교체");
        assertThat(check.get("checked").asBoolean()).isTrue();
        assertThat(check.get("checkedAt").asString()).isEqualTo("2026-11-18T09:00:00Z");
        assertThat(jdbc.queryForObject("SELECT title FROM task WHERE id=?",String.class,mic)).isEqualTo("오프닝 마이크 최종 점검");
    }

    @Test void omittedItemsAreDeletedAndNamesCanBeReused() throws Exception {
        UUID event=create();
        var first=json.readTree(put(event,draft("1")).body());
        var current=fromPlan(event,first.get("plan"));
        // 음향 부스 구역·케이블 업무·행사 안내를 빼고, 지운 구역 이름을 새 구역이 쓴다.
        var edited=current.deepCopy();
        var zones=(ArrayNode)edited.get("zones");
        UUID booth=null;
        for (int i=zones.size()-1;i>=0;i--) if (zones.get(i).get("name").asString().equals("음향 부스")) {
            booth=UUID.fromString(zones.get(i).get("id").asString()); zones.remove(i);
        }
        zones.addObject().put("tempId","tmp-new-booth").put("name","음향 부스");
        var tasks=(ArrayNode)edited.get("tasks");
        for (int i=tasks.size()-1;i>=0;i--) if (tasks.get(i).get("title").asString().equals("케이블 정리")) tasks.remove(i);
        ((ArrayNode)edited.get("instructions")).removeAll();
        var r=put(event,edited.toString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var p=json.readTree(r.body()).get("plan");
        assertThat(texts(p.get("tasks"),"title")).doesNotContain("케이블 정리");
        assertThat(p.get("instructions")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM zone WHERE id=?",Long.class,booth)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM zone WHERE event_id=? AND name='음향 부스'",Long.class,event)).isOne();
        assertThat(jdbc.queryForObject("SELECT instructions FROM event WHERE id=?",String.class,event)).isEmpty();
    }

    @Test void savedPlanPassesPublishChecks() throws Exception {
        UUID event=create();
        var patched=send(patch("/api/v1/events/"+event,complete("1")).header("Cookie",chiefCookie));
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        var saved=put(event,draft("2"));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        var published=send(post("/api/v1/events/"+event+"/publish","{\"expectedEventRevision\":\"3\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        // 공개 뒤에는 초안을 고치지 않는다(DRAFT 전용).
        error(put(event,draft("4")),409,"INVALID_STATE");
    }

    // ---- 권한·상태·revision ---------------------------------------------------------------------------------

    @Test void accessStateAndRevision() throws Exception {
        UUID event=create();
        UUID group=group(event,"음향",true,"");
        member(event,worker,group);
        // 팀원은 계획을 읽지만 초안은 저장하지 못한다. 비참여자는 행사 존재를 모른다(404).
        assertThat(get("/api/v1/events/"+event+"/plan",workerCookie).statusCode()).isEqualTo(200);
        error(put(event,draft("1"),workerCookie),403,"FORBIDDEN");
        error(get("/api/v1/events/"+event+"/plan",outsiderCookie),404,"NOT_FOUND");
        error(put(event,draft("1"),outsiderCookie),404,"NOT_FOUND");
        error(get("/api/v1/events/"+UUID.randomUUID()+"/plan",chiefCookie),404,"NOT_FOUND");
        // 로그인 없음 401, 형식 오류 400
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/plan"))).GET()).statusCode()).isEqualTo(401);
        error(get("/api/v1/events/not-a-uuid/plan",chiefCookie),400,"INVALID_REQUEST");
        error(get("/api/v1/events/"+event+"/plan?x=1",chiefCookie),400,"INVALID_REQUEST");
        // 허용하지 않은 Origin의 WEB 쓰기는 막는다.
        var foreign=send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/draft-plan"))).header("Content-Type","application/json")
                .header("Origin","https://evil.example.invalid").header("Cookie",chiefCookie).PUT(HttpRequest.BodyPublishers.ofString(draft("1"))));
        assertThat(foreign.statusCode()).isEqualTo(403);
        // 옛 revision은 409 VERSION_CONFLICT(target=event)이고 아무것도 바꾸지 않는다.
        var stale=put(event,draft("0"));
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("event");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(1L);
    }

    @Test void workerSeesCurrentCommonPlanOnly() throws Exception {
        UUID event=create();
        UUID sound=group(event,"음향",true,"음향 안내"), light=group(event,"조명",true,"조명 안내"), old=group(event,"옛 그룹",false,"옛 안내");
        UUID z=zone(event,"무대");
        UUID live=task(event,sound,z,"s1","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z","WAITING");
        task(event,sound,z,"s2","2026-11-18T03:00:00Z","2026-11-18T04:00:00Z","CANCELED");
        task(event,old,z,"o1","2026-11-18T03:00:00Z","2026-11-18T04:00:00Z","WAITING");
        member(event,worker,sound);
        jdbc.update("UPDATE event SET state='LIVE' WHERE id=?",event);
        var p=json.readTree(get("/api/v1/events/"+event+"/plan",workerCookie).body());
        assertThat(p.get("viewerRole").asString()).isEqualTo("WORKER");
        // 활성 그룹만, 본인 그룹 안내만 보인다.
        assertThat(texts(p.get("workGroups"),"name")).containsExactly("음향","조명");
        assertThat(p.get("workGroups").get(0).get("instructions").asString()).isEqualTo("음향 안내");
        assertThat(p.get("workGroups").get(1).get("instructions").isNull()).isTrue();
        // 취소된 업무와 비활성 그룹의 업무는 현재 계획이 아니다.
        assertThat(p.get("tasks")).hasSize(1);
        assertThat(p.get("tasks").get(0).get("id").asString()).isEqualTo(live.toString());
        // 총괄은 모든 그룹 안내를 본다.
        var c=json.readTree(get("/api/v1/events/"+event+"/plan",chiefCookie).body());
        assertThat(c.get("workGroups").get(1).get("instructions").asString()).isEqualTo("조명 안내");
        assertThat(light).isNotNull();
        // 종료된 행사도 읽을 수 있다.
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        assertThat(get("/api/v1/events/"+event+"/plan",workerCookie).statusCode()).isEqualTo(200);
    }

    // ---- 형식·의미 검사(400) ----------------------------------------------------------------------------------

    @Test void invalidRequestsChangeNothing() throws Exception {
        UUID event=create();
        UUID otherEvent=create();
        UUID foreignZone=zone(otherEvent,"남의 구역");
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("body.revision", draft("1").replace("\"expectedEventRevision\":\"1\"","\"expectedEventRevision\":1"));
        cases.put("unknown", draft("1").replace("\"programs\":","\"extra\":1,\"programs\":"));
        cases.put("both id and temp", draft("1").replace("{\"tempId\":\"tmp-stage\"","{\"id\":\""+UUID.randomUUID()+"\",\"tempId\":\"tmp-stage\""));
        cases.put("bad temp", draft("1").replace("tmp-stage","stage"));
        cases.put("duplicate temp", draft("1").replace("tmp-booth","tmp-stage"));
        cases.put("missing ref", draft("1").replace("\"zoneId\":\"tmp-booth\"","\"zoneId\":\"tmp-nowhere\""));
        cases.put("foreign id", draft("1").replace("\"zoneId\":\"tmp-booth\"","\"zoneId\":\""+foreignZone+"\""));
        cases.put("unknown existing id", draft("1").replace("{\"tempId\":\"tmp-booth\"","{\"id\":\""+foreignZone+"\""));
        cases.put("same name ignoring spaces", draft("1").replace("\"name\":\"음향 부스\"","\"name\":\" 메인무대 \""));
        cases.put("same name ignoring case", draft("1").replace("\"name\":\"조명\"","\"name\":\"SOUND\"").replace("\"name\":\"음향\"","\"name\":\"sound\""));
        cases.put("blank name", draft("1").replace("\"name\":\"조명\"","\"name\":\"   \""));
        cases.put("name too long", draft("1").replace("\"name\":\"조명\"","\"name\":\"조명조명조명조명조명조명조명조명조명조명😀\""));
        cases.put("time order", draft("1").replace("\"endAt\":\"2026-11-18T10:20:00Z\"","\"endAt\":\"2026-11-18T09:00:00Z\""));
        cases.put("no offset", draft("1").replace("2026-11-18T10:20:00Z","2026-11-18T10:20:00"));
        cases.put("program context", draft("1").replace("\"zoneId\":\"tmp-stage\",\"programId\":\"tmp-opening\"","\"zoneId\":\"tmp-booth\",\"programId\":\"tmp-opening\""));
        cases.put("bundle group", draft("1").replace("\"bundleKey\":\"light-a\"","\"bundleKey\":\"sound-a\""));
        cases.put("bundle overlap", draft("1").replace("\"bundleKey\":\"sound-b\"","\"bundleKey\":\"sound-a\"").replace("2026-11-18T11:00:00Z","2026-11-18T10:10:00Z"));
        cases.put("steps empty", draft("1").replace("\"steps\":[\"케이블 묶기\"]","\"steps\":[]"));
        cases.put("checklist duplicate", draft("1").replace("{\"key\":\"rig\",\"text\":\"고정 확인\",\"required\":false}",
                "{\"key\":\"rig\",\"text\":\"고정 확인\",\"required\":false},{\"key\":\"rig\",\"text\":\"다시\",\"required\":true}"));
        cases.put("two instructions", draft("1").replace("\"instructions\":[{","\"instructions\":[{\"scope\":\"EVENT\",\"workGroupId\":null,\"body\":\"둘째\"},{"));
        cases.put("group instruction on top", draft("1").replace("\"scope\":\"EVENT\",\"workGroupId\":null","\"scope\":\"WORK_GROUP\",\"workGroupId\":\"tmp-sound\""));
        cases.put("assignee not allowed", draft("1").replace("\"bundleKey\":\"light-a\"","\"bundleKey\":\"light-a\",\"assigneeId\":null"));
        cases.put("null array", draft("1").replace("\"programs\":[","\"programs\":null,\"x\":["));
        for (var c:cases.entrySet()) {
            var r=put(event,c.getValue());
            assertThat(r.statusCode()).as(c.getKey()+": "+r.body()).isEqualTo(400);
            var body=json.readTree(r.body());
            assertThat(body.get("code").asString()).as(c.getKey()).isEqualTo("INVALID_REQUEST");
            if (!c.getKey().equals("null array"))
                assertThat(body.get("details").get("fields").get(0).propertyNames()).as(c.getKey()).containsExactlyInAnyOrder("field","reason");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_group WHERE event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(1L);
        // 대표 경로는 원인 칸을 가리킨다.
        var dup=json.readTree(put(event,cases.get("same name ignoring spaces")).body()).get("details").get("fields").get(0);
        assertThat(dup.get("field").asString()).isEqualTo("zones[1].name");
        assertThat(dup.get("reason").asString()).isEqualTo("DUPLICATE");
        var ctx=json.readTree(put(event,cases.get("program context")).body()).get("details").get("fields").get(0);
        assertThat(ctx.get("reason").asString()).isEqualTo("PROGRAM_CONTEXT");
    }

    // ---- 바깥 참조(409) --------------------------------------------------------------------------------------

    @Test void deletingItemsUsedElsewhereIsRejected() throws Exception {
        UUID event=create();
        var first=json.readTree(put(event,draft("1")).body());
        var current=fromPlan(event,first.get("plan"));
        // 조명 그룹에 참여자가 있고, 오프닝 프로그램을 운영 관계가 가리킨다.
        UUID lightGroup=groupId(event,"조명");
        member(event,worker,lightGroup);
        UUID opening=jdbc.queryForObject("SELECT id FROM program WHERE event_id=?",UUID.class,event);
        UUID chiefMember=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,event,chief);
        UUID mic=taskId(event,"오프닝 마이크 점검");
        jdbc.update("""
                INSERT INTO operational_dependency (id,event_id,from_kind,from_id,to_kind,to_id,lag_sec,evidence,confirmed_by_member_id,confirmed_at)
                VALUES (?,?, 'PROGRAM', ?, 'TASK', ?, 0, '{}'::jsonb, ?, now())""",UUID.randomUUID(),event,opening,mic,chiefMember);

        var edited=current.deepCopy();
        removeWhere((ArrayNode)edited.get("workGroups"),"name","조명");
        removeWhere((ArrayNode)edited.get("tasks"),"title","무대 조명 점검");
        ((ArrayNode)edited.get("programs")).removeAll();
        for (JsonNode t:edited.get("tasks")) ((ObjectNode)t).putNull("programId");
        var r=put(event,edited.toString());
        error(r,409,"INVALID_STATE");
        var refs=new ArrayList<String>();
        for (JsonNode ref:json.readTree(r.body()).get("details").get("references"))
            refs.add(ref.get("resource").asString()+":"+ref.get("id").asString()+":"+ref.get("referencedBy").asString());
        assertThat(refs).containsExactlyInAnyOrder("programs:"+opening+":operational_dependency","workGroups:"+lightGroup+":member");
        // 거절되면 아무것도 바뀌지 않는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_group WHERE id=?",Long.class,lightGroup)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM program WHERE id=?",Long.class,opening)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE event_id=?",Long.class,event)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(2L);
    }

    // ---- 동시성 ------------------------------------------------------------------------------------------------

    @Test void concurrentSavesWithSameRevisionApplyOnce() throws Exception {
        UUID event=create();
        var pool=Executors.newFixedThreadPool(6);
        try {
            var futures=new ArrayList<Future<HttpResponse<String>>>();
            for (int i=0;i<6;i++) futures.add(pool.submit(()->put(event,draft("1"))));
            var codes=new ArrayList<Integer>();
            for (var f:futures) codes.add(f.get(60,TimeUnit.SECONDS).statusCode());
            assertThat(codes).containsOnly(200,409);
            assertThat(codes.stream().filter(c->c==200).count()).isOne();
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE event_id=?",Long.class,event)).isEqualTo(3L);
    }

    @Test void saveAndBasicInfoEditDoNotDeadlock() throws Exception {
        UUID event=create();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var save=pool.submit(()->put(event,draft("1")));
            var edit=pool.submit(()->send(patch("/api/v1/events/"+event,complete("1")).header("Cookie",chiefCookie)));
            int a=save.get(60,TimeUnit.SECONDS).statusCode(), b=edit.get(60,TimeUnit.SECONDS).statusCode();
            // 둘 다 같은 revision을 기대했으므로 하나만 반영되고 다른 하나는 409다. 교착(500·시간 초과)은 없다.
            assertThat(List.of(a,b)).containsExactlyInAnyOrder(200,409);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(2L);
    }

    // ---- 도우미 ------------------------------------------------------------------------------------------------

    /** 그룹2·구역2·프로그램1·업무3·행사 안내1을 모두 새로 만드는 초안 */
    static String draft(String revision) {
        return """
                {"expectedEventRevision":"%s",
                 "workGroups":[{"tempId":"tmp-sound","name":"음향","instructions":"무선 마이크 예비 배터리를 챙깁니다."},
                               {"tempId":"tmp-light","name":"조명","instructions":""}],
                 "zones":[{"tempId":"tmp-stage","name":"메인 무대","description":"관객석에서 보이는 무대"},
                          {"tempId":"tmp-booth","name":"음향 부스"}],
                 "programs":[{"tempId":"tmp-opening","name":"오프닝","zoneId":"tmp-stage","startAt":"2026-11-18T10:00:00Z","endAt":"2026-11-18T10:30:00Z"}],
                 "tasks":[{"tempId":"tmp-mic","workGroupId":"tmp-sound","bundleKey":"sound-a","zoneId":"tmp-stage","programId":"tmp-opening",
                           "title":"오프닝 마이크 점검","startAt":"2026-11-18T09:50:00Z","endAt":"2026-11-18T10:20:00Z",
                           "steps":["무선 마이크 전원 확인","수신기 채널 확인"],
                           "checklist":[{"key":"battery","text":"배터리 교체","required":true}]},
                          {"tempId":"tmp-light-check","workGroupId":"tmp-light","bundleKey":"light-a","zoneId":"tmp-stage","programId":null,
                           "title":"무대 조명 점검","startAt":"2026-11-18T09:55:00Z","endAt":"2026-11-18T10:05:00Z",
                           "steps":["조명 켜기"],"checklist":[{"key":"rig","text":"고정 확인","required":false}]},
                          {"tempId":"tmp-cable","workGroupId":"tmp-sound","bundleKey":"sound-b","zoneId":"tmp-booth","programId":null,
                           "title":"케이블 정리","startAt":"2026-11-18T11:00:00Z","endAt":"2026-11-18T11:30:00Z",
                           "steps":["케이블 묶기"],"checklist":[]}],
                 "instructions":[{"scope":"EVENT","workGroupId":null,"body":"입장 전 무전기를 켭니다."}]}""".formatted(revision);
    }

    /** 027 계획과 업무 상세(DB)로 FE와 같은 방식의 현재 초안(기존 id)을 만든다. */
    ObjectNode fromPlan(UUID event,JsonNode plan) {
        var d=json.createObjectNode();
        d.put("expectedEventRevision",plan.get("eventRevision").asString());
        var groups=d.putArray("workGroups");
        for (JsonNode g:plan.get("workGroups")) groups.addObject().put("id",g.get("id").asString()).put("name",g.get("name").asString())
                .put("instructions",g.get("instructions").asString());
        var zones=d.putArray("zones");
        for (JsonNode z:plan.get("zones")) {
            var o=zones.addObject().put("id",z.get("id").asString()).put("name",z.get("name").asString());
            o.set("description",z.get("description"));
        }
        var programs=d.putArray("programs");
        for (JsonNode p:plan.get("programs")) {
            var o=programs.addObject().put("id",p.get("id").asString()).put("name",p.get("name").asString());
            o.set("zoneId",p.get("zoneId")); o.set("startAt",p.get("startAt")); o.set("endAt",p.get("endAt"));
        }
        var tasks=d.putArray("tasks");
        for (JsonNode t:plan.get("tasks")) {
            var row=jdbc.queryForMap("SELECT bundle_key, steps::text AS steps, checklist::text AS checklist FROM task WHERE id=?",
                    UUID.fromString(t.get("id").asString()));
            var o=tasks.addObject().put("id",t.get("id").asString()).put("workGroupId",t.get("workGroupId").asString())
                    .put("bundleKey",(String)row.get("bundle_key")).put("zoneId",t.get("zoneId").asString());
            o.set("programId",t.get("programId"));
            o.put("title",t.get("title").asString()); o.set("startAt",t.get("startAt")); o.set("endAt",t.get("endAt"));
            o.set("steps",json.readTree((String)row.get("steps")));
            var checklist=o.putArray("checklist");
            for (JsonNode c:json.readTree((String)row.get("checklist")))
                checklist.addObject().put("key",c.get("key").asString()).put("text",c.get("text").asString()).put("required",c.get("required").asBoolean());
        }
        var instructions=d.putArray("instructions");
        for (JsonNode i:plan.get("instructions")) instructions.add(i);
        return d;
    }

    static void removeWhere(ArrayNode array,String field,String value) {
        for (int i=array.size()-1;i>=0;i--) if (array.get(i).get(field).asString().equals(value)) array.remove(i);
    }
    static List<String> texts(JsonNode array,String field) {
        var out=new ArrayList<String>(); for (JsonNode n:array) out.add(n.get(field).asString()); return out;
    }
    UUID taskId(UUID event,String title) { return jdbc.queryForObject("SELECT id FROM task WHERE event_id=? AND title=?",UUID.class,event,title); }
    UUID groupId(UUID event,String name) { return jdbc.queryForObject("SELECT id FROM work_group WHERE event_id=? AND name=?",UUID.class,event,name); }
    UUID zone(UUID event,String name) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,?,NULL,'[]'::jsonb)",id,event,name);
        return id;
    }
    UUID group(UUID event,String name,boolean active,String instructions) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,?,?,?)",id,event,name,instructions,active);
        return id;
    }
    UUID task(UUID event,UUID group,UUID zone,String bundle,String start,String end,String status) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?,?,?,?,NULL,?,NULL,NULL,'업무',?::timestamptz,?::timestamptz,'["준비"]'::jsonb,'[]'::jsonb,?)""",
                id,event,group,bundle,zone,start,end,status);
        return id;
    }
    void member(UUID event,UUID account,UUID group) {
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,(SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?),'WORKER','팀원',true,false,true,1)""",
                UUID.randomUUID(),event,account,group,event);
    }
    static String complete(String revision) {
        return """
                {"date":"2026-11-18","prepStartAt":"2026-11-18T09:00:00+09:00","openAt":"2026-11-18T10:00:00+09:00",
                 "closeAt":"2026-11-18T20:00:00+09:00","teardownEndAt":"2026-11-18T21:00:00+09:00","locationName":"합성 시연장",
                 "address":"합성 장소 주소","totalCapacity":"10","expectedVisitors":"50","peakVisitors":null,
                 "desiredReserveCount":"1","expectedEventRevision":"%s"}""".formatted(revision);
    }
    UUID create() throws Exception {
        var r=send(post("/api/v1/events","{\"name\":\"계획 시험 공연\"}").header("Cookie",chiefCookie)
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
    HttpRequest.Builder post(String path,String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json").header("Origin",WEB)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }
    HttpRequest.Builder patch(String path,String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json").header("Origin",WEB)
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> put(UUID event,String body) throws Exception { return put(event,body,chiefCookie); }
    HttpResponse<String> put(UUID event,String body,String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/draft-plan"))).header("Content-Type","application/json")
                .header("Origin",WEB).header("Cookie",cookie).PUT(HttpRequest.BodyPublishers.ofString(body)));
    }
    HttpResponse<String> get(String path,String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url(path))).header("Cookie",cookie).GET());
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
