package com.ssafy.scenecrew.document.service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import com.ssafy.scenecrew.support.FakeFileStorage;
import com.ssafy.scenecrew.support.FileTestBeans;
import static org.assertj.core.api.Assertions.*;

/**
 * SC-API-046 문서 등록·047 정리·048 다운로드의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis. S3는 FakeFileStorage다.
 * 응답 키는 계약(UploadResult·DocumentRegistered, FE의 v9 schemas.json과 같은 모양)과 정확히 같은지 본다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({IntakeDocumentHttpTest.Beans.class, FileTestBeans.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class IntakeDocumentHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] MD="# 운영 안내\n입장은 10시부터".getBytes(StandardCharsets.UTF_8);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"document-test"); r.add("scenecrew.auth.audience",()->"document-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired FakeFileStorage storage;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>();
    UUID chief, worker, outsider;
    String chiefCookie, workerCookie, outsiderCookie;

    record FilePart(String name, String type, byte[] content) { }

    @BeforeEach void fixture() throws Exception {
        storage.reset();
        chief=account(); worker=account(); outsider=account();
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
    }
    @AfterEach void cleanup() {
        storage.reset();
        for (var a:accounts) {
            for (var e:jdbc.queryForList("SELECT DISTINCT event_id FROM member WHERE account_id=?",UUID.class,a)) {
                jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
                jdbc.update("DELETE FROM intake_document WHERE event_id=?",e);
                jdbc.update("DELETE FROM ai_job WHERE event_id=?",e);
                jdbc.update("DELETE FROM member WHERE event_id=?",e);
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

    // ---- 046 등록 ---------------------------------------------------------------------------------------------

    @Test void registerReplayAndMismatch() throws Exception {
        UUID event=create();
        OffsetDateTime before=lastInput(event);
        String key=UUID.randomUUID().toString();
        var files=List.of(new FilePart("안내.md","text/markdown",MD),new FilePart("일정.pdf","application/pdf",Pdfs.pages(2)));
        var r=upload(event,key,files,chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("files","eventRevision");
        assertThat(body.get("eventRevision").asString()).isEqualTo("2");
        assertThat(body.get("files")).hasSize(2);
        for (int i=0;i<2;i++) {
            var f=body.get("files").get(i);
            assertThat(f.propertyNames()).containsExactlyInAnyOrder("index","status","document");
            assertThat(f.get("index").asInt()).isEqualTo(i);
            assertThat(f.get("index").isIntegralNumber()).isTrue();
            assertThat(f.get("status").asString()).isEqualTo("REGISTERED");
            assertThat(f.get("document").propertyNames()).containsExactlyInAnyOrder("id","originalName","bytes","pageCount","expiresAt");
        }
        var md=body.get("files").get(0).get("document");
        assertThat(md.get("originalName").asString()).isEqualTo("안내.md");
        assertThat(md.get("bytes").asString()).isEqualTo(Integer.toString(MD.length));
        assertThat(md.get("pageCount").isNull()).isTrue();
        assertThat(body.get("files").get(1).get("document").get("pageCount").asInt()).isEqualTo(2);
        // 입력 묶음이 바뀌었으므로 revision과 마지막 입력 시각이 바뀐다.
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(2L);
        assertThat(lastInput(event)).isAfter(before);
        assertThat(storage.objects()).hasSize(2);

        // 같은 키·같은 파일은 다시 올리지 않고 최초 응답을 그대로 돌려준다.
        var again=upload(event,key,files,chiefCookie);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json.readTree(again.body())).isEqualTo(body);
        assertThat(storage.objects()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM intake_document WHERE event_id=?",Long.class,event)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(2L);
        // 같은 키에 다른 파일(내용·순서)은 409 IDEMPOTENCY_MISMATCH
        error(upload(event,key,List.of(files.get(1),files.get(0)),chiefCookie),409,"IDEMPOTENCY_MISMATCH");
        error(upload(event,key,List.of(new FilePart("안내.md","text/markdown","다른 내용".getBytes(StandardCharsets.UTF_8))),chiefCookie),
                409,"IDEMPOTENCY_MISMATCH");
        assertThat(storage.objects()).hasSize(2);
    }

    @Test void concurrentSameKeyRegistersOnce() throws Exception {
        UUID event=create();
        String key=UUID.randomUUID().toString();
        var files=List.of(new FilePart("안내.md","text/markdown",MD));
        var arrived=new CountDownLatch(2); var release=new CountDownLatch(1);
        storage.holdPuts(arrived,release);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->upload(event,key,files,chiefCookie));
            var b=pool.submit(()->upload(event,key,files,chiefCookie));
            // 두 요청이 모두 원본 저장 단계까지 와서 겹친 뒤에 진행시킨다.
            assertThat(arrived.await(20,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var x=a.get(60,TimeUnit.SECONDS); var y=b.get(60,TimeUnit.SECONDS);
            assertThat(x.statusCode()).as(x.body()).isEqualTo(200);
            assertThat(y.statusCode()).as(y.body()).isEqualTo(200);
            assertThat(json.readTree(x.body())).isEqualTo(json.readTree(y.body()));
        } finally { pool.shutdownNow(); storage.reset(); }
        // 등록은 한 번이고, 늦은 쪽이 올린 원본은 지웠다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM intake_document WHERE event_id=?",Long.class,event)).isOne();
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(2L);
    }

    @Test void oneBadFileRejectsWholeRequestWithItsIndex() throws Exception {
        UUID event=create();
        byte[] notUtf8={(byte)0xff,(byte)0xfe,(byte)0x00};
        var r=upload(event,UUID.randomUUID().toString(),List.of(new FilePart("안내.md","text/markdown",MD),
                new FilePart("깨진.txt","text/plain",notUtf8)),chiefCookie);
        error(r,415,"UNSUPPORTED_MEDIA_TYPE");
        var details=json.readTree(r.body()).get("details");
        assertThat(details.get("index").asInt()).isEqualTo(1);
        assertThat(details.get("fileName").asString()).isEqualTo("깨진.txt");
        assertThat(storage.objects()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM intake_document WHERE event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(1L);
    }

    @Test void bundleLimitsCountPdfPagesAcrossFiles() throws Exception {
        UUID event=create();
        var first=upload(event,UUID.randomUUID().toString(),List.of(new FilePart("a.pdf","application/pdf",Pdfs.pages(20))),chiefCookie);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        // 파일 하나는 30쪽 이하지만 묶음 합계가 31쪽이 되면 거절한다(P08 PDF합계30쪽).
        var r=upload(event,UUID.randomUUID().toString(),List.of(new FilePart("b.pdf","application/pdf",Pdfs.pages(11))),chiefCookie);
        error(r,413,"PAYLOAD_TOO_LARGE");
        assertThat(json.readTree(r.body()).get("details").get("maxTotalPages").asInt()).isEqualTo(30);
        var ten=upload(event,UUID.randomUUID().toString(),List.of(new FilePart("c.pdf","application/pdf",Pdfs.pages(10))),chiefCookie);
        assertThat(ten.statusCode()).as("합계 30쪽은 받는다: "+ten.body()).isEqualTo(200);
        // 한 번에 4개는 거절
        var four=new ArrayList<FilePart>();
        for (int i=0;i<4;i++) four.add(new FilePart(i+".md","text/markdown",MD));
        error(upload(create(),UUID.randomUUID().toString(),four,chiefCookie),413,"PAYLOAD_TOO_LARGE");
    }

    @Test void uploadFormatAndAccess() throws Exception {
        UUID event=create();
        member(event,worker);
        var files=List.of(new FilePart("안내.md","text/markdown",MD));
        // 요청 키 없음, 파일 없음, 다른 파트 이름은 400
        error(send(multipart(event,null,files,chiefCookie,WEB)),400,"INVALID_REQUEST");
        error(upload(event,UUID.randomUUID().toString(),List.of(),chiefCookie),400,"INVALID_REQUEST");
        var other=multipartBody(List.of(new FilePart("안내.md","text/markdown",MD)),"attachment");
        error(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/documents"))).header("Content-Type","multipart/form-data; boundary="+other.boundary())
                .header("Origin",WEB).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(other.body()))),400,"INVALID_REQUEST");
        // 팀원 403, 비참여 404, 로그인 없음 401, 허용하지 않은 Origin 403
        error(upload(event,UUID.randomUUID().toString(),files,workerCookie),403,"FORBIDDEN");
        error(upload(event,UUID.randomUUID().toString(),files,outsiderCookie),404,"NOT_FOUND");
        assertThat(send(multipart(event,UUID.randomUUID().toString(),files,null,WEB)).statusCode()).isEqualTo(401);
        assertThat(send(multipart(event,UUID.randomUUID().toString(),files,chiefCookie,"https://evil.example.invalid")).statusCode()).isEqualTo(403);
        // 문서 추출 중이거나 공개 뒤에는 파일을 바꾸지 않는다.
        jdbc.update("""
                INSERT INTO ai_job (id,event_id,requested_by_member_id,kind,state,input_hash,context_revision,request_schema_version,
                                    input,attempt,execution_meta,auto_reanalysis_count,created_at,deadline_at)
                SELECT gen_random_uuid(),?,m.id,'EXTRACT_EVENT_INPUT','QUEUED',repeat('a',64),1,'4','{}'::jsonb,0,'{}'::jsonb,0,now(),
                       now()+interval '1 hour'
                  FROM member m WHERE m.event_id=? AND m.event_role='CHIEF'""",event,event);
        error(upload(event,UUID.randomUUID().toString(),files,chiefCookie),409,"INVALID_STATE");
        jdbc.update("DELETE FROM ai_job WHERE event_id=?",event);
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        error(upload(event,UUID.randomUUID().toString(),files,chiefCookie),409,"INVALID_STATE");
        assertThat(storage.objects()).isEmpty();
    }

    // ---- 047 정리 ---------------------------------------------------------------------------------------------

    @Test void deleteAllWithExpectedRevision() throws Exception {
        UUID event=create();
        assertThat(upload(event,UUID.randomUUID().toString(),List.of(new FilePart("안내.md","text/markdown",MD)),chiefCookie).statusCode()).isEqualTo(200);
        // 옛 revision은 409이고 아무것도 지우지 않는다.
        var stale=delete(event,"{\"expectedEventRevision\":\"1\"}",chiefCookie);
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("event");
        assertThat(storage.objects()).hasSize(1);
        error(delete(event,"{}",chiefCookie),400,"INVALID_REQUEST");
        error(delete(event,"{\"expectedEventRevision\":2}",chiefCookie),400,"INVALID_REQUEST");
        member(event,worker);
        error(delete(event,"{\"expectedEventRevision\":\"2\"}",workerCookie),403,"FORBIDDEN");
        error(delete(event,"{\"expectedEventRevision\":\"2\"}",outsiderCookie),404,"NOT_FOUND");

        var ok=delete(event,"{\"expectedEventRevision\":\"2\"}",chiefCookie);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(204);
        assertThat(storage.objects()).isEmpty();
        // 원본을 지웠으므로 입력 묶음이 바뀌어 revision이 오른다. 메타데이터 행은 남고 저장 위치만 비운다.
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM intake_document WHERE event_id=? AND storage_key IS NULL",Long.class,event)).isOne();
        // 더 지울 것이 없으면 아무것도 바꾸지 않는다.
        assertThat(delete(event,"{\"expectedEventRevision\":\"3\"}",chiefCookie).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(3L);
    }

    // ---- 048 다운로드 -----------------------------------------------------------------------------------------

    /** V9-D62: 계획 작성 화면의 평면도 배경. 입력 PDF 한 쪽을 PNG로 그린다. 권한은 048 내려받기와 같다. */
    @Test void pdfPageImage() throws Exception {
        UUID event=create();
        var r=json.readTree(upload(event,UUID.randomUUID().toString(),List.of(new FilePart("계획서.pdf","application/pdf",Pdfs.pages(2)),
                new FilePart("안내.md","text/markdown",MD)),chiefCookie).body());
        String pdf=r.get("files").get(0).get("document").get("id").asString();
        String md=r.get("files").get(1).get("document").get("id").asString();
        var image=client.send(HttpRequest.newBuilder(URI.create(url("/api/v1/intake-documents/"+pdf+"/pages/2/image")))
                .header("Cookie",chiefCookie).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(image.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(java.util.Arrays.copyOf(image.body(),8)).isEqualTo(new byte[]{(byte)0x89,'P','N','G',0x0d,0x0a,0x1a,0x0a});
        var png=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image.body()));
        assertThat(Math.max(png.getWidth(),png.getHeight())).isEqualTo(1600);
        error(get("/api/v1/intake-documents/"+pdf+"/pages/3/image",chiefCookie),404,"NOT_FOUND");
        error(get("/api/v1/intake-documents/"+md+"/pages/1/image",chiefCookie),404,"NOT_FOUND");
        error(get("/api/v1/intake-documents/"+pdf+"/pages/0/image",chiefCookie),400,"INVALID_REQUEST");
        assertThat(get("/api/v1/intake-documents/"+pdf+"/pages/1/image",outsiderCookie).statusCode()).isEqualTo(403);
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/v1/intake-documents/"+pdf+"/pages/1/image"))).GET()).statusCode()).isEqualTo(401);
    }

    @Test void downloadOriginal() throws Exception {
        UUID event=create();
        var r=json.readTree(upload(event,UUID.randomUUID().toString(),List.of(new FilePart("운영 안내.md","text/markdown",MD)),chiefCookie).body());
        String id=r.get("files").get(0).get("document").get("id").asString();
        var d=client.send(HttpRequest.newBuilder(URI.create(url("/api/v1/intake-documents/"+id+"/download"))).header("Cookie",chiefCookie).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(d.statusCode()).isEqualTo(200);
        assertThat(d.body()).isEqualTo(MD);
        assertThat(d.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/markdown").containsIgnoringCase("charset=UTF-8");
        assertThat(d.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment").contains("filename*=UTF-8''");
        assertThat(d.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(d.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        error(get("/api/v1/intake-documents/"+UUID.randomUUID()+"/download",chiefCookie),404,"NOT_FOUND");
        error(get("/api/v1/intake-documents/not-a-uuid/download",chiefCookie),400,"INVALID_REQUEST");
        assertThat(get("/api/v1/intake-documents/"+id+"/download",outsiderCookie).statusCode()).isEqualTo(403);
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/v1/intake-documents/"+id+"/download"))).GET()).statusCode()).isEqualTo(401);
    }

    // ---- 도우미 ------------------------------------------------------------------------------------------------

    record Multipart(String boundary, byte[] body) { }
    static Multipart multipartBody(List<FilePart> files,String partName) {
        String boundary="----scenecrew"+UUID.randomUUID().toString().replace("-","");
        var out=new ByteArrayOutputStream();
        for (var f:files) {
            out.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+partName+"\"; filename=\""+f.name()+"\"\r\nContent-Type: "
                    +f.type()+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(f.content());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Multipart(boundary,out.toByteArray());
    }
    HttpRequest.Builder multipart(UUID event,String key,List<FilePart> files,String cookie,String origin) {
        var m=multipartBody(files,"files");
        var b=HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/documents")))
                .header("Content-Type","multipart/form-data; boundary="+m.boundary()).header("Origin",origin)
                .POST(HttpRequest.BodyPublishers.ofByteArray(m.body()));
        if (cookie!=null) b.header("Cookie",cookie);
        if (key!=null) b.header("Idempotency-Key",key);
        return b;
    }
    HttpResponse<String> upload(UUID event,String key,List<FilePart> files,String cookie) throws Exception {
        return send(multipart(event,key,files,cookie,WEB));
    }
    HttpResponse<String> delete(UUID event,String body,String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/documents"))).header("Content-Type","application/json")
                .header("Origin",WEB).header("Cookie",cookie).method("DELETE",HttpRequest.BodyPublishers.ofString(body)));
    }
    HttpResponse<String> get(String path,String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url(path))).header("Cookie",cookie).GET());
    }
    OffsetDateTime lastInput(UUID event) {
        return jdbc.queryForObject("SELECT last_input_modified_at FROM event WHERE id=?",OffsetDateTime.class,event);
    }
    void member(UUID event,UUID account) {
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,NULL,(SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?),'WORKER','팀원',true,false,true,1)""",
                UUID.randomUUID(),event,account,event);
    }
    UUID create() throws Exception {
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/events"))).header("Content-Type","application/json").header("Origin",WEB)
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"문서 시험 공연\"}")));
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
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type","application/json").header("Origin",WEB)
                .header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    String url(String path) { return "http://localhost:"+port+path; }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Beans {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}
