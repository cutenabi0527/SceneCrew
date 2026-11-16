package com.ssafy.scenecrew.equipment;

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
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/**
 * 장비 관계 R1(연결·요구·운영 의존의 추가·교체·해제)과 equipment-context의 실제 HTTP·제품 Security·PostgreSQL.
 * 응답 키는 계약(FE v9 schemas.json과 같은 모양)과 정확히 같은지 본다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(EquipmentRelationHttpTest.Beans.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EquipmentRelationHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final Set<String> EVIDENCE_KEYS=Set.of("sourceKind","sourceRef","observedAt","recordedAt","confirmedByMemberId","confirmed");
    static final Set<String> CONNECTION_KEYS=Set.of("id","eventId","fromAssetId","fromPort","toAssetId","toPort","confirmedByMemberId",
            "confirmedAt","revision","active");
    static final Set<String> REQUIREMENT_KEYS=Set.of("id","eventId","programId","taskIds","requirements","readyBy","schedulePolicy",
            "timeEvidence","revision");
    static final Set<String> DEPENDENCY_KEYS=Set.of("id","eventId","from","to","lagSec","evidence","confirmedByMemberId","confirmedAt");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"relation-test"); r.add("scenecrew.auth.audience",()->"relation-test");
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
    UUID chief, worker, outsider, current;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); worker=account(); outsider=account();
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
        current=null;
    }
    @AfterEach void cleanup() {
        for (var a:accounts) {
            for (var e:jdbc.queryForList("SELECT DISTINCT event_id FROM member WHERE account_id=?",UUID.class,a)) {
                jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
                jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
                jdbc.update("DELETE FROM operational_dependency WHERE event_id=?",e);
                jdbc.update("DELETE FROM equipment_connection_port WHERE connection_id IN (SELECT id FROM equipment_connection WHERE event_id=?)",e);
                jdbc.update("DELETE FROM equipment_connection WHERE event_id=?",e);
                jdbc.update("DELETE FROM equipment_requirement WHERE event_id=?",e);
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

    // ---- 연결 ----------------------------------------------------------------------------------------------

    @Test void connectionLifecycle() throws Exception {
        UUID event=create();
        UUID mic=registered(event,"수신기"), mixer=registered(event,"믹서"), amp=registered(event,"앰프");
        var created=relation("POST","/connections",connection(revision(event),mic,"ch1",mixer,"ch1",null));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var receipt=json.readTree(created.body());
        assertThat(receipt.propertyNames()).containsExactlyInAnyOrder("id","revision","eventRevision");
        assertThat(receipt.get("revision").asString()).isEqualTo("1");
        assertThat(receipt.get("eventRevision").asString()).isEqualTo(revision(event));
        UUID id=UUID.fromString(receipt.get("id").asString());

        var ctx=context(event,chiefCookie);
        assertThat(ctx.propertyNames()).containsExactlyInAnyOrder("eventId","eventRevision","connections","requirements","dependencies");
        var c=ctx.get("connections").get(0);
        assertThat(c.propertyNames()).containsExactlyInAnyOrderElementsOf(CONNECTION_KEYS);
        assertThat(c.get("active").asBoolean()).isTrue();
        assertThat(c.get("confirmedByMemberId").asString()).isEqualTo(memberId(event,chief).toString());
        // 상세(EXT03)에도 같은 연결이 보인다.
        assertThat(json.readTree(get("/api/v1/equipment/"+mic,chiefCookie).body()).get("connections").get(0)).isEqualTo(c);

        // 같은 포트 재사용 409(쓰는 연결 id), 같은 장비끼리 422, 없는 포트 422
        var busy=relation("POST","/connections",connection(revision(event),amp,"ch1",mixer,"ch1",null));
        error(busy,409,"INVALID_STATE");
        assertThat(json.readTree(busy.body()).get("details").get("reason").asString()).isEqualTo("PORT_IN_USE");
        assertThat(json.readTree(busy.body()).get("details").get("connectionId").asString()).isEqualTo(id.toString());
        reference(relation("POST","/connections",connection(revision(event),mic,"ch1",mic,"ch1",null)),"toAssetId","SELF");
        reference(relation("POST","/connections",connection(revision(event),amp,"none",mixer,"ch1",null)),"fromPort","NOT_FOUND");

        // 교체는 모든 칸을 다시 받는다. 빠진 칸은 400, 옛 revision은 409(connection)
        field(relation("PATCH","/connections/"+id,connection(revision(event),mic,"ch1",amp,"ch1",null)),"expectedRevision","REQUIRED");
        var stale=relation("PATCH","/connections/"+id,connection(revision(event),mic,"ch1",amp,"ch1","7"));
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("connection");
        var replaced=relation("PATCH","/connections/"+id,connection(revision(event),mic,"ch1",amp,"ch1","1"));
        assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
        assertThat(json.readTree(replaced.body()).get("revision").asString()).isEqualTo("2");
        // 믹서 ch1은 비었으므로 다른 연결이 쓸 수 있다.
        var reuse=relation("POST","/connections",connection(revision(event),amp,"ch2",mixer,"ch1",null));
        assertThat(reuse.statusCode()).as(reuse.body()).isEqualTo(201);

        // 해제: query의 기대 revision, 같은 키 재요청은 같은 영수증
        String key=UUID.randomUUID().toString();
        String path="/connections/"+id+"?expectedEventRevision="+revision(event)+"&expectedRevision=2";
        var released=relation("DELETE",path,null,key);
        assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
        var rr=json.readTree(released.body());
        assertThat(rr.propertyNames()).containsExactlyInAnyOrder("id","eventRevision","released");
        assertThat(rr.get("released").asBoolean()).isTrue();
        assertThat(json.readTree(relation("DELETE",path,null,key).body())).isEqualTo(rr);
        assertThat(jdbc.queryForObject("SELECT active FROM equipment_connection WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_connection_port WHERE connection_id=?",Integer.class,id)).isZero();
        assertThat(texts(context(event,chiefCookie).get("connections"),"id")).doesNotContain(id.toString());
        var again=relation("DELETE","/connections/"+id+"?expectedEventRevision="+revision(event)+"&expectedRevision=3",null);
        error(again,409,"INVALID_STATE");
        assertThat(json.readTree(again.body()).get("details").get("reason").asString()).isEqualTo("RELEASED");
        field(relation("DELETE","/connections/"+id+"?expectedEventRevision="+revision(event),null),"expectedRevision","REQUIRED");
    }

    @Test void accessRules() throws Exception {
        UUID event=create();
        UUID group=group(event,"장비 운영");
        member(event,worker,group);
        jdbc.update("UPDATE work_group SET equipment_view_allowed=true WHERE id=?",group);
        UUID a=registered(event,"수신기"), b=registered(event,"믹서");
        // 관계 쓰기·문맥 조회는 총괄만(장비 조회 허용 그룹도 403), 비참여 404
        error(send(post("/api/v1/events/"+event+"/equipment-connections",connection(revision(event),a,"ch1",b,"ch1",null))
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString())),403,"FORBIDDEN");
        error(get("/api/v1/events/"+event+"/equipment-context",workerCookie),403,"FORBIDDEN");
        error(get("/api/v1/events/"+event+"/equipment-context",outsiderCookie),404,"NOT_FOUND");
        // 끝난 행사도 문맥은 읽지만 쓰지는 못한다.
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        assertThat(get("/api/v1/events/"+event+"/equipment-context",chiefCookie).statusCode()).isEqualTo(200);
        error(relation("POST","/connections",connection(revision(event),a,"ch1",b,"ch1",null)),409,"INVALID_STATE");
        // 없는 관계 id는 404
        UUID other=create();
        error(relation("DELETE","/connections/"+UUID.randomUUID()+"?expectedEventRevision="+revision(other)+"&expectedRevision=1",null,
                UUID.randomUUID().toString(),other),404,"NOT_FOUND");
    }

    // ---- 요구 ----------------------------------------------------------------------------------------------

    @Test void requirementLifecycleAndDraftPlanGuard() throws Exception {
        UUID event=create();
        UUID group=group(event,"장비 운영");
        UUID zone=zone(event,"무대");
        UUID task=task(event,group,zone);
        UUID mic=registered(event,"무선 마이크");
        var created=relation("POST","/requirements",requirement(revision(event),task,mic,"FIXED",null));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID id=UUID.fromString(json.readTree(created.body()).get("id").asString());
        var q=context(event,chiefCookie).get("requirements").get(0);
        assertThat(q.propertyNames()).containsExactlyInAnyOrderElementsOf(REQUIREMENT_KEYS);
        assertThat(q.get("taskIds").get(0).asString()).isEqualTo(task.toString());
        assertThat(q.get("requirements").get(0).get("quantity").asInt()).isEqualTo(1);
        // 확인자·기록 시각은 클라이언트 값(가짜 확인자)이 아니라 서버가 정한다.
        var evidence=q.get("timeEvidence");
        assertThat(evidence.propertyNames()).containsExactlyInAnyOrderElementsOf(EVIDENCE_KEYS);
        assertThat(evidence.get("confirmedByMemberId").asString()).isEqualTo(memberId(event,chief).toString());
        assertThat(evidence.get("recordedAt").asString()).isNotEqualTo("2000-01-01T00:00:00Z");
        assertThat(json.readTree(get("/api/v1/equipment/"+mic,chiefCookie).body()).get("requirements").get(0)).isEqualTo(q);

        // 028 초안 저장으로 이 요구가 쓰는 업무를 지우면 거절한다.
        var draft="{\"expectedEventRevision\":\""+revision(event)+"\",\"workGroups\":[],\"zones\":[],\"programs\":[],\"tasks\":[],\"instructions\":[]}";
        var guarded=send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/draft-plan"))).header("Content-Type","application/json")
                .header("Origin",WEB).header("Cookie",chiefCookie).PUT(HttpRequest.BodyPublishers.ofString(draft)));
        error(guarded,409,"INVALID_STATE");
        var refs=new ArrayList<String>();
        for (JsonNode ref:json.readTree(guarded.body()).get("details").get("references"))
            refs.add(ref.get("resource").asString()+":"+ref.get("referencedBy").asString());
        assertThat(refs).contains("tasks:equipment_requirement");

        // 형식: 대상 없음·변경 허용인데 구간 없음 400, 취소된 업무 422
        var none=(ObjectNode)json.readTree(requirement(revision(event),task,mic,"FIXED",null));
        none.putArray("taskIds");
        field(relation("POST","/requirements",none.toString()),"taskIds","TARGET_REQUIRED");
        field(relation("POST","/requirements",requirement(revision(event),task,mic,"CHANGE_ALLOWED",null)),"schedulePolicy.allowedStartAt","REQUIRED");
        UUID canceled=task(event,group,zone);
        jdbc.update("UPDATE task SET status='CANCELED' WHERE id=?",canceled);
        reference(relation("POST","/requirements",requirement(revision(event),canceled,mic,"FIXED",null)),"taskIds","NOT_FOUND");

        var replaced=relation("PATCH","/requirements/"+id,requirement(revision(event),task,mic,"CHANGE_ALLOWED","1"));
        assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
        assertThat(json.readTree(replaced.body()).get("revision").asString()).isEqualTo("2");
        var released=relation("DELETE","/requirements/"+id+"?expectedEventRevision="+revision(event)+"&expectedRevision=2",null);
        assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_requirement WHERE id=?",Integer.class,id)).isZero();
        var log=jdbc.queryForObject("SELECT changes::text FROM operation_log WHERE resource_id=? AND type='EQUIPMENT_REQUIREMENT_RELEASED'",String.class,id);
        assertThat(json.readTree(log).get("before").get("revision").asInt()).isEqualTo(2);
    }

    // ---- 운영 의존 ------------------------------------------------------------------------------------------

    @Test void dependencyLifecycleAndCycles() throws Exception {
        UUID event=create();
        UUID group=group(event,"장비 운영");
        UUID zone=zone(event,"무대");
        UUID program=program(event,zone);
        UUID setup=task(event,group,zone), check=task(event,group,zone);
        var created=relation("POST","/dependencies",dependency(revision(event),"TASK",setup,"TASK",check,"30"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var receipt=json.readTree(created.body());
        // 운영 의존은 revision이 없어 영수증 revision이 Event revision이다.
        assertThat(receipt.get("revision").asString()).isEqualTo(receipt.get("eventRevision").asString());
        UUID id=UUID.fromString(receipt.get("id").asString());
        var d=context(event,chiefCookie).get("dependencies").get(0);
        assertThat(d.propertyNames()).containsExactlyInAnyOrderElementsOf(DEPENDENCY_KEYS);
        assertThat(d.get("from").propertyNames()).containsExactlyInAnyOrder("kind","id");
        assertThat(d.get("lagSec").isNumber()).isTrue();
        assertThat(d.get("lagSec").asDouble()).isEqualTo(30.0);

        var next=relation("POST","/dependencies",dependency(revision(event),"TASK",check,"PROGRAM",program,"0"));
        assertThat(next.statusCode()).as(next.body()).isEqualTo(201);
        // program → setup을 더하면 setup → check → program → setup 순환
        reference(relation("POST","/dependencies",dependency(revision(event),"PROGRAM",program,"TASK",setup,"0")),"to","CYCLE");
        reference(relation("POST","/dependencies",dependency(revision(event),"TASK",setup,"TASK",setup,"0")),"to","SELF");
        var dup=relation("POST","/dependencies",dependency(revision(event),"TASK",setup,"TASK",check,"10"));
        error(dup,409,"INVALID_STATE");
        assertThat(json.readTree(dup.body()).get("details").get("reason").asString()).isEqualTo("DUPLICATE");
        field(relation("POST","/dependencies",dependency(revision(event),"TASK",setup,"TASK",check,"-1")),"lagSec","RANGE");
        reference(relation("POST","/dependencies",dependency(revision(event),"TASK",setup,"TASK",UUID.randomUUID(),"0")),"to","NOT_FOUND");

        // 교체는 자기 간선을 뺀 그래프로 순환을 본다.
        error(relation("PATCH","/dependencies/"+id,dependency("1","TASK",setup,"TASK",check,"5")),409,"VERSION_CONFLICT");
        // setup→check를 program→setup으로 바꾸면 check→program→setup이 되어 순환이 없다.
        var replaced=relation("PATCH","/dependencies/"+id,dependency(revision(event),"PROGRAM",program,"TASK",setup,"5"));
        assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
        field(relation("DELETE","/dependencies/"+id+"?expectedEventRevision="+revision(event)+"&expectedRevision=1",null),"expectedRevision","UNKNOWN");
        var released=relation("DELETE","/dependencies/"+id+"?expectedEventRevision="+revision(event),null);
        assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operational_dependency WHERE id=?",Integer.class,id)).isZero();
    }

    // ---- 도우미 ----------------------------------------------------------------------------------------------

    String connection(String revision,UUID from,String fromPort,UUID to,String toPort,String expected) {
        var o=json.createObjectNode().put("fromAssetId",from.toString()).put("fromPort",fromPort).put("toAssetId",to.toString())
                .put("toPort",toPort).put("expectedEventRevision",revision);
        o.set("confirmation",json.readTree(confirmation()));
        if (expected!=null) o.put("expectedRevision",expected);
        return o.toString();
    }
    static String confirmation() { return "{\"summary\":\"리허설에서 직접 확인\",\"sourceKind\":\"MANUAL_CONFIRMATION\",\"observedAt\":null}"; }
    /** 클라이언트가 보낸 확인자·기록 시각은 서버가 무시해야 한다. */
    static String fullEvidence() {
        return "{\"sourceKind\":\"MANUAL_CONFIRMATION\",\"sourceRef\":\"chief:check\",\"observedAt\":null,\"recordedAt\":\"2000-01-01T00:00:00Z\","
                +"\"confirmedByMemberId\":\""+UUID.randomUUID()+"\",\"confirmed\":true}";
    }
    String requirement(String revision,UUID task,UUID asset,String mode,String expected) {
        var o=json.createObjectNode().putNull("programId");
        o.putArray("taskIds").add(task.toString());
        var item=o.putArray("requirements").addObject();
        item.putArray("assetIds").add(asset.toString()); item.putArray("compatibleAssetIds"); item.put("quantity",1);
        item.putArray("componentAssetIds"); item.putArray("requiredPortKeys");
        o.put("readyBy","2026-11-18T08:30:00Z");
        var policy=o.putObject("schedulePolicy").put("mode",mode);
        if ("CHANGE_ALLOWED".equals(mode) && expected!=null) policy.put("allowedStartAt","2026-11-18T08:00:00Z").put("allowedEndAt","2026-11-18T09:00:00Z");
        else policy.putNull("allowedStartAt").putNull("allowedEndAt");
        policy.set("evidence",json.readTree(fullEvidence()));
        o.set("timeEvidence",json.readTree(fullEvidence()));
        o.put("expectedEventRevision",revision);
        o.set("confirmation",json.readTree(confirmation()));
        if (expected!=null) o.put("expectedRevision",expected);
        return o.toString();
    }
    String dependency(String revision,String fromKind,UUID from,String toKind,UUID to,String lag) {
        return "{\"from\":{\"kind\":\""+fromKind+"\",\"id\":\""+from+"\"},\"to\":{\"kind\":\""+toKind+"\",\"id\":\""+to+"\"},\"lagSec\":"+lag
                +",\"evidence\":"+fullEvidence()+",\"expectedEventRevision\":\""+revision+"\",\"confirmation\":"+confirmation()+"}";
    }
    HttpResponse<String> relation(String method,String path,String body) throws Exception { return relation(method,path,body,UUID.randomUUID().toString()); }
    HttpResponse<String> relation(String method,String path,String body,String key) throws Exception { return relation(method,path,body,key,current); }
    HttpResponse<String> relation(String method,String path,String body,String key,UUID event) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/equipment-"+path.substring(1)))).header("Origin",WEB)
                .header("Cookie",chiefCookie).header("Idempotency-Key",key);
        if (body==null) b.method(method,HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
        return send(b);
    }
    JsonNode context(UUID event,String cookie) throws Exception {
        var r=get("/api/v1/events/"+event+"/equipment-context",cookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
    void reference(HttpResponse<String> r,String field,String reason) {
        error(r,422,"INVALID_REFERENCE");
        var d=json.readTree(r.body()).get("details");
        assertThat(d.get("field").asString()).isEqualTo(field);
        assertThat(d.get("reason").asString()).isEqualTo(reason);
    }
    static List<String> texts(JsonNode array,String field) {
        var out=new ArrayList<String>(); for (JsonNode n:array) out.add(n.get(field).asString()); return out;
    }
    UUID registered(UUID event,String name) throws Exception {
        var body="{\"expectedEventRevision\":\""+revision(event)+"\",\"name\":\""+name+"\",\"componentAssetIds\":[],"
                +"\"ports\":[{\"key\":\"ch1\",\"kind\":\"XLR\"},{\"key\":\"ch2\",\"kind\":\"XLR\"}],"
                +"\"evidence\":{\"sourceKind\":\"MANUAL_CONFIRMATION\",\"sourceRef\":\"chief:check\",\"observedAt\":null}}";
        var r=send(post("/api/v1/events/"+event+"/equipment",body).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(r.body()).get("id").asString());
    }
    String revision(UUID event) { return Long.toString(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)); }
    UUID memberId(UUID event,UUID account) {
        return jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,event,account);
    }
    UUID zone(UUID event,String name) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,?,NULL,'[]'::jsonb)",id,event,name);
        return id;
    }
    UUID group(UUID event,String name) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,?,'',true)",id,event,name);
        return id;
    }
    UUID program(UUID event,UUID zone) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO program (id,event_id,zone_id,name,start_at,end_at) VALUES (?,?,?,'오프닝','2026-11-18T10:00:00Z','2026-11-18T10:30:00Z')",
                id,event,zone);
        return id;
    }
    UUID task(UUID event,UUID group,UUID zone) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?,?,?,'b1',NULL,?,NULL,NULL,'장비 설치','2026-11-18T09:00:00Z','2026-11-18T10:00:00Z','["설치"]'::jsonb,'[]'::jsonb,'WAITING')""",
                id,event,group,zone);
        return id;
    }
    void member(UUID event,UUID account,UUID group) {
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,(SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?),'WORKER','팀원',true,false,true,1)""",
                UUID.randomUUID(),event,account,group,event);
    }
    UUID create() throws Exception {
        var r=send(post("/api/v1/events","{\"name\":\"관계 시험 공연\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        UUID id=UUID.fromString(json.readTree(r.body()).get("id").asString());
        if (current==null) current=id;
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
    HttpResponse<String> get(String path,String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url(path))).header("Cookie",cookie).GET());
    }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);
    }
    void field(HttpResponse<String> r,String field,String reason) {
        error(r,400,"INVALID_REQUEST");
        var f=json.readTree(r.body()).get("details").get("fields").get(0);
        assertThat(f.get("field").asString()).isEqualTo(field);
        assertThat(f.get("reason").asString()).isEqualTo(reason);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Beans {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}
