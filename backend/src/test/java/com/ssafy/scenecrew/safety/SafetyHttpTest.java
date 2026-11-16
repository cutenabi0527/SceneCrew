package com.ssafy.scenecrew.safety;

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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/**
 * 안전 지도 EXT10·11, 관측 EXT13·14, 카메라 EXT21~24의 실제 HTTP·제품 Security·PostgreSQL.
 * 응답 키는 계약(FE v9 schemas.json과 같은 모양)과 정확히 같은지 본다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SafetyHttpTest.Beans.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SafetyHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final Set<String> MAP_KEYS=Set.of("eventId","eventRevision","layout","profiles","observations");
    static final Set<String> LAYOUT_KEYS=Set.of("id","eventId","revision","coordinateSystem","width","height","zonePolygons","source",
            "confirmedByMemberId","confirmedAt");
    static final Set<String> PROFILE_KEYS=Set.of("id","eventId","zoneId","layoutId","layoutRevision","revision","effectiveAreaM2",
            "areaEvidence","riskRule","minimumCrewRule","confirmedByMemberId","confirmedAt");
    static final Set<String> CAMERA_KEYS=Set.of("id","eventId","name","sourceBindingRef","enabled","revision","coverage",
            "configuredByMemberId","configuredAt","connectionState","streamSessionId","lastSequence","lastCapturedAt","lastReceivedAt",
            "lastProcessedAt","latestObservationId","freshness","inputKind","linkTaskId","linkMemberId","linkedAt");
    static final Set<String> COVERAGE_KEYS=Set.of("zoneId","layoutId","layoutRevision","profileRevision","scope","roi","effectiveAreaM2",
            "calibrationEvidence");
    static final Set<String> OBSERVATION_KEYS=Set.of("id","eventId","cameraId","cameraRevision","streamSessionId","sequence","capturedAt",
            "captureTimeBasis","receivedAt","inputHash","coverage","state","freshness","estimates","modelVersion","processedAt",
            "metadataExpiresAt");
    static final Set<String> EVIDENCE_KEYS=Set.of("sourceKind","sourceRef","observedAt","recordedAt","confirmedByMemberId","confirmed");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"safety-test"); r.add("scenecrew.auth.audience",()->"safety-test");
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
                jdbc.update("UPDATE safety_camera SET latest_observation_id=NULL WHERE event_id=?",e);
                jdbc.update("DELETE FROM safety_observation WHERE event_id=?",e);
                jdbc.update("DELETE FROM safety_camera WHERE event_id=?",e);
                jdbc.update("DELETE FROM safety_zone_profile WHERE event_id=?",e);
                jdbc.update("DELETE FROM venue_layout WHERE event_id=?",e);
                jdbc.update("DELETE FROM task WHERE event_id=?",e);
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

    // ---- EXT10·11 지도 · EXT21~24 카메라 ---------------------------------------------------------------------

    @Test void mapAndCameraLifecycle() throws Exception {
        UUID event=recruiting();
        UUID stage=zone(event,"무대"), lobby=zone(event,"로비");
        UUID group=group(event,"안전 관리");
        var empty=readMap(event);
        assertThat(empty.propertyNames()).containsExactlyInAnyOrderElementsOf(MAP_KEYS);
        assertThat(empty.get("layout").isNull()).isTrue();
        assertThat(empty.get("profiles")).isEmpty();
        assertThat(empty.get("observations")).isEmpty();

        String key=UUID.randomUUID().toString();
        String body=mapBody(event,null,square(0.1),stage,lobby,group,null,null);
        var saved=save(event,body,key);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        var receipt=json.readTree(saved.body());
        assertThat(receipt.propertyNames()).containsExactlyInAnyOrder("id","revision","eventRevision");
        assertThat(receipt.get("revision").asString()).isEqualTo("1");
        // 같은 키 재요청은 첫 영수증, 다른 내용은 409
        assertThat(json.readTree(save(event,body,key).body())).isEqualTo(receipt);
        error(save(event,mapBody(event,null,square(0.2),stage,lobby,group,null,null),key),409,"IDEMPOTENCY_MISMATCH");

        var map=readMap(event);
        var layout=map.get("layout");
        assertThat(layout.propertyNames()).containsExactlyInAnyOrderElementsOf(LAYOUT_KEYS);
        assertThat(layout.get("coordinateSystem").asString()).isEqualTo("normalized-image");
        assertThat(layout.get("source").propertyNames()).containsExactlyInAnyOrderElementsOf(EVIDENCE_KEYS);
        assertThat(layout.get("confirmedByMemberId").asString()).isEqualTo(memberId(event,chief).toString());
        assertThat(map.get("profiles")).hasSize(2);
        var stageProfile=profileOf(map,stage);
        assertThat(stageProfile.propertyNames()).containsExactlyInAnyOrderElementsOf(PROFILE_KEYS);
        assertThat(stageProfile.get("effectiveAreaM2").asDouble()).isEqualTo(120.5);
        assertThat(stageProfile.get("riskRule").get("evidence").propertyNames()).containsExactlyInAnyOrderElementsOf(EVIDENCE_KEYS);
        assertThat(stageProfile.get("minimumCrewRule").get("minimumCount").asInt()).isEqualTo(2);
        // 미확인 면적·기준은 null 그대로(0·정상으로 채우지 않음)
        var lobbyProfile=profileOf(map,lobby);
        assertThat(lobbyProfile.get("effectiveAreaM2").isNull()).isTrue();
        assertThat(lobbyProfile.get("riskRule").isNull()).isTrue();

        // 카메라 등록: 지금 평면도·프로필 revision에 묶인다.
        var created=camera(event,cameraBody(revision(event),"객석 카메라 1","cam-01",true,coverage(map,stage)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID cam=UUID.fromString(json.readTree(created.body()).get("id").asString());
        var c=json.readTree(get("/api/v1/safety-cameras/"+cam,chiefCookie).body());
        assertThat(c.propertyNames()).containsExactlyInAnyOrderElementsOf(CAMERA_KEYS);
        assertThat(c.get("coverage").get(0).propertyNames()).containsExactlyInAnyOrderElementsOf(COVERAGE_KEYS);
        assertThat(c.get("connectionState").asString()).isEqualTo("CONNECTING");
        assertThat(c.get("freshness").asString()).isEqualTo("UNKNOWN");
        assertThat(c.get("lastSequence").isNull()).isTrue();
        var page=json.readTree(get("/api/v1/events/"+event+"/safety-cameras",chiefCookie).body());
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items","nextCursor");
        assertThat(page.get("items").get(0)).isEqualTo(c);

        // 같은 내용으로 다시 저장하면(근거도 같은 출처) 평면도·프로필 revision이 그대로고 카메라도 유효하다.
        map=readMap(event);
        var same=save(event,mapBody(event,"1",square(0.1),stage,lobby,group,map,null),UUID.randomUUID().toString());
        assertThat(same.statusCode()).as(same.body()).isEqualTo(200);
        assertThat(json.readTree(same.body()).get("revision").asString()).isEqualTo("1");
        assertThat(profileOf(readMap(event),stage).get("revision").asString()).isEqualTo("1");
        assertThat(freshness(cam)).isEqualTo("UNKNOWN");

        // 도형을 바꾸면 평면도 revision이 오르고, 옛 revision을 가리키는 카메라는 재보정 전까지 INVALID_MAPPING
        map=readMap(event);
        var moved=save(event,mapBody(event,"1",square(0.3),stage,lobby,group,map,null),UUID.randomUUID().toString());
        assertThat(json.readTree(moved.body()).get("revision").asString()).isEqualTo("2");
        assertThat(freshness(cam)).isEqualTo("INVALID_MAPPING");
        map=readMap(event);
        assertThat(profileOf(map,stage).get("layoutRevision").asString()).isEqualTo("2");

        // 재보정: 새 coverage로 수정하면 UNKNOWN
        var recal=patch(cam,"{\"expectedCameraRevision\":\"1\",\"coverage\":"+coverage(map,stage)+"}");
        assertThat(recal.statusCode()).as(recal.body()).isEqualTo(200);
        assertThat(json.readTree(recal.body()).get("revision").asString()).isEqualTo("2");
        assertThat(freshness(cam)).isEqualTo("UNKNOWN");
        // 연결 참조만 바꾸면 다른 입력이므로 INVALID_MAPPING, 연결 상태는 CONNECTING. 옛 입력의 세션·번호·촬영 시각은 비운다
        jdbc.update("UPDATE safety_camera SET stream_session_id=gen_random_uuid(),last_sequence=7,last_captured_at=now() WHERE id=?",cam);
        patch(cam,"{\"expectedCameraRevision\":\"2\",\"sourceBindingRef\":\"cam-02\"}");
        assertThat(freshness(cam)).isEqualTo("INVALID_MAPPING");
        assertThat(jdbc.queryForMap("SELECT stream_session_id,last_sequence,last_captured_at FROM safety_camera WHERE id=?",cam).values())
                .containsOnlyNulls();
        // 비활성화는 DISABLED
        patch(cam,"{\"expectedCameraRevision\":\"3\",\"enabled\":false}");
        assertThat(jdbc.queryForObject("SELECT connection_state FROM safety_camera WHERE id=?",String.class,cam)).isEqualTo("DISABLED");
        field(patch(cam,"{\"expectedCameraRevision\":\"4\"}"),"body","NO_CHANGE");
        field(patch(cam,"{\"expectedCameraRevision\":\"4\",\"name\":null}"),"name","TYPE");
        var stale=patch(cam,"{\"expectedCameraRevision\":\"1\",\"enabled\":true}");
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("camera");

        // 프로필을 요청에서 빼면 지우고, 그 구역을 비추는 카메라는 INVALID_MAPPING
        map=readMap(event);
        patch(cam,"{\"expectedCameraRevision\":\"4\",\"coverage\":"+coverage(map,stage)+"}");
        assertThat(freshness(cam)).isEqualTo("UNKNOWN");
        var dropped=save(event,mapBody(event,"2",square(0.3),stage,lobby,group,map,stage),UUID.randomUUID().toString());
        assertThat(dropped.statusCode()).as(dropped.body()).isEqualTo(200);
        assertThat(texts(readMap(event).get("profiles"),"zoneId")).containsExactly(lobby.toString());
        assertThat(freshness(cam)).isEqualTo("INVALID_MAPPING");
    }

    @Test void validationAndAccess() throws Exception {
        UUID event=create();
        UUID stage=zone(event,"무대"), lobby=zone(event,"로비");
        UUID group=group(event,"안전 관리");
        member(event,worker,group);
        // DRAFT는 쓰기 409, 팀원 403, 비참여 404
        error(save(event,mapBody(event,null,square(0.1),stage,lobby,group,null,null),UUID.randomUUID().toString()),409,"INVALID_STATE");
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        error(get("/api/v1/events/"+event+"/safety-map",workerCookie),403,"FORBIDDEN");
        error(get("/api/v1/events/"+event+"/safety-cameras",workerCookie),403,"FORBIDDEN");
        error(get("/api/v1/events/"+event+"/safety-observations",outsiderCookie),404,"NOT_FOUND");
        // 평면도가 없으면 카메라를 등록하지 못한다.
        var noLayout=camera(event,cameraBody(revision(event),"카메라","cam-01",true,"[{\"zoneId\":\""+stage+"\",\"layoutId\":\""+UUID.randomUUID()
                +"\",\"layoutRevision\":\"1\",\"profileRevision\":\"1\",\"scope\":\"FULL\",\"roi\":"+square(0.1)+",\"effectiveAreaM2\":null,"
                +"\"calibrationEvidence\":"+evidence()+"}]"));
        error(noLayout,409,"INVALID_STATE");
        assertThat(json.readTree(noLayout.body()).get("details").get("reason").asString()).isEqualTo("LAYOUT_REQUIRED");

        // 형식: 도형 없는 프로필·근거 없는 면적·좌표 범위·점 개수
        var noPolygon=(ObjectNode)json.readTree(mapBody(event,null,square(0.1),stage,lobby,group,null,null));
        ((ArrayNode)noPolygon.get("layout").get("zonePolygons")).remove(1);
        field(save(event,noPolygon.toString(),UUID.randomUUID().toString()),"profiles[1].zoneId","NO_POLYGON");
        var noEvidence=(ObjectNode)json.readTree(mapBody(event,null,square(0.1),stage,lobby,group,null,null));
        ((ObjectNode)noEvidence.get("profiles").get(0)).putNull("areaEvidence");
        field(save(event,noEvidence.toString(),UUID.randomUUID().toString()),"profiles[0].areaEvidence","AREA_EVIDENCE");
        field(save(event,mapBody(event,null,"[{\"x\":0,\"y\":0},{\"x\":1.5,\"y\":0},{\"x\":0,\"y\":1}]",stage,lobby,group,null,null),
                UUID.randomUUID().toString()),"layout.zonePolygons[0].points[1].x","RANGE");
        field(save(event,mapBody(event,null,"[{\"x\":0,\"y\":0},{\"x\":1,\"y\":0}]",stage,lobby,group,null,null),
                UUID.randomUUID().toString()),"layout.zonePolygons[0].points","LIMIT");
        // 다른 행사 구역 422, 평면도 revision 불일치 409(layout)
        UUID foreign=zone(create(),"남의 구역");
        error(save(event,mapBody(event,null,square(0.1),foreign,lobby,group,null,null),UUID.randomUUID().toString()),422,"INVALID_REFERENCE");
        var staleLayout=save(event,mapBody(event,"3",square(0.1),stage,lobby,group,null,null),UUID.randomUUID().toString());
        error(staleLayout,409,"VERSION_CONFLICT");
        assertThat(json.readTree(staleLayout.body()).get("details").get("target").asString()).isEqualTo("layout");

        assertThat(save(event,mapBody(event,null,square(0.1),stage,lobby,group,null,null),UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
        var map=readMap(event);
        // 있는 프로필을 새 프로필(null)로 보내면 409(profile)
        var staleProfile=save(event,mapBody(event,"1",square(0.1),stage,lobby,group,null,null),UUID.randomUUID().toString());
        error(staleProfile,409,"VERSION_CONFLICT");
        assertThat(json.readTree(staleProfile.body()).get("details").get("target").asString()).isEqualTo("profile");
        // 연결 참조는 주소·계정 모양을 받지 않는다.
        field(camera(event,cameraBody(revision(event),"카메라","rtsp://user@10.0.0.5/live",true,coverage(map,stage))),"sourceBindingRef","FORMAT");
        // 옛 평면도 revision을 가리키는 coverage는 409(layout)
        var oldCoverage=coverage(map,stage).replace("\"layoutRevision\":\"1\"","\"layoutRevision\":\"9\"");
        error(camera(event,cameraBody(revision(event),"카메라","cam-01",true,oldCoverage)),409,"VERSION_CONFLICT");
        // 같은 이름(공백·대소문자만 다름)은 409
        assertThat(camera(event,cameraBody(revision(event),"Seat Cam","cam-01",true,coverage(map,stage))).statusCode()).isEqualTo(201);
        var dup=camera(event,cameraBody(revision(event)," seat cam ","cam-02",true,coverage(map,stage)));
        error(dup,409,"INVALID_STATE");
        assertThat(json.readTree(dup.body()).get("details").get("reason").asString()).isEqualTo("DUPLICATE_NAME");
    }

    // ---- EXT13·14 관측 ---------------------------------------------------------------------------------------

    @Test void observationsReadBack() throws Exception {
        UUID event=recruiting();
        UUID stage=zone(event,"무대"), lobby=zone(event,"로비");
        UUID group=group(event,"안전 관리");
        save(event,mapBody(event,null,square(0.1),stage,lobby,group,null,null),UUID.randomUUID().toString());
        var map=readMap(event);
        var created=camera(event,cameraBody(revision(event),"객석 카메라","cam-01",true,coverage(map,stage)));
        UUID cam=UUID.fromString(json.readTree(created.body()).get("id").asString());
        UUID older=observation(event,cam,1,"2026-11-18T10:00:00Z");
        UUID newer=observation(event,cam,2,"2026-11-18T10:00:05Z");
        jdbc.update("UPDATE safety_camera SET latest_observation_id=? WHERE id=?",newer,cam);

        var first=json.readTree(get("/api/v1/events/"+event+"/safety-observations?limit=1",chiefCookie).body());
        var o=first.get("items").get(0);
        assertThat(o.propertyNames()).containsExactlyInAnyOrderElementsOf(OBSERVATION_KEYS);
        assertThat(o.get("id").asString()).isEqualTo(newer.toString());
        assertThat(o.get("sequence").asString()).isEqualTo("2");
        assertThat(o.get("estimates").get(0).get("densityPerM2").isNull()).isTrue();
        var second=json.readTree(get("/api/v1/events/"+event+"/safety-observations?limit=1&cursor="+first.get("nextCursor").asString(),
                chiefCookie).body());
        assertThat(second.get("items").get(0).get("id").asString()).isEqualTo(older.toString());
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(json.readTree(get("/api/v1/safety-observations/"+older,chiefCookie).body()).get("id").asString()).isEqualTo(older.toString());
        // 지도에는 카메라별 최신 관측만
        var observations=readMap(event).get("observations");
        assertThat(texts(observations,"id")).containsExactly(newer.toString());
        assertThat(observations.get(0)).isEqualTo(o);
    }

    // ---- 도우미 ----------------------------------------------------------------------------------------------

    static String evidence() { return "{\"sourceKind\":\"MANUAL_CONFIRMATION\",\"sourceRef\":\"chief:check\",\"observedAt\":null}"; }
    static String square(double d) {
        return "[{\"x\":"+d+",\"y\":"+d+"},{\"x\":0.9,\"y\":"+d+"},{\"x\":0.9,\"y\":0.9},{\"x\":"+d+",\"y\":0.9}]";
    }
    /**
     * stage에는 면적·위험 기준·최소 인원, lobby에는 미확인(null)을 둔다. previous가 있으면 그 revision과 근거 출처를 그대로 보낸다
     * (FE가 바꾸지 않은 값을 돌려보내는 방식). drop 구역의 프로필은 빼고 보낸다.
     */
    String mapBody(UUID event,String layoutRevision,String stagePoints,UUID stage,UUID lobby,UUID group,JsonNode previous,UUID drop) {
        String stageRev=previous==null?null:revisionOf(previous,stage), lobbyRev=previous==null?null:revisionOf(previous,lobby);
        String stageProfile="{\"zoneId\":\""+stage+"\",\"expectedProfileRevision\":"+q(stageRev)+",\"effectiveAreaM2\":120.5,\"areaEvidence\":"+evidence()
                +",\"riskRule\":{\"version\":\"v1\",\"unit\":\"명/㎡\",\"condition\":\"2명/㎡ 초과 시 주의\",\"evidence\":"+evidence()+"},"
                +"\"minimumCrewRule\":{\"workGroupId\":\""+group+"\",\"minimumCount\":2,\"startAt\":\"2026-11-18T09:00:00Z\",\"endAt\":\"2026-11-18T12:00:00Z\",\"evidence\":"+evidence()+"}}";
        String lobbyProfile="{\"zoneId\":\""+lobby+"\",\"expectedProfileRevision\":"+q(lobbyRev)+",\"effectiveAreaM2\":null,\"areaEvidence\":null,\"riskRule\":null,\"minimumCrewRule\":null}";
        List<String> profiles=new ArrayList<>();
        if (!stage.equals(drop)) profiles.add(stageProfile);
        if (!lobby.equals(drop)) profiles.add(lobbyProfile);
        return "{\"expectedEventRevision\":\""+revision(event)+"\",\"expectedLayoutRevision\":"+q(layoutRevision)+",\"layout\":{\"coordinateSystem\":\"normalized-image\","
                +"\"width\":1920,\"height\":1080,\"zonePolygons\":[{\"zoneId\":\""+stage+"\",\"points\":"+stagePoints+"},"
                +"{\"zoneId\":\""+lobby+"\",\"points\":"+square(0.05)+"}],\"source\":"+evidence()+"},\"profiles\":["+String.join(",",profiles)+"]}";
    }
    static String q(String v) { return v==null?"null":"\""+v+"\""; }
    static String revisionOf(JsonNode map,UUID zone) { var p=profileOf(map,zone); return p==null?null:p.get("revision").asString(); }
    static JsonNode profileOf(JsonNode map,UUID zone) {
        for (JsonNode p:map.get("profiles")) if (p.get("zoneId").asString().equals(zone.toString())) return p;
        return null;
    }
    String coverage(JsonNode map,UUID zone) {
        var layout=map.get("layout");
        return "[{\"zoneId\":\""+zone+"\",\"layoutId\":\""+layout.get("id").asString()+"\",\"layoutRevision\":\""+layout.get("revision").asString()
                +"\",\"profileRevision\":\""+revisionOf(map,zone)+"\",\"scope\":\"PARTIAL\",\"roi\":"+square(0.2)+",\"effectiveAreaM2\":40,"
                +"\"calibrationEvidence\":"+evidence()+"}]";
    }
    static String cameraBody(String revision,String name,String binding,boolean enabled,String coverage) {
        return "{\"expectedEventRevision\":\""+revision+"\",\"name\":\""+name+"\",\"sourceBindingRef\":\""+binding+"\",\"enabled\":"+enabled
                +",\"coverage\":"+coverage+"}";
    }
    UUID observation(UUID event,UUID camera,long sequence,String receivedAt) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO safety_observation (id,event_id,camera_id,camera_revision,stream_session_id,sequence,captured_at,capture_time_basis,
                                                received_at,input_hash,coverage,state,freshness,estimates,model_version,processed_at,metadata_expires_at)
                VALUES (?,?,?,1,'99999999-9999-4999-8999-999999999999',?,NULL,'DEVICE_TIME_UNVERIFIED',?::timestamptz,?,
                        (SELECT coverage FROM safety_camera WHERE id=?),'READY','FRESH',?::jsonb,'crowd-test',?::timestamptz,?::timestamptz + interval '30 days')""",
                id,event,camera,sequence,receivedAt,"a".repeat(64),camera,
                "[{\"zoneId\":\""+UUID.randomUUID()+"\",\"estimatedCount\":12,\"densityPerM2\":null,\"qualityReasons\":[\"PARTIAL_COVERAGE\"],\"riskLevel\":\"UNASSESSED\",\"ruleRef\":null}]",
                receivedAt,receivedAt);
        return id;
    }
    JsonNode readMap(UUID event) throws Exception {
        var r=get("/api/v1/events/"+event+"/safety-map",chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
    String freshness(UUID camera) { return jdbc.queryForObject("SELECT freshness FROM safety_camera WHERE id=?",String.class,camera); }
    HttpResponse<String> save(UUID event,String body,String key) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/safety-map"))).header("Content-Type","application/json")
                .header("Origin",WEB).header("Cookie",chiefCookie).header("Idempotency-Key",key).method("PATCH",HttpRequest.BodyPublishers.ofString(body)));
    }
    HttpResponse<String> camera(UUID event,String body) throws Exception {
        return send(post("/api/v1/events/"+event+"/safety-cameras",body).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
    }
    HttpResponse<String> patch(UUID camera,String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/"+camera))).header("Content-Type","application/json")
                .header("Origin",WEB).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body)));
    }
    static List<String> texts(JsonNode array,String field) {
        var out=new ArrayList<String>(); for (JsonNode n:array) out.add(n.get(field).asString()); return out;
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
    void member(UUID event,UUID account,UUID group) {
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,(SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?),'WORKER','팀원',true,false,true,1)""",
                UUID.randomUUID(),event,account,group,event);
    }
    UUID recruiting() throws Exception {
        UUID event=create();
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        return event;
    }
    UUID create() throws Exception {
        var r=send(post("/api/v1/events","{\"name\":\"안전 시험 공연\"}").header("Cookie",chiefCookie)
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
