package com.ssafy.scenecrew.issue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import com.ssafy.scenecrew.support.FakeAudioProbe;
import com.ssafy.scenecrew.support.FakeFileStorage;
import com.ssafy.scenecrew.support.FileTestBeans;
import static org.assertj.core.api.Assertions.*;

/**
 * #340 060 음성 전사 접수·061 음성 원본 정리의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis.
 * 저장소와 ffprobe는 가짜(FileTestBeans)이고, outbox 주기 실행이 꺼져 있어 AI 서버를 부르지 않는다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import({FileTestBeans.class, TranscriptionHttpTest.Encoder.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TranscriptionHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final byte[] WEBM={0x1A,0x45,(byte)0xDF,(byte)0xA3,1,2,3,4};
    static final byte[] OTHER_WEBM={0x1A,0x45,(byte)0xDF,(byte)0xA3,9,9,9,9};
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"transcription-test"); r.add("scenecrew.auth.audience",()->"transcription-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->ANDROID);
        r.add("scenecrew.ai.transcription.audio-base-url",()->"https://app.example.invalid/");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired FakeFileStorage storage;
    @Autowired FakeAudioProbe probe;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    UUID worker, other, outsider, silent, event, otherEvent;
    String workerCookie, otherCookie, outsiderCookie, silentCookie;

    @BeforeEach void fixture() throws Exception {
        storage.reset(); probe.reset();
        worker=account(true); other=account(true); outsider=account(true); silent=account(false);
        event=event("LIVE"); otherEvent=event("LIVE");
        member(event,worker,"WORKER"); member(event,other,"WORKER"); member(event,silent,"WORKER");
        member(otherEvent,outsider,"CHIEF");
        workerCookie=login(worker); otherCookie=login(other); outsiderCookie=login(outsider); silentCookie=login(silent);
    }
    @AfterEach void cleanup() {
        for (var e:events) {
            jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
            jdbc.update("DELETE FROM outbox_event WHERE event_id=?",e);
            jdbc.update("DELETE FROM ai_job WHERE event_id=?",e);
            jdbc.update("DELETE FROM audio_asset WHERE event_id=?",e);
            jdbc.update("DELETE FROM member WHERE event_id=?",e);
            jdbc.update("DELETE FROM event WHERE id=?",e);
        }
        for (var a:accounts) {
            jdbc.update("DELETE FROM idempotency_record WHERE actor_account_id=?",a);
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        events.clear(); accounts.clear();
    }

    @Test void webUploadQueuesTranscriptionAndSameKeyReturnsFirstResponse() throws Exception {
        String key=UUID.randomUUID().toString();
        var r=send(upload(event,workerCookie,key,WEBM,"audio/webm",null));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("audioId","jobId","statusUrl");
        assertThat(body.get("statusUrl").asString()).isEqualTo("/api/v1/ai-jobs/"+body.get("jobId").asString());
        var job=jdbc.queryForMap("SELECT kind, state, requested_by_member_id, input::text AS input FROM ai_job WHERE id=?",
                UUID.fromString(body.get("jobId").asString()));
        assertThat(job).containsEntry("kind","TRANSCRIBE").containsEntry("state","QUEUED")
                .containsEntry("requested_by_member_id",memberId(event,worker));
        assertThat(json.readTree(job.get("input").toString()).get("audioId").asString()).isEqualTo(body.get("audioId").asString());
        assertThat(json.readTree(job.get("input").toString()).has("options")).isFalse();

        var again=send(upload(event,workerCookie,key.toUpperCase(),WEBM,"audio/webm",null));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(202);
        assertThat(json.readTree(again.body())).isEqualTo(body);
        assertThat(count("ai_job",event)).isEqualTo(1);
        assertThat(count("audio_asset",event)).isEqualTo(1);
        assertThat(storage.objects()).hasSize(1);
    }

    @Test void rawPipelineAndAndroidBearerAreAccepted() throws Exception {
        String bearer=androidLogin(other);
        var r=send(multipart(event,UUID.randomUUID().toString(),WEBM,"audio/webm","RAW")
                .header("Authorization","Bearer "+bearer).header("Origin",ANDROID));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        var input=jdbc.queryForObject("SELECT input::text FROM ai_job WHERE id=?",String.class,
                UUID.fromString(json.readTree(r.body()).get("jobId").asString()));
        assertThat(json.readTree(input).get("options").get("pipeline").asString()).isEqualTo("RAW");
    }

    @Test void sameKeyWithDifferentAudioIsMismatchWithoutSecondUpload() throws Exception {
        String key=UUID.randomUUID().toString();
        assertThat(send(upload(event,workerCookie,key,WEBM,"audio/webm",null)).statusCode()).isEqualTo(202);
        error(send(upload(event,workerCookie,key,OTHER_WEBM,"audio/webm",null)),409,"IDEMPOTENCY_MISMATCH");
        error(send(upload(event,workerCookie,key,WEBM,"audio/webm","RAW")),409,"IDEMPOTENCY_MISMATCH");
        assertThat(count("ai_job",event)).isEqualTo(1);
        assertThat(storage.objects()).hasSize(1);
    }

    /**
     * 응답 유실 뒤 앱이 같은 키로 동시에 다시 보낸 경우. 두 요청이 모두 업로드 단계까지 온 뒤(완료 기록 확인을 둘 다 지난 뒤)
     * 함께 진행시킨다. 등록 단계에서 하나만 남고, 늦은 쪽은 등록을 롤백·원본을 지운 뒤 앞 결과를 돌려준다.
     */
    @Test void concurrentSameKeyQueuesOneTranscriptionAndDiscardsLateUpload() throws Exception {
        String key=UUID.randomUUID().toString();
        var arrived=new CountDownLatch(2); var release=new CountDownLatch(1);
        storage.holdPuts(arrived,release);
        try {
            var first=client.sendAsync(upload(event,workerCookie,key,WEBM,"audio/webm",null).build(),HttpResponse.BodyHandlers.ofString());
            var second=client.sendAsync(upload(event,workerCookie,key,WEBM,"audio/webm",null).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(arrived.await(10,TimeUnit.SECONDS)).as("두 요청이 모두 업로드 단계에 도달").isTrue();
            release.countDown();
            var a=first.get(20,TimeUnit.SECONDS); var b=second.get(20,TimeUnit.SECONDS);
            assertThat(a.statusCode()).as(a.body()).isEqualTo(202);
            assertThat(b.statusCode()).as(b.body()).isEqualTo(202);
            assertThat(json.readTree(a.body())).isEqualTo(json.readTree(b.body()));
        } finally {
            release.countDown();
        }
        assertThat(count("ai_job",event)).isEqualTo(1);
        assertThat(count("audio_asset",event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE event_id=?",Long.class,event)).isEqualTo(1);
        assertThat(storage.objects()).hasSize(1);
    }

    /** 업로드하는 동안 이탈→재참여로 회차가 바뀌면 옛 회차 요청은 403이고 아무것도 남기지 않는다(#378). */
    @Test void rejoinDuringUploadRejectsOldRoundAndLeavesNothing() throws Exception {
        String key=UUID.randomUUID().toString();
        var arrived=new CountDownLatch(1); var release=new CountDownLatch(1);
        storage.holdPuts(arrived,release);
        HttpResponse<String> old;
        try {
            var pending=client.sendAsync(upload(event,workerCookie,key,WEBM,"audio/webm",null).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(arrived.await(10,TimeUnit.SECONDS)).as("업로드 단계에 도달").isTrue();
            // 같은 Member id로 재참여하면 회차만 오른다(InviteJoinRepository.rejoin).
            jdbc.update("UPDATE member SET participation_revision=participation_revision+1 WHERE event_id=? AND account_id=?",event,worker);
            release.countDown();
            old=pending.get(20,TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        error(old,403,"FORBIDDEN");
        assertThat(count("ai_job",event)).isZero();
        assertThat(count("audio_asset",event)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE event_id=?",Long.class,event)).isZero();
        assertThat(storage.objects()).isEmpty();
        // 새 회차에서 새로 보내면 정상 접수된다.
        var fresh=send(upload(event,workerCookie,UUID.randomUUID().toString(),WEBM,"audio/webm",null));
        assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(202);
        assertThat(count("ai_job",event)).isEqualTo(1);
    }

    /** 가입 동의 객체에 voice가 없거나 null·false면 500이 아니라 403이고, 아무것도 만들지 않는다. */
    @Test void missingOrNullVoiceConsentIsForbidden() throws Exception {
        for (String consent:List.of("{}","{\"voice\":null}","{\"voice\":false}","{\"voice\":\"true\"}")) {
            UUID account=account(consent); member(event,account,"WORKER");
            error(send(upload(event,login(account),UUID.randomUUID().toString(),WEBM,"audio/webm",null)),403,"FORBIDDEN");
        }
        assertThat(count("ai_job",event)).isZero();
        assertThat(count("audio_asset",event)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE event_id=?",Long.class,event)).isZero();
        assertThat(storage.objects()).isEmpty();
        assertThat(send(upload(event,workerCookie,UUID.randomUUID().toString(),WEBM,"audio/webm",null)).statusCode()).isEqualTo(202);
    }

    @Test void rejectionsCreateNothing() throws Exception {
        String key=UUID.randomUUID().toString();
        error(send(multipart(event,key,WEBM,"audio/webm",null).header("Origin",WEB)),401,"UNAUTHENTICATED");
        error(send(multipart(event,key,WEBM,"audio/webm",null).header("Cookie",workerCookie).header("Origin","https://evil.example.invalid")),
                403,"CSRF_REJECTED");
        error(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/transcriptions"))).header("Cookie",workerCookie)
                .header("Origin",WEB).header("Idempotency-Key",key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))),415,"UNSUPPORTED_MEDIA_TYPE");
        error(send(multipart(event,null,WEBM,"audio/webm",null).header("Cookie",workerCookie).header("Origin",WEB)),400,"INVALID_REQUEST");
        error(send(upload(event,workerCookie,"not-a-uuid",WEBM,"audio/webm",null)),400,"INVALID_REQUEST");
        error(send(upload(event,workerCookie,key,WEBM,"audio/webm","DENOISED")),400,"INVALID_REQUEST");
        error(send(upload(event,workerCookie,key,WEBM,"text/plain",null)),415,"UNSUPPORTED_MEDIA_TYPE");
        error(send(upload(otherEvent,workerCookie,key,WEBM,"audio/webm",null)),404,"NOT_FOUND");
        error(send(upload(event,outsiderCookie,key,WEBM,"audio/webm",null)),404,"NOT_FOUND");
        error(send(upload(event,silentCookie,key,WEBM,"audio/webm",null)),403,"FORBIDDEN");
        var extra=send(rawMultipart(event,workerCookie,key,
                part("audio","a.webm","audio/webm",WEBM)+"|"+part("note",null,null,"x".getBytes(StandardCharsets.UTF_8))));
        error(extra,400,"INVALID_REQUEST");
        assertThat(count("ai_job",event)+count("ai_job",otherEvent)).isZero();
        assertThat(count("audio_asset",event)+count("audio_asset",otherEvent)).isZero();
        assertThat(storage.objects()).isEmpty();
    }

    @Test void uploaderDeletesOriginalAndOthersCannot() throws Exception {
        var r=send(upload(event,workerCookie,UUID.randomUUID().toString(),WEBM,"audio/webm",null));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        UUID audioId=UUID.fromString(json.readTree(r.body()).get("audioId").asString());

        error(send(delete(audioId).header("Cookie",otherCookie).header("Origin",WEB)),404,"NOT_FOUND");
        error(send(delete(audioId).header("Cookie",outsiderCookie).header("Origin",WEB)),404,"NOT_FOUND");
        error(send(delete(audioId).header("Cookie",workerCookie).header("Origin","https://evil.example.invalid")),403,"CSRF_REJECTED");
        error(send(delete(audioId).header("Origin",WEB)),401,"UNAUTHENTICATED");
        error(send(delete(UUID.randomUUID()).header("Cookie",workerCookie).header("Origin",WEB)),404,"NOT_FOUND");
        assertThat(storage.objects()).hasSize(1);

        var deleted=send(delete(audioId).header("Cookie",workerCookie).header("Origin",WEB));
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
        assertThat(deleted.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(jdbc.queryForObject("SELECT storage_key FROM audio_asset WHERE id=?",String.class,audioId)).isNull();
        assertThat(storage.objects()).isEmpty();
        assertThat(send(delete(audioId).header("Cookie",workerCookie).header("Origin",WEB)).statusCode()).isEqualTo(204);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    HttpRequest.Builder upload(UUID event,String cookie,String key,byte[] audio,String type,String pipeline) {
        return multipart(event,key,audio,type,pipeline).header("Cookie",cookie).header("Origin",WEB);
    }
    HttpRequest.Builder multipart(UUID event,String key,byte[] audio,String type,String pipeline) {
        String parts=part("audio","voice.webm",type,audio)+(pipeline==null?"":"|"+part("pipeline",null,null,pipeline.getBytes(StandardCharsets.UTF_8)));
        return rawMultipart(event,null,key,parts);
    }
    static final String BOUNDARY="scenecrewBoundary7MA4YWxkTrZu0gW";
    /** part 문자열은 "이름\0파일명\0타입\0base64"를 |로 이은 것 */
    HttpRequest.Builder rawMultipart(UUID event,String cookie,String key,String parts) {
        var out=new ByteArrayOutputStream();
        for (String p:parts.split("\\|")) {
            String[] f=p.split("\0",-1);
            StringBuilder head=new StringBuilder("--"+BOUNDARY+"\r\nContent-Disposition: form-data; name=\""+f[0]+"\"");
            if (!f[1].isEmpty()) head.append("; filename=\"").append(f[1]).append("\"");
            head.append("\r\n");
            if (!f[2].isEmpty()) head.append("Content-Type: ").append(f[2]).append("\r\n");
            head.append("\r\n");
            out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes(Base64.getDecoder().decode(f[3]));
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--"+BOUNDARY+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var b=HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/transcriptions")))
                .header("Content-Type","multipart/form-data; boundary="+BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if (cookie!=null) b.header("Cookie",cookie).header("Origin",WEB);
        if (key!=null) b.header("Idempotency-Key",key);
        return b;
    }
    static String part(String name,String file,String type,byte[] content) {
        return name+"\0"+(file==null?"":file)+"\0"+(type==null?"":type)+"\0"+Base64.getEncoder().encodeToString(content);
    }
    HttpRequest.Builder delete(UUID audioId) {
        return HttpRequest.newBuilder(URI.create(url("/api/v1/audio-assets/"+audioId))).DELETE();
    }
    UUID account(boolean voiceConsent) {
        return account("{\"service\":true,\"voice\":"+voiceConsent+",\"push\":true,\"externalAi\":true}");
    }
    UUID account(String consent) {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, ?::jsonb, now())",id,consent);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    UUID event(String state) {
        var id=UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",
                id,"공연",state,3L,LocalDate.of(2026,11,14));
        return id;
    }
    void member(UUID event,UUID account,String role) {
        Long crew=jdbc.queryForObject("SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?",Long.class,event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,NULL,?,?,'fixture',true,false,true,2)""",UUID.randomUUID(),event,account,crew,role);
    }
    UUID memberId(UUID event,UUID account) {
        return jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,event,account);
    }
    long count(String table,UUID event) {
        return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE event_id=?",Long.class,event);
    }
    String login(UUID account) throws Exception {
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type","application/json")
                .header("X-SceneCrew-Client","WEB").header("Origin",WEB).header("X-SceneCrew-CSRF","1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    String androidLogin(UUID account) throws Exception {
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type","application/json")
                .header("X-SceneCrew-Client","ANDROID").header("Origin",ANDROID)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("accessToken").asString();
    }
    String url(String path) { return "http://localhost:"+port+path; }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        JsonNode body=json.readTree(r.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Encoder {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}
