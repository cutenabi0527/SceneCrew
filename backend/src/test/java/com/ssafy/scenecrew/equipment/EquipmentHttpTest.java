package com.ssafy.scenecrew.equipment;

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
import static org.assertj.core.api.Assertions.*;

/**
 * 장비 현황 조회 EXT01·03·05, 등록 EXT02, 상태 기록 EXT04, 업무 장비 점검 EXT09의 실제 HTTP·제품 Security·PostgreSQL.
 * 응답 키는 계약(FE v9 schemas.json과 같은 모양)과 정확히 같은지 본다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(EquipmentHttpTest.Beans.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EquipmentHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final Set<String> ASSET_KEYS=Set.of("id","eventId","name","model","technicalState","stateEvidence","componentAssetIds",
            "ports","verifiedCompatibility","storageZoneId","holderMemberId","revision");
    static final Set<String> EVIDENCE_KEYS=Set.of("sourceKind","sourceRef","observedAt","recordedAt","confirmedByMemberId","confirmed");
    static final Set<String> RESERVATION_KEYS=Set.of("id","eventId","assetIds","programId","taskId","startAt","endAt","status",
            "sourceChangeSetId","revision");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"equipment-test"); r.add("scenecrew.auth.audience",()->"equipment-test");
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
                jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
                jdbc.update("DELETE FROM equipment_reservation_asset WHERE reservation_id IN (SELECT id FROM equipment_reservation WHERE event_id=?)",e);
                jdbc.update("DELETE FROM equipment_reservation WHERE event_id=?",e);
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

    // ---- EXT02 등록 · EXT01 목록 · EXT03 상세 ------------------------------------------------------------------

    @Test void registerThenListAndDetail() throws Exception {
        UUID event=create();
        UUID zone=zone(event,"음향 부스");
        String key=UUID.randomUUID().toString();
        String body=asset("1","무선 수신기 A","QLXD4",List.of(),zone.toString());
        var r=register(event,body,key,chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var receipt=json.readTree(r.body());
        assertThat(receipt.propertyNames()).containsExactlyInAnyOrder("id","revision","eventRevision");
        assertThat(receipt.get("revision").asString()).isEqualTo("1");
        assertThat(receipt.get("eventRevision").asString()).isEqualTo("2");
        UUID id=UUID.fromString(receipt.get("id").asString());

        // 같은 키·같은 내용은 최초 영수증, 같은 키·다른 내용은 409
        var again=register(event,body,key,chiefCookie);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(json.readTree(again.body())).isEqualTo(receipt);
        error(register(event,asset("1","무선 수신기 B",null,List.of(),null),key,chiefCookie),409,"IDEMPOTENCY_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_asset WHERE event_id=?",Integer.class,event)).isEqualTo(1);

        var page=json.readTree(get("/api/v1/events/"+event+"/equipment",chiefCookie).body());
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items","nextCursor");
        assertThat(page.get("nextCursor").isNull()).isTrue();
        var a=page.get("items").get(0);
        assertThat(a.propertyNames()).containsExactlyInAnyOrderElementsOf(ASSET_KEYS);
        assertThat(a.get("id").asString()).isEqualTo(id.toString());
        assertThat(a.get("name").asString()).isEqualTo("무선 수신기 A");
        assertThat(a.get("model").asString()).isEqualTo("QLXD4");
        // 미확인 장비는 UNKNOWN으로 시작한다(07-public-api).
        assertThat(a.get("technicalState").asString()).isEqualTo("UNKNOWN");
        assertThat(a.get("stateEvidence").propertyNames()).containsExactlyInAnyOrderElementsOf(EVIDENCE_KEYS);
        assertThat(a.get("stateEvidence").get("confirmed").asBoolean()).isTrue();
        assertThat(a.get("stateEvidence").get("confirmedByMemberId").asString()).isEqualTo(memberId(event,chief).toString());
        assertThat(a.get("ports").get(0).propertyNames()).containsExactlyInAnyOrder("key","kind");
        assertThat(a.get("verifiedCompatibility")).isEmpty();
        assertThat(a.get("storageZoneId").asString()).isEqualTo(zone.toString());
        assertThat(a.get("holderMemberId").isNull()).isTrue();
        assertThat(a.get("revision").asString()).isEqualTo("1");

        var detail=get("/api/v1/equipment/"+id,chiefCookie);
        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        var d=json.readTree(detail.body());
        assertThat(d.propertyNames()).containsExactlyInAnyOrder("asset","connections","requirements","reservations","eventRevision");
        assertThat(d.get("asset")).isEqualTo(a);
        assertThat(d.get("eventRevision").asString()).isEqualTo("2");
        assertThat(json.readTree(jdbc.queryForObject("SELECT changes::text FROM operation_log WHERE resource_id=?",String.class,id))
                .get("note").asString()).isEqualTo("리허설 전 확인");
    }

    @Test void accessAndStateRules() throws Exception {
        UUID event=create();
        UUID group=group(event,"장비 운영");
        member(event,worker,group);
        // 비참여 404, 로그인 없음 401, 팀원의 등록 403
        error(get("/api/v1/events/"+event+"/equipment",outsiderCookie),404,"NOT_FOUND");
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/equipment"))).GET()).statusCode()).isEqualTo(401);
        error(register(event,asset("1","믹서",null,List.of(),null),UUID.randomUUID().toString(),workerCookie),403,"FORBIDDEN");
        // 장비 조회가 허용되지 않은 그룹의 팀원은 403
        error(get("/api/v1/events/"+event+"/equipment",workerCookie),403,"FORBIDDEN");
        error(get("/api/v1/events/"+event+"/equipment-reservations",workerCookie),403,"FORBIDDEN");
        jdbc.update("UPDATE work_group SET equipment_view_allowed=true WHERE id=?",group);
        assertThat(get("/api/v1/events/"+event+"/equipment",workerCookie).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/events/"+event+"/equipment-reservations",workerCookie).statusCode()).isEqualTo(200);
        // 그룹을 옮기면 다음 요청부터 자격을 다시 본다.
        UUID other=group(event,"입장 안내");
        jdbc.update("UPDATE member SET work_group_id=? WHERE event_id=? AND account_id=?",other,event,worker);
        error(get("/api/v1/events/"+event+"/equipment",workerCookie),403,"FORBIDDEN");

        // revision 불일치 409(event), 끝난 행사 409
        var stale=register(event,asset("9","믹서",null,List.of(),null),UUID.randomUUID().toString(),chiefCookie);
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("event");
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        error(register(event,asset("1","믹서",null,List.of(),null),UUID.randomUUID().toString(),chiefCookie),409,"INVALID_STATE");
    }

    @Test void registerValidation() throws Exception {
        UUID event=create();
        UUID otherEvent=create();
        UUID foreign=registered(otherEvent,"다른 행사 장비");
        field(register(event,"{\"expectedEventRevision\":\"1\",\"name\":\"a\",\"componentAssetIds\":[],\"ports\":[],\"evidence\":"+evidence()+",\"extra\":1}",
                UUID.randomUUID().toString(),chiefCookie),"extra","UNKNOWN");
        field(register(event,asset("1","가".repeat(41),null,List.of(),null),UUID.randomUUID().toString(),chiefCookie),"name","LENGTH");
        field(register(event,"{\"expectedEventRevision\":\"1\",\"name\":\"a\",\"componentAssetIds\":[],\"ports\":[{\"key\":\"ch1\",\"kind\":\"XLR\"},{\"key\":\"ch1\",\"kind\":\"XLR\"}],\"evidence\":"+evidence()+"}",
                UUID.randomUUID().toString(),chiefCookie),"ports[1].key","DUPLICATE");
        field(register(event,"{\"expectedEventRevision\":\"1\",\"name\":\"a\",\"componentAssetIds\":[],\"ports\":[],\"evidence\":{\"sourceKind\":\"GUESS\",\"sourceRef\":\"x\",\"observedAt\":null}}",
                UUID.randomUUID().toString(),chiefCookie),"evidence.sourceKind","ENUM");
        field(register(event,asset("01","a",null,List.of(),null),UUID.randomUUID().toString(),chiefCookie),"expectedEventRevision","FORMAT");
        // 다른 행사 장비를 구성품으로 쓰면 422, 공백·대소문자만 다른 같은 이름은 409
        error(register(event,asset("1","세트",null,List.of(foreign.toString()),null),UUID.randomUUID().toString(),chiefCookie),422,"INVALID_REFERENCE");
        registered(event,"Main Mixer");
        var dup=register(event,asset("2","  main mixer ",null,List.of(),null),UUID.randomUUID().toString(),chiefCookie);
        error(dup,409,"INVALID_STATE");
        assertThat(json.readTree(dup.body()).get("details").get("reason").asString()).isEqualTo("DUPLICATE_NAME");
        // 같은 행사 구성품은 받는다.
        UUID part=registered(event,"마이크 1");
        var set=register(event,asset(revision(event),"무선 세트",null,List.of(part.toString()),null),UUID.randomUUID().toString(),chiefCookie);
        assertThat(set.statusCode()).as(set.body()).isEqualTo(201);
    }

    @Test void pagingByNameWithSignedCursor() throws Exception {
        UUID event=create();
        for (String n:List.of("다 장비","가 장비","나 장비")) registered(event,n);
        var first=json.readTree(get("/api/v1/events/"+event+"/equipment?limit=2",chiefCookie).body());
        assertThat(first.get("items")).hasSize(2);
        String cursor=first.get("nextCursor").asString();
        var second=json.readTree(get("/api/v1/events/"+event+"/equipment?limit=2&cursor="+cursor,chiefCookie).body());
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        var names=new ArrayList<String>();
        for (var p:List.of(first,second)) for (JsonNode a:p.get("items")) names.add(a.get("name").asString());
        assertThat(names).containsExactly("가 장비","나 장비","다 장비");
        field(get("/api/v1/events/"+event+"/equipment?cursor=abc.def",chiefCookie),"cursor","FORMAT");
        field(get("/api/v1/events/"+event+"/equipment?limit=101",chiefCookie),"limit","RANGE");
        field(get("/api/v1/events/"+event+"/equipment?sort=name",chiefCookie),"sort","UNKNOWN");
        // 다른 행사의 cursor는 받지 않는다.
        UUID other=create();
        field(get("/api/v1/events/"+other+"/equipment?cursor="+cursor,chiefCookie),"cursor","FORMAT");
    }

    // ---- EXT05 예약 시간표 · EXT04 상태 기록 · EXT09 점검 ---------------------------------------------------------

    @Test void reservationsAndStateRecord() throws Exception {
        UUID event=create();
        UUID group=group(event,"장비 운영");
        member(event,worker,group);
        UUID zone=zone(event,"무대");
        UUID asset=registered(event,"무선 마이크 1");
        UUID task=task(event,group,zone);
        UUID reservation=reservation(event,task,asset,"2026-11-18T09:00:00Z","2026-11-18T10:00:00Z");
        var page=json.readTree(get("/api/v1/events/"+event+"/equipment-reservations",chiefCookie).body());
        var v=page.get("items").get(0);
        assertThat(v.propertyNames()).containsExactlyInAnyOrderElementsOf(RESERVATION_KEYS);
        assertThat(v.get("id").asString()).isEqualTo(reservation.toString());
        assertThat(v.get("assetIds").get(0).asString()).isEqualTo(asset.toString());
        assertThat(v.get("startAt").asString()).isEqualTo("2026-11-18T09:00:00Z");
        assertThat(v.get("status").asString()).isEqualTo("PLANNED");
        var detail=json.readTree(get("/api/v1/equipment/"+asset,chiefCookie).body());
        assertThat(detail.get("reservations").get(0)).isEqualTo(v);

        String rev=revision(event);
        // DRAFT에서는 상태를 기록하지 않는다.
        error(observe(asset,state(rev,"1",null,"VERIFIED_USABLE"),chiefCookie),409,"INVALID_STATE");
        jdbc.update("UPDATE event SET state='LIVE' WHERE id=?",event);
        var stale=observe(asset,state(rev,"5",null,"VERIFIED_USABLE"),chiefCookie);
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("asset");
        var ok=observe(asset,state(rev,"1",null,"CHECK_REQUIRED"),chiefCookie);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(201);
        var receipt=json.readTree(ok.body());
        assertThat(receipt.get("id").asString()).isEqualTo(asset.toString());
        assertThat(receipt.get("revision").asString()).isEqualTo("2");
        assertThat(receipt.get("eventRevision").asString()).isEqualTo(Long.toString(Long.parseLong(rev)+1));
        assertThat(jdbc.queryForObject("SELECT technical_state FROM equipment_asset WHERE id=?",String.class,asset)).isEqualTo("CHECK_REQUIRED");

        // 팀원: 이 장비를 예약에 둔 업무를 지금 맡아야 한다.
        rev=revision(event);
        error(observe(asset,state(rev,"2",null,"VERIFIED_USABLE"),workerCookie),403,"FORBIDDEN");
        error(observe(asset,state(rev,"2",task.toString(),"VERIFIED_USABLE"),workerCookie),403,"FORBIDDEN");
        jdbc.update("UPDATE task SET assignee_id=? WHERE id=?",memberId(event,worker),task);
        var mine=observe(asset,state(rev,"2",task.toString(),"VERIFIED_USABLE"),workerCookie);
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(201);
        UUID otherAsset=registered(event,"예비 마이크");
        rev=revision(event);
        error(observe(otherAsset,state(rev,"1",task.toString(),"VERIFIED_USABLE"),workerCookie),403,"FORBIDDEN");
    }

    @Test void equipmentCheckKeepsRevisions() throws Exception {
        UUID event=create();
        UUID group=group(event,"장비 운영");
        member(event,worker,group);
        UUID asset=registered(event,"스피커 L");
        UUID task=task(event,group,zone(event,"무대"));
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        String rev=revision(event);
        // 담당자가 없으면 총괄도 기록하지 못한다.
        error(check(task,checkBody(rev,asset),chiefCookie),403,"FORBIDDEN");
        jdbc.update("UPDATE task SET assignee_id=? WHERE id=?",memberId(event,worker),task);
        var r=check(task,checkBody(rev,asset),workerCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var receipt=json.readTree(r.body());
        assertThat(receipt.get("revision").asString()).isEqualTo("1");
        assertThat(receipt.get("eventRevision").asString()).isEqualTo(rev);
        assertThat(revision(event)).isEqualTo(rev);
        var log=jdbc.queryForMap("SELECT type, resource_id, changes::text AS changes FROM operation_log WHERE id=?",
                UUID.fromString(receipt.get("id").asString()));
        assertThat(log.get("type")).isEqualTo("EQUIPMENT_CHECK");
        assertThat(log.get("resource_id")).isEqualTo(asset);
        assertThat(json.readTree((String)log.get("changes")).get("kind").asString()).isEqualTo("INSTALLATION");
        // 다른 행사 장비는 422
        UUID foreign=registered(create(),"다른 행사 장비");
        error(check(task,checkBody(rev,foreign),workerCookie),422,"INVALID_REFERENCE");
    }

    @Test void concurrentSameKeyStoresOnce() throws Exception {
        UUID event=create();
        String key=UUID.randomUUID().toString();
        String body=asset("1","동시 등록 장비",null,List.of(),null);
        var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<HttpResponse<String>>>();
            for (int i=0;i<4;i++) futures.add(pool.submit(()->register(event,body,key,chiefCookie)));
            var ids=new HashSet<String>();
            for (var f:futures) {
                var r=f.get(30,TimeUnit.SECONDS);
                assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
                ids.add(json.readTree(r.body()).get("id").asString());
            }
            assertThat(ids).hasSize(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_asset WHERE event_id=?",Integer.class,event)).isEqualTo(1);
    }

    // ---- 도우미 ----------------------------------------------------------------------------------------------

    static String evidence() {
        return "{\"sourceKind\":\"MANUAL_CONFIRMATION\",\"sourceRef\":\"chief:check\",\"observedAt\":\"2026-11-18T08:00:00+09:00\",\"note\":\"리허설 전 확인\"}";
    }
    String asset(String revision,String name,String model,List<String> components,String zone) {
        var o=json.createObjectNode().put("expectedEventRevision",revision).put("name",name);
        if (model!=null) o.put("model",model);
        var c=o.putArray("componentAssetIds"); components.forEach(c::add);
        o.putArray("ports").addObject().put("key","ch1").put("kind","XLR");
        if (zone!=null) o.put("storageZoneId",zone);
        o.set("evidence",json.readTree(evidence()));
        return o.toString();
    }
    String state(String eventRevision,String assetRevision,String task,String technicalState) {
        var o=json.createObjectNode().put("expectedEventRevision",eventRevision).put("expectedAssetRevision",assetRevision);
        if (task!=null) o.put("taskId",task);
        o.put("technicalState",technicalState);
        o.set("evidence",json.readTree(evidence()));
        return o.toString();
    }
    String checkBody(String revision,UUID asset) {
        return "{\"expectedEventRevision\":\""+revision+"\",\"assetId\":\""+asset+"\",\"kind\":\"INSTALLATION\",\"passed\":true,\"evidence\":"+evidence()+"}";
    }
    UUID registered(UUID event,String name) throws Exception {
        var r=register(event,asset(revision(event),name,null,List.of(),null),UUID.randomUUID().toString(),chiefCookie);
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
    UUID task(UUID event,UUID group,UUID zone) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?,?,?,'b1',NULL,?,NULL,NULL,'장비 설치','2026-11-18T09:00:00Z','2026-11-18T10:00:00Z','["설치"]'::jsonb,'[]'::jsonb,'WAITING')""",
                id,event,group,zone);
        return id;
    }
    UUID reservation(UUID event,UUID task,UUID asset,String start,String end) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipment_reservation (id,event_id,program_id,task_id,start_at,end_at,status,source_change_set_id,revision)
                VALUES (?,?,NULL,?,?::timestamptz,?::timestamptz,'PLANNED',NULL,1)""",id,event,task,start,end);
        jdbc.update("INSERT INTO equipment_reservation_asset (reservation_id,asset_id) VALUES (?,?)",id,asset);
        return id;
    }
    void member(UUID event,UUID account,UUID group) {
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,(SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?),'WORKER','팀원',true,false,true,1)""",
                UUID.randomUUID(),event,account,group,event);
    }
    UUID create() throws Exception {
        var r=send(post("/api/v1/events","{\"name\":\"장비 시험 공연\"}").header("Cookie",chiefCookie)
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
    HttpResponse<String> register(UUID event,String body,String key,String cookie) throws Exception {
        return send(post("/api/v1/events/"+event+"/equipment",body).header("Cookie",cookie).header("Idempotency-Key",key));
    }
    HttpResponse<String> observe(UUID asset,String body,String cookie) throws Exception {
        return send(post("/api/v1/equipment/"+asset+"/observations",body).header("Cookie",cookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
    }
    HttpResponse<String> check(UUID task,String body,String cookie) throws Exception {
        return send(post("/api/v1/tasks/"+task+"/equipment-checks",body).header("Cookie",cookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
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
