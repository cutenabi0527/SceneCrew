package com.ssafy.scenecrew.account.oauth;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.global.error.*;

/** Fixed provider endpoints in production; package-private injection supports local HTTP fixtures.
 * No redirects or retries: a code redemption with an unknown outcome must not be replayed.
 */
public final class HttpOAuthProviderClient implements OAuthProviderClient {
    private final SocialProvider provider;
    private final OAuthClientSettings settings;
    private final URI authorize, token, profile;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    public HttpOAuthProviderClient(SocialProvider provider, OAuthClientSettings settings) {
        this(provider, settings, switch (provider) {
            case GOOGLE -> URI.create("https://accounts.google.com/o/oauth2/v2/auth");
            case KAKAO -> URI.create("https://kauth.kakao.com/oauth/authorize");
            case NAVER -> URI.create("https://nid.naver.com/oauth2/authorize");
        }, switch (provider) {
            case GOOGLE -> URI.create("https://oauth2.googleapis.com/token");
            case KAKAO -> URI.create("https://kauth.kakao.com/oauth/token");
            case NAVER -> URI.create("https://nid.naver.com/oauth2/token");
        }, switch (provider) {
            case GOOGLE -> URI.create("https://openidconnect.googleapis.com/v1/userinfo");
            case KAKAO -> URI.create("https://kapi.kakao.com/v2/user/me");
            case NAVER -> URI.create("https://openapi.naver.com/v1/nid/me");
        });
    }
    HttpOAuthProviderClient(SocialProvider provider, OAuthClientSettings settings, URI authorize, URI token, URI profile) {
        this.provider=provider; this.settings=settings; this.authorize=authorize; this.token=token; this.profile=profile;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public SocialProvider provider() { return provider; }
    public URI authorizationUrl(String state, String codeVerifier) {
        settings.requireConfigured(provider);
        var fields = new LinkedHashMap<String,String>();
        fields.put("response_type","code"); fields.put("client_id",settings.clientId());
        fields.put("redirect_uri",settings.redirectUri().toString()); fields.put("state",state);
        fields.put("code_challenge",challenge(codeVerifier)); fields.put("code_challenge_method","S256");
        if (!settings.scope().isBlank()) fields.put("scope",settings.scope());
        return URI.create(authorize + "?" + form(fields));
    }
    public String verifiedSubject(String code, String state, String codeVerifier) {
        settings.requireConfigured(provider);
        var fields = new LinkedHashMap<String,String>();
        fields.put("grant_type","authorization_code"); fields.put("client_id",settings.clientId());
        fields.put("code",code); fields.put("redirect_uri",settings.redirectUri().toString());
        challenge(codeVerifier); fields.put("code_verifier",codeVerifier);
        if (settings.clientSecret()!=null && !settings.clientSecret().isBlank()) fields.put("client_secret",settings.clientSecret());
        if (provider==SocialProvider.NAVER) fields.put("state",state);
        var tokens=send(HttpRequest.newBuilder(token).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))));
        String access=text(tokens,"access_token");
        // RFC 6750 Bearer credentials must be valid before building the profile request header.
        if (access==null || !access.matches("[A-Za-z0-9._~+/-]+[=]*")
                || tokens.has("error") || !"Bearer".equalsIgnoreCase(text(tokens,"token_type"))) denied();
        var user=send(HttpRequest.newBuilder(profile).header("Authorization","Bearer "+access).GET());
        String subject=switch (provider) {
            case GOOGLE -> text(user,"sub");
            case KAKAO -> kakaoSubject(user);
            case NAVER -> "00".equals(text(user,"resultcode")) && user.has("response") ? text(user.get("response"),"id") : null;
        };
        if (subject==null || subject.length()>512 || subject.chars().anyMatch(Character::isISOControl)) denied();
        return subject;
    }
    private JsonNode send(HttpRequest.Builder request) {
        var response=http.sendAsync(request.timeout(Duration.ofSeconds(5)).header("Accept","application/json").build(),
                info->new LimitedBody());
        try {
            var received=response.get(5,java.util.concurrent.TimeUnit.SECONDS);
            if (received.statusCode()==429 || received.statusCode()>=500) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            if (received.statusCode()!=200) denied();
            var result=json.readTree(received.body());
            if (result==null || !result.isObject()) denied();
            return result;
        } catch (InterruptedException e) {
            response.cancel(true); Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (BusinessException e) { throw e;
        } catch (Exception e) {
            response.cancel(true); throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
    /** Bounds the entire HTTP exchange, including a slow or oversized response body. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body=HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private long size;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return body.getBody(); }
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            this.subscription=subscription; body.onSubscribe(subscription);
        }
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer:buffers) size+=buffer.remaining();
            if (size>65536) {
                subscription.cancel(); body.onError(new java.io.IOException("OAuth response limit exceeded")); return;
            }
            body.onNext(buffers);
        }
        public void onError(Throwable error) { body.onError(error); }
        public void onComplete() { body.onComplete(); }
    }
    @Override public void close() { http.close(); }
    private static String kakaoSubject(JsonNode user) {
        if (!user.has("id") || !user.get("id").isIntegralNumber()) return null;
        try {
            long id=Long.parseLong(user.get("id").toString());
            return id>0?Long.toString(id):null;
        } catch (NumberFormatException invalid) { return null; }
    }
    private static String text(JsonNode n,String field) {
        return n.has(field) && n.get(field).isString() && !n.get(field).asString().isBlank() ? n.get(field).asString() : null;
    }
    private static void denied() { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    private static String form(Map<String,String> fields) {
        return fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }
    static String challenge(String verifier) {
        if (verifier==null || !verifier.matches("[A-Za-z0-9_-]{43}")) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                HexFormat.of().parseHex(RedisOAuthAttempts.hash(verifier)));
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
}
