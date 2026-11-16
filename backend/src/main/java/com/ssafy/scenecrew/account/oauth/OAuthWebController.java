package com.ssafy.scenecrew.account.oauth;

import java.time.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.controller.AuthTransport;
import com.ssafy.scenecrew.account.dto.LoginResponses;
import com.ssafy.scenecrew.account.service.EmailLoginService;
import com.ssafy.scenecrew.global.error.*;

/** 채널별 소셜 로그인·가입과 WEB 소셜 계정 연결 callback. */
@RestController
@ConditionalOnProperty(prefix="scenecrew.auth",name={"public-enabled","oauth.web-enabled"},havingValue="true")
public class OAuthWebController {
    static final String COOKIE="__Secure-sc-oauth";
    static final String REAUTH="__Secure-sc-reauth";
    private final OAuthLoginService login;
    private final SocialSignupService signup;
    private final OAuthAndroidContinuation android;
    private final OAuthIdentityChanges links;
    private final java.net.URI linkReturn;
    private final RedisOAuthAttempts attempts;
    private final AuthTransport transport;
    private final Clock clock;
    private final Duration ttl;
    public OAuthWebController(OAuthLoginService login, SocialSignupService signup, OAuthIdentityChanges links,
            RedisOAuthAttempts attempts, AuthTransport transport, Clock clock, Environment env) {
        this.login=login; this.signup=signup; this.links=links; this.attempts=attempts;
        this.android=new OAuthAndroidContinuation(login,signup);
        this.transport=transport; this.clock=clock; ttl=OAuthWebConfiguration.stateTtl(env);
        linkReturn=OAuthWebConfiguration.linkReturn(env);
    }
    @GetMapping("/api/v1/auth/oauth/{provider}/authorize")
    public ResponseEntity<AuthorizeResult> authorize(@PathVariable SocialProvider provider,
            @RequestParam String purpose,@RequestParam String returnRoute,HttpServletRequest request) {
        if ("ANDROID".equals(request.getHeader("X-SceneCrew-Client"))) {
            requireAndroidEnabled();
            // Reuse the Android Origin and credential checks used by auth POSTs.
            transport.inspect(request,true);
            query(request,Set.of("purpose","returnRoute","codeChallenge","codeChallengeMethod"));
            var url=login.authorizeAndroid(provider,purpose,returnRoute,request.getParameter("codeChallenge"),
                    request.getParameter("codeChallengeMethod"));
            return ResponseEntity.ok().headers(noStore()).body(new AuthorizeResult(url.toString()));
        }
        transport.inspectOAuthStart(request); query(request,Set.of("purpose","returnRoute"));
        // Validate duplicate/malformed temporary cookies before replacing an old attempt.
        cookie(request,false);
        String proof=attempts.secret();
        var url=login.authorize(provider,purpose,"WEB",returnRoute,proof);
        return ResponseEntity.ok().headers(noStore()).header(HttpHeaders.SET_COOKIE,cookie(proof,ttl))
                .body(new AuthorizeResult(url.toString()));
    }
    @GetMapping("/api/v1/auth/oauth/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable SocialProvider provider,@RequestParam String state,
            @RequestParam(required=false) String code,@RequestParam(required=false) String error,HttpServletRequest request) {
        var credentials=transport.inspect(request,false);
        if (!"WEB".equals(credentials.channel())) throw new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
        query(request,Set.of("state","code","error","error_description","error_uri","scope","authuser","prompt","iss"));
        if (login.ownsAndroidState(state)) {
            var location=login.callbackAndroid(provider,state,code,error);
            return ResponseEntity.status(303).headers(noStore()).location(location).build();
        }
        String proof=cookie(request,true);
        if (links.owns(state)) return linkCallback(provider,state,code,error,proof,credentials.access(),request);
        var location=login.callback(provider,state,code,error,proof);
        boolean denied=error!=null;
        return ResponseEntity.status(303).headers(noStore()).location(location)
                .header(HttpHeaders.SET_COOKIE,cookie(denied?"":proof,denied?Duration.ZERO:Duration.ofSeconds(60))).build();
    }
    @PostMapping(value="/api/v1/auth/oauth/exchange",consumes="application/json")
    public ResponseEntity<?> exchange(@RequestBody JsonNode body,HttpServletRequest request) {
        var credentials=transport.inspect(request,true);
        boolean app="ANDROID".equals(credentials.channel());
        if (app) requireAndroidEnabled();
        if (!body.isObject() || !body.path("ticket").isString() || !body.path("action").isString())
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (app && !body.path("codeVerifier").isString()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        String ticket = body.get("ticket").asString();
        String verifier = app ? body.get("codeVerifier").asString() : null;
        return switch (body.get("action").asString()) {
            case "LOGIN" -> {
                if (body.size() != (app ? 3 : 2)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
                String proof = app ? verifier : cookie(request, true);
                var result = app ? android.exchange(ticket,proof) : login.continueLogin(ticket,"WEB",proof,signup);
                yield switch (result) {
                    case OAuthLoginService.Authenticated authenticated -> loginResponse(authenticated.result(),app);
                    case OAuthLoginService.OAuthPending pending -> {
                        var response=ResponseEntity.ok().headers(noStore());
                        if (!app) response.header(HttpHeaders.SET_COOKIE,cookie(proof,OAuthWebConfiguration.SIGNUP_TTL));
                        yield response.body(new SignupPendingResponse(false,pending.signupTicket(),
                                List.of("LOGIN_EXISTING","COMPLETE_SIGNUP"),
                                pending.signupExpiresAt().toString(),pending.provider()));
                    }
                };
            }
            case "COMPLETE_SIGNUP" -> {
                if (body.size() != (app ? 5 : 4) || !body.path("displayName").isString())
                    throw new BusinessException(ErrorCode.INVALID_REQUEST);
                var consent = signupConsent(body.path("consent"));
                String name = body.get("displayName").asString();
                var result = app ? android.complete(ticket,verifier,name,consent)
                        : signup.complete(ticket,"WEB",cookie(request,false),name,consent);
                yield loginResponse(result,app);
            }
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
        };
    }

    private static SocialSignupService.Consent signupConsent(JsonNode consent) {
        if (!consent.isObject() || consent.size() != 4 || !consent.path("service").isBoolean()
                || !consent.path("voice").isBoolean() || !consent.path("push").isBoolean()
                || !consent.path("externalAi").isBoolean())
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return new SocialSignupService.Consent(consent.get("service").asBoolean(), consent.get("voice").asBoolean(),
                consent.get("push").asBoolean(), consent.get("externalAi").asBoolean());
    }

    private record SignupPendingResponse(boolean signupCompleted, String signupTicket, List<String> availableActions,
            String signupExpiresAt, SocialProvider provider) {
        @Override public String toString() { return "SignupPendingResponse[redacted]"; }
    }

    private void requireAndroidEnabled() {
        if (!login.androidEnabled()) throw new BusinessException(ErrorCode.INVALID_AUTH_CHANNEL);
    }

    private ResponseEntity<?> loginResponse(EmailLoginService.Result result, boolean app) {
        if (app) {
            var response=LoginResponses.android(result);
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .header("Referrer-Policy","no-referrer").body(response.getBody());
        }
        var response=LoginResponses.web(result,clock);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .header(HttpHeaders.SET_COOKIE,cookie("",Duration.ZERO)).header("Referrer-Policy","no-referrer").body(response.getBody());
    }
    /**
     * #227 연결 완료. 브라우저가 돌아오는 화면이라 결과는 JSON 오류 대신 내 정보 화면의 고정 결과값으로 알린다.
     * 성공하면 재확인 증명을 지워 같은 증명으로 다시 연결하지 못하게 한다.
     */
    private ResponseEntity<Void> linkCallback(SocialProvider provider,String state,String code,String error,String browserProof,
            String access,HttpServletRequest request) {
        String result;
        String reauth=named(request,REAUTH,false);
        try {
            if ((code==null)==(error==null)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
            if (error!=null) {
                if (!"access_denied".equals(error)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
                links.cancel(provider,state); result="cancelled";
            } else {
                links.complete(access,reauth,provider,state,code,browserProof);
                result="linked";
            }
        } catch (IdentityChangeException e) {
            result=switch (e.reason()) {
                case REAUTHENTICATION_REQUIRED -> "reauth_required";
                case PROVIDER_ALREADY_LINKED -> "provider_linked";
                case IDENTITY_ALREADY_LINKED -> "identity_linked";
                default -> "failed";
            };
        } catch (BusinessException e) {
            result=e.getErrorCode()==ErrorCode.UNAUTHENTICATED?"unauthenticated":"failed";
        }
        var response=ResponseEntity.status(303).headers(noStore())
                .location(java.net.URI.create(linkReturn+"?link="+provider+"&result="+result))
                .header(HttpHeaders.SET_COOKIE,cookie("",Duration.ZERO));
        if ("linked".equals(result)) response.header(HttpHeaders.SET_COOKIE,reauthCookie("",Duration.ZERO));
        return response.build();
    }
    static String reauthCookie(String proof,Duration ttl) {
        return ResponseCookie.from(REAUTH,proof).path("/api/v1").secure(true).httpOnly(true)
                .sameSite("Lax").maxAge(ttl).build().toString();
    }
    private static void query(HttpServletRequest r,Set<String> allowed) {
        for (var e:r.getParameterMap().entrySet())
            if (!allowed.contains(e.getKey()) || e.getValue().length!=1) throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
    private static HttpHeaders noStore() {
        var headers=new HttpHeaders(); headers.setCacheControl("no-store"); headers.set("Referrer-Policy","no-referrer"); return headers;
    }
    static String cookie(String proof,Duration ttl) {
        return ResponseCookie.from(COOKIE,proof).path("/api/v1/auth/oauth").secure(true).httpOnly(true)
                .sameSite("Lax").maxAge(ttl).build().toString();
    }
    private static String cookie(HttpServletRequest r,boolean required) { return named(r,COOKIE,required); }
    /** 이름이 같은 Cookie가 둘이거나 값 형식이 다르면 거절한다. */
    static String named(HttpServletRequest r,String name,boolean required) {
        String found=null;
        for (String header:Collections.list(r.getHeaders("Cookie"))) for (String pair:header.split(";",-1)) {
            int at=pair.indexOf('=');
            if (at>0 && name.equals(pair.substring(0,at).trim())) {
                if (found!=null) throw new BusinessException(ErrorCode.DUPLICATE_AUTH_COOKIE);
                found=pair.substring(at+1).trim();
                if (!found.matches("[A-Za-z0-9_-]{43}")) throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
        if (required && found==null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return found;
    }
    public record AuthorizeResult(String authorizationUrl) { }
}
