package com.ssafy.scenecrew.chat;

import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** #237·#238 채팅 채널·복구(REST)와 WebSocket의 실제 HTTP·Security·PostgreSQL·Redis 시험 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ChatHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ChatHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"chat-test"); r.add("scenecrew.auth.audience",()->"chat-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionAuthenticator authenticator;
    @Autowired StringRedisTemplate redis;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    final List<WebSocketSession> sockets=new ArrayList<>();
    UUID chief, sound1, sound2, safety1, event, sound, safety, sound1Member;
    String chiefCookie, sound1Cookie, sound2Cookie, safety1Cookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); sound1=account(); sound2=account(); safety1=account();
        event=event();
        sound=group(event,"음향·장비"); safety=group(event,"관객 안전");
        member(event,chief,"CHIEF",null,"김하준"); sound1Member=member(event,sound1,"WORKER",sound,"최도윤");
        member(event,sound2,"WORKER",sound,"이지우"); member(event,safety1,"WORKER",safety,"오하늘");
        chiefCookie=login(chief); sound1Cookie=login(sound1); sound2Cookie=login(sound2); safety1Cookie=login(safety1);
    }
    @AfterEach void cleanup() throws Exception {
        for (var s:sockets) if (s.isOpen()) s.close();
        sockets.clear();
        for (var e:events) {
            jdbc.update("DELETE FROM chat_message WHERE event_id=?",e);
            jdbc.update("DELETE FROM member WHERE event_id=?",e);
            jdbc.update("DELETE FROM work_group WHERE event_id=?",e);
            jdbc.update("DELETE FROM event WHERE id=?",e);
        }
        for (var a:accounts) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        events.clear(); accounts.clear();
    }

    @Test void channelsAreAllPlusAllowedGroups() throws Exception {
        var chiefChannels=json.readTree(ok(get("/api/v1/events/"+event+"/channels",chiefCookie)).body());
        assertThat(chiefChannels.get("nextCursor").isNull()).isTrue();
        assertThat(names(chiefChannels.get("items"))).containsExactly("전체","관객 안전","음향·장비");
        assertThat(chiefChannels.get("items").get(0).get("workGroupId").isNull()).isTrue();
        var workerChannels=json.readTree(ok(get("/api/v1/events/"+event+"/channels",sound1Cookie)).body());
        assertThat(names(workerChannels.get("items"))).containsExactly("전체","음향·장비");
    }

    @Test void webSocketSendStoresOnceAndDeliversToSubscribersOfThatChannel() throws Exception {
        var a=webSocket(sound1Cookie); var b=webSocket(sound2Cookie); var c=webSocket(safety1Cookie); var boss=webSocket(chiefCookie);
        for (var s:List.of(a,b,c,boss)) {
            var ready=s.next();
            assertThat(ready.propertyNames()).containsExactly("type","memberId","participationRevision","subscribedChannels");
            assertThat(ready.get("type").asString()).isEqualTo("READY");
            assertThat(ready.get("participationRevision").asString()).isEqualTo("2");
            assertThat(ready.get("subscribedChannels").size()).isZero();
        }
        assertThat(a.next0()).isNull(); // 구독 전에는 아무것도 오지 않는다
        for (var s:List.of(a,b,c,boss)) { s.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":null}"); assertThat(s.next().get("subscribedChannels").get(0).isNull()).isTrue(); }
        for (var s:List.of(a,b,boss)) { s.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":\""+sound+"\"}"); assertThat(s.next().get("subscribedChannels").size()).isEqualTo(2); }

        String cid=UUID.randomUUID().toString();
        a.send("{\"type\":\"SEND\",\"workGroupId\":null,\"clientMessageId\":\""+cid+"\",\"text\":\"예비 마이크 C로 교체합니다\"}");
        var stored=a.next();
        assertThat(stored.get("type").asString()).isEqualTo("STORED");
        var m=stored.get("message");
        assertThat(m.propertyNames()).containsExactly("id","workGroupId","senderMemberId","senderDisplayName","sequence","clientMessageId","text","createdAt");
        assertThat(m.get("sequence").asString()).isEqualTo("1");
        assertThat(m.get("senderDisplayName").asString()).isEqualTo("최도윤");
        assertThat(m.get("senderMemberId").asString()).isEqualTo(sound1Member.toString());
        for (var s:List.of(b,c,boss)) { var f=s.next(); assertThat(f.get("type").asString()).isEqualTo("MESSAGE"); assertThat(f.get("message")).isEqualTo(m); }

        // 그룹 채널 메시지는 그 그룹을 구독한 연결(같은 그룹·총괄)에만 간다. 번호는 행사 전체에서 이어진다.
        b.send("{\"type\":\"SEND\",\"workGroupId\":\""+sound+"\",\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"믹서 확인\"}");
        assertThat(b.next().get("message").get("sequence").asString()).isEqualTo("2");
        assertThat(a.next().get("message").get("text").asString()).isEqualTo("믹서 확인");
        assertThat(boss.next().get("type").asString()).isEqualTo("MESSAGE");
        assertThat(c.next0()).isNull();

        // 같은 clientMessageId·같은 본문은 기존 메시지(다시 알리지 않음), 다른 본문은 거절
        a.send("{\"type\":\"SEND\",\"workGroupId\":null,\"clientMessageId\":\""+cid+"\",\"text\":\"예비 마이크 C로 교체합니다\"}");
        assertThat(a.next().get("message").get("id")).isEqualTo(m.get("id"));
        assertThat(b.next0()).isNull();
        a.send("{\"type\":\"SEND\",\"workGroupId\":null,\"clientMessageId\":\""+cid+"\",\"text\":\"다른 내용\"}");
        var err=a.next();
        assertThat(err.get("type").asString()).isEqualTo("ERROR");
        assertThat(err.get("code").asString()).isEqualTo("IDEMPOTENCY_MISMATCH");
        assertThat(err.get("clientMessageId").asString()).isEqualTo(cid);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE event_id=?",Long.class,event)).isEqualTo(2);

        // 구독하지 않은 채널에는 보낼 수 없다
        c.send("{\"type\":\"SEND\",\"workGroupId\":\""+safety+"\",\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"x\"}");
        assertThat(c.next().get("code").asString()).isEqualTo("INVALID_STATE");
    }

    @Test void subscribeAndRevocationAreCheckedEveryTime() throws Exception {
        var a=webSocket(sound1Cookie);
        a.next();
        a.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":\""+safety+"\"}");
        var err=a.next();
        assertThat(err.get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(err.get("clientMessageId").isNull()).isTrue();
        assertThat(a.closed().get(5,TimeUnit.SECONDS)).isNotNull();

        var b=webSocket(sound2Cookie);
        b.next();
        b.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":null}"); b.next();
        // 이탈하면 다음 보내기에서 NOT_FOUND로 끊긴다(이전 권한으로 저장하지 않는다).
        jdbc.update("UPDATE member SET active=false WHERE event_id=? AND account_id=?",event,sound2);
        b.send("{\"type\":\"SEND\",\"workGroupId\":null,\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"x\"}");
        assertThat(b.next().get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(b.closed().get(5,TimeUnit.SECONDS)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE event_id=?",Long.class,event)).isZero();
    }

    @Test void publicLogoutRevokesHttpAndOpenSocketsForOnlyThatSession() throws Exception {
        var targetLogin = loginResponse(sound1, "WEB", WEB);
        String targetAccess = authCookie(targetLogin, "__Secure-sc-access");
        String targetRefresh = authCookie(targetLogin, "__Secure-sc-refresh");
        String independentWeb = login(sound1);
        String independentAndroid = json.readTree(loginResponse(sound1, "ANDROID", ANDROID).body())
                .get("accessToken").asString();
        String targetKey = sessionKey(targetAccess.substring(targetAccess.indexOf('=') + 1));
        String webKey = sessionKey(independentWeb.substring(independentWeb.indexOf('=') + 1));
        String androidKey = sessionKey(independentAndroid);
        assertThat(List.of(targetKey, webKey, androidKey)).doesNotHaveDuplicates();
        for (String key : List.of(targetKey, webKey, androidKey)) {
            assertThat(redis.hasKey(key)).isTrue();
        }

        String channels = "/api/v1/events/" + event + "/channels";
        assertThat(get(channels, targetAccess).statusCode()).isEqualTo(200);
        var revokedSender = webSocket(targetAccess);
        var revokedSubscriber = webSocket(targetAccess);
        var web = webSocket(independentWeb);
        var android = connect(null, ANDROID);
        android.send(json.createObjectNode().put("type", "AUTH").put("accessToken", independentAndroid).toString());
        for (var socket : List.of(revokedSender, revokedSubscriber, web, android)) {
            var ready = socket.next();
            assertThat(ready.path("type").asString()).isEqualTo("READY");
            assertThat(ready.path("memberId").asString()).isEqualTo(sound1Member.toString());
            socket.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":null}");
            var subscribed = socket.next();
            assertThat(subscribed.path("type").asString()).isEqualTo("READY");
            assertThat(subscribed.path("subscribedChannels").size()).isEqualTo(1);
            assertThat(subscribed.path("subscribedChannels").get(0).isNull()).isTrue();
        }

        var logout = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/logout"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Origin", WEB)
                .header("X-SceneCrew-Client", "WEB").header("X-SceneCrew-CSRF", "1")
                .header("X-SceneCrew-Logout-Attempt", "AAAAAAAAAAAAAAAAAAAAAA")
                .header("Cookie", targetAccess + "; " + targetRefresh)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(redis.hasKey(targetKey)).isFalse();
        assertThat(redis.hasKey(webKey)).isTrue();
        assertThat(redis.hasKey(androidKey)).isTrue();
        var denied = get(channels, targetAccess);
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(json.readTree(denied.body()).path("code").asString()).isEqualTo("UNAUTHENTICATED");

        UUID rejected = UUID.randomUUID();
        revokedSender.send(sendFrame(rejected, "회수된 세션의 메시지"));
        assertThat(revokedSender.closed().get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4401);
        assertThat(revokedSender.frames()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_message WHERE event_id=? AND client_message_id=?",
                Long.class, event, rejected.toString()))
                .isZero();

        assertThat(get(channels, independentWeb).statusCode()).isEqualTo(200);
        var androidRead = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + channels))
                .timeout(Duration.ofSeconds(10)).header("Origin", ANDROID)
                .header("Authorization", "Bearer " + independentAndroid).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(androidRead.statusCode()).isEqualTo(200);

        UUID delivered = UUID.randomUUID();
        web.send(sendFrame(delivered, "독립 세션의 메시지"));
        var stored = web.next();
        assertThat(stored.path("type").asString()).isEqualTo("STORED");
        assertThat(stored.path("message").path("clientMessageId").asString()).isEqualTo(delivered.toString());
        var received = android.next();
        assertThat(received.path("type").asString()).isEqualTo("MESSAGE");
        assertThat(received.path("message")).isEqualTo(stored.path("message"));
        assertThat(revokedSubscriber.closed().get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4401);
        assertThat(revokedSubscriber.frames()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT client_message_id FROM chat_message WHERE event_id=?", String.class, event))
                .isEqualTo(delivered.toString());
    }

    private String sessionKey(String accessToken) {
        return RedisLoginSessions.key(authenticator.authenticate(accessToken).sessionId());
    }

    private String sendFrame(UUID clientMessageId, String text) {
        return json.createObjectNode().put("type", "SEND").putNull("workGroupId")
                .put("clientMessageId", clientMessageId.toString()).put("text", text).toString();
    }

    @Test void handshakeChecksOriginAndCredentialsAndAndroidAuthFrame() throws Exception {
        assertThatThrownBy(()->connect(sound1Cookie,"https://evil.example.invalid")).isInstanceOf(Exception.class);
        assertThatThrownBy(()->connect(null,WEB)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->connect("__Secure-sc-access=forged",WEB)).isInstanceOf(Exception.class);
        // Android: Cookie 없이 연결하고 첫 프레임 AUTH
        var login=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/login")).header("Content-Type","application/json")
                .header("X-SceneCrew-Client","ANDROID").header("Origin",ANDROID)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+sound1+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")).build(),HttpResponse.BodyHandlers.ofString());
        String token=json.readTree(login.body()).get("accessToken").asString();
        var app=connect(null,ANDROID);
        app.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":null}");
        assertThat(app.next().get("code").asString()).isEqualTo("INVALID_STATE"); // AUTH 전
        var app2=connect(null,ANDROID);
        app2.send("{\"type\":\"AUTH\",\"accessToken\":\""+token+"\"}");
        var ready=app2.next();
        assertThat(ready.get("type").asString()).isEqualTo("READY");
        assertThat(ready.get("memberId").asString()).isEqualTo(sound1Member.toString());
        // WEB Cookie 세션의 토큰으로 Android AUTH를 하면 채널이 달라 거절
        var app3=connect(null,ANDROID);
        app3.send("{\"type\":\"AUTH\",\"accessToken\":\""+sound1Cookie.substring(sound1Cookie.indexOf('=')+1)+"\"}");
        assertThat(app3.next().get("code").asString()).isEqualTo("UNAUTHENTICATED");
        // AUTH가 5초 안에 오지 않으면 닫는다
        var silent=connect(null,ANDROID);
        assertThat(silent.closed().get(8,TimeUnit.SECONDS)).isNotNull();
    }

    @Test void recoveryReturnsStableUpperBoundAndOnlyAccessibleChannels() throws Exception {
        var a=webSocket(sound1Cookie); a.next();
        a.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":null}"); a.next();
        a.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":\""+sound+"\"}"); a.next();
        var c=webSocket(safety1Cookie); c.next();
        c.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":\""+safety+"\"}"); c.next();
        for (int i=0;i<4;i++) { a.send("{\"type\":\"SEND\",\"workGroupId\":"+(i%2==0?"null":"\""+sound+"\"")+",\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"m"+i+"\"}"); a.next(); }
        c.send("{\"type\":\"SEND\",\"workGroupId\":\""+safety+"\",\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"안전\"}"); c.next();

        var page=json.readTree(ok(get("/api/v1/events/"+event+"/messages?afterSequence=0",sound1Cookie)).body());
        assertThat(page.propertyNames()).containsExactly("items","nextCursor","stableThroughSequence");
        assertThat(page.get("stableThroughSequence").asString()).isEqualTo("5");
        assertThat(texts(page.get("items"))).containsExactly("m0","m1","m2","m3"); // 다른 그룹(안전) 메시지는 없다
        assertThat(page.get("nextCursor").isNull()).isTrue();
        // 쪽 나누기: 같은 H를 유지하고, 그사이 새 메시지는 다음 복구에서 받는다.
        var first=json.readTree(ok(get("/api/v1/events/"+event+"/messages?afterSequence=1&limit=2",sound1Cookie)).body());
        assertThat(texts(first.get("items"))).containsExactly("m1","m2");
        a.send("{\"type\":\"SEND\",\"workGroupId\":null,\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"late\"}"); a.next();
        var second=json.readTree(ok(get("/api/v1/events/"+event+"/messages?cursor="+first.get("nextCursor").asString()+"&limit=2",sound1Cookie)).body());
        assertThat(texts(second.get("items"))).containsExactly("m3");
        assertThat(second.get("stableThroughSequence").asString()).isEqualTo("5");
        assertThat(second.get("nextCursor").isNull()).isTrue();
        // 채널 하나만
        var only=json.readTree(ok(get("/api/v1/events/"+event+"/messages?workGroupId="+sound,sound1Cookie)).body());
        assertThat(texts(only.get("items"))).containsExactly("m1","m3");
        assertThat(get("/api/v1/events/"+event+"/messages?workGroupId="+safety,sound1Cookie).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/events/"+event+"/messages?afterSequence=1&cursor="+first.get("nextCursor").asString(),sound1Cookie).statusCode()).isEqualTo(400);
        var chiefAll=json.readTree(ok(get("/api/v1/events/"+event+"/messages",chiefCookie)).body());
        assertThat(texts(chiefAll.get("items"))).containsExactly("m0","m1","m2","m3","안전","late");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0x00a0,0x202f,0x0085})
    void rejectsUnicodeWhitespaceOnlyMessages(int codePoint) throws Exception {
        var socket=webSocket(sound1Cookie);
        socket.next();
        socket.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":null}");
        socket.next();
        socket.send("{\"type\":\"SEND\",\"workGroupId\":null,\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\""+Character.toString(codePoint)+"\"}");
        var reply=socket.next();
        assertThat(reply.get("type").asString()).isEqualTo("ERROR");
        assertThat(reply.get("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE event_id=?",Long.class,event)).isZero();
    }

    @Test void endedEventRejectsNewMessages() throws Exception {
        var socket=webSocket(sound1Cookie);
        assertThat(socket.next().get("type").asString()).isEqualTo("READY");
        socket.send("{\"type\":\"SUBSCRIBE\",\"workGroupId\":null}");
        assertThat(socket.next().get("type").asString()).isEqualTo("READY");
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        socket.send("{\"type\":\"SEND\",\"workGroupId\":null,\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"종료 뒤 새 메시지\"}");
        JsonNode reply=socket.next();
        assertThat(reply.get("type").asString()).isEqualTo("ERROR");
        assertThat(reply.get("code").asString()).isEqualTo("INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE event_id=?",Long.class,event)).isZero();
    }

    // ---- 도움 ----
    record Socket(WebSocketSession session, BlockingQueue<JsonNode> frames, CompletableFuture<CloseStatus> closed) {
        JsonNode next0() throws Exception { return frames.poll(300,TimeUnit.MILLISECONDS); }
        JsonNode next() throws Exception {
            var f=frames.poll(5,TimeUnit.SECONDS);
            assertThat(f).as("프레임이 와야 한다").isNotNull();
            return f;
        }
        void send(String text) throws Exception { session.sendMessage(new TextMessage(text)); }
    }
    Socket webSocket(String cookie) throws Exception { return connect(cookie==null?null:cookie,WEB); }
    Socket connect(String cookie,String origin) throws Exception {
        var frames=new LinkedBlockingQueue<JsonNode>();
        var closed=new CompletableFuture<CloseStatus>();
        var headers=new WebSocketHttpHeaders();
        headers.add("Origin",origin);
        if (cookie!=null) headers.add("Cookie",cookie);
        var session=new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) { frames.add(json.readTree(m.getPayload())); }
            @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus st) { closed.complete(st); }
        },headers,URI.create("ws://localhost:"+port+"/api/v1/events/"+event+"/ws")).get(5,TimeUnit.SECONDS);
        sockets.add(session);
        return new Socket(session,frames,closed);
    }
    static List<String> texts(JsonNode items) { var out=new ArrayList<String>(); for (JsonNode n:items) out.add(n.get("text").asString()); return out; }
    static List<String> names(JsonNode items) { var out=new ArrayList<String>(); for (JsonNode n:items) out.add(n.get("name").asString()); return out; }
    HttpResponse<String> ok(HttpResponse<String> r) { assertThat(r.statusCode()).as(r.body()).isEqualTo(200); return r; }
    HttpResponse<String> get(String path,String cookie) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    UUID event() {
        var id=UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",id,"공연","LIVE",3L,LocalDate.of(2026,11,14));
        return id;
    }
    UUID group(UUID event,String name) { var id=UUID.randomUUID(); jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?, ?, ?, '', true)",id,event,name); return id; }
    UUID member(UUID event,UUID account,String role,UUID group,String name) {
        var id=UUID.randomUUID();
        Long crew=jdbc.queryForObject("SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?",Long.class,event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,?,?,true,false,true,2)""",id,event,account,group,crew,role,name);
        return id;
    }
    String login(UUID account) throws Exception {
        return authCookie(loginResponse(account, "WEB", WEB), "__Secure-sc-access");
    }
    private HttpResponse<String> loginResponse(UUID account, String channel, String origin) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                .header("X-SceneCrew-Client",channel).header("Origin",origin);
        if ("WEB".equals(channel)) request.header("X-SceneCrew-CSRF","1");
        var r=client.send(request
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return r;
    }
    private static String authCookie(HttpResponse<?> login, String name) {
        return login.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith(name+"=")).findFirst().orElseThrow();
    }
    @TestConfiguration(proxyBeanMethods=false) static class Encoder {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}
