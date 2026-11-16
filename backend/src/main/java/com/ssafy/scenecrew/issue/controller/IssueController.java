package com.ssafy.scenecrew.issue.controller;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.global.text.UnicodeText;
import com.ssafy.scenecrew.issue.dto.IssueResponses.IssueView;
import com.ssafy.scenecrew.issue.dto.IssueResponses;
import com.ssafy.scenecrew.issue.service.IssueService.IssueChange;
import com.ssafy.scenecrew.issue.service.IssueService.NewIssue;
import com.ssafy.scenecrew.issue.service.IssueService.NewReport;
import com.ssafy.scenecrew.issue.service.IssueService.SituationCorrection;
import com.ssafy.scenecrew.issue.service.IssueService;

/**
 * #282 보고·이슈 공개 API. 인증과 WEB 쓰기 검사(허용 Origin·JSON)는 PublicAuthFilter가 끝낸 뒤 들어온다.
 * 본문은 계약 스키마 그대로 엄격하게 읽는다: 모르는 필드·잘못된 타입·필수 누락은 400 INVALID_REQUEST.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class IssueController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String DECIMAL = "0|[1-9][0-9]{0,17}";
    private final IssueService issues;

    public IssueController(IssueService issues) { this.issues = issues; }

    /** 055 */
    @GetMapping("/api/v1/events/{e}/issues")
    public ResponseEntity<IssueResponses.IssuePage> list(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        onlyParams(request, Set.of("cursor", "limit"));
        UUID eventId = uuid(e);
        String limit = request.getParameter("limit");
        if (limit != null && !limit.matches("[1-9][0-9]{0,2}")) throw invalid();
        return noStore(issues.list(eventId, account(authentication), request.getParameter("cursor"),
                limit == null ? null : Integer.parseInt(limit)));
    }

    /** 056 */
    @PostMapping(value = "/api/v1/events/{e}/issues", consumes = "application/json")
    public ResponseEntity<IssueResponses.IssueCreated> create(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        onlyParams(request, Set.of());
        UUID eventId = uuid(e);
        object(body, Set.of("workGroupId", "zoneId", "taskId", "relatedIssueId", "report", "safetyObservationId"),
                Set.of("zoneId", "report"));
        JsonNode report = body.get("report");
        object(report, Set.of("text", "quickTemplateKey", "audioId", "kind"), Set.of("text", "kind"));
        if (!"INITIAL".equals(textValue(report.get("kind")))) throw invalid();
        var input = new NewIssue(uuidField(body.get("zoneId"), false), optionalUuid(body, "workGroupId", false),
                optionalUuid(body, "taskId", true), optionalUuid(body, "relatedIssueId", true),
                optionalUuid(body, "safetyObservationId", false), reportText(report.get("text")),
                optionalText(report, "quickTemplateKey"), optionalUuid(report, "audioId", true));
        return respond(issues.create(eventId, account(authentication), key(request), input));
    }

    /** 057 */
    @GetMapping("/api/v1/issues/{i}")
    public ResponseEntity<IssueView> get(@PathVariable("i") String i, HttpServletRequest request,
            Authentication authentication) {
        onlyParams(request, Set.of());
        return noStore(issues.get(uuid(i), account(authentication)));
    }

    /** 058 */
    @PatchMapping(value = "/api/v1/issues/{i}", consumes = "application/json")
    public ResponseEntity<IssueView> change(@PathVariable("i") String i, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        onlyParams(request, Set.of());
        UUID issueId = uuid(i);
        object(body, Set.of("title", "status", "expectedEventRevision"), Set.of("expectedEventRevision"));
        String title = null;
        if (body.has("title")) {
            title = textValue(body.get("title"));
            if (title == null) throw invalid();
            title = UnicodeText.strip(title);
            if (title.isEmpty() || title.codePointCount(0, title.length()) > 50) throw invalid();
        }
        String status = optionalEnum(body, "status", Set.of("RECEIVED", "RESPONDING", "RESOLUTION_REPORTED", "CLOSED"));
        var change = new IssueChange(title, status, revision(body.get("expectedEventRevision")));
        return noStore(issues.change(issueId, account(authentication), change));
    }

    /** 운영 상황 6종(V9-D03). 미분류는 primaryType=null이다(V9-D48). */
    private static final Set<String> SITUATIONS = Set.of("SCHEDULE_DELAY", "STAFF_GAP", "TASK_CONFLICT",
            "EQUIPMENT_ANOMALY", "PERFORMANCE_ORDER_CHANGE", "AUDIENCE_SAFETY");
    /** 정정 사유 상한. 일반 텍스트 상한(1000 코드포인트, P08)을 따른다. */
    private static final int REASON_MAX = 1000;

    /** EXT19 총괄 운영 상황 확인·정정(V9-D48). 응답은 영수증이고 상세는 이슈 조회로 다시 읽는다. */
    @PatchMapping(value = "/api/v1/issues/{i}/situation", consumes = "application/json")
    public ResponseEntity<IssueResponses.SituationReceipt> correctSituation(@PathVariable("i") String i,
            @RequestBody JsonNode body, HttpServletRequest request, Authentication authentication) {
        onlyParams(request, Set.of());
        UUID issueId = uuid(i);
        object(body, Set.of("expectedEventRevision", "primaryType", "relatedTypes", "reason"),
                Set.of("expectedEventRevision", "primaryType", "relatedTypes", "reason"));
        JsonNode primaryNode = body.get("primaryType");
        String primary = null;
        if (!primaryNode.isNull()) {
            primary = textValue(primaryNode);
            if (primary == null || !SITUATIONS.contains(primary)) throw invalid();
        }
        JsonNode relatedNode = body.get("relatedTypes");
        if (!relatedNode.isArray()) throw invalid();
        List<String> related = new java.util.ArrayList<>();
        for (JsonNode item : relatedNode) {
            String type = textValue(item);
            if (type == null || !SITUATIONS.contains(type) || type.equals(primary) || related.contains(type))
                throw invalid();
            related.add(type);
        }
        String reason = textValue(body.get("reason"));
        if (reason == null) throw invalid();
        reason = UnicodeText.strip(reason);
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > REASON_MAX) throw invalid();
        var input = new SituationCorrection(primary, List.copyOf(related), reason,
                revision(body.get("expectedEventRevision")));
        return respond(issues.correctSituation(issueId, account(authentication), key(request), input));
    }

    /** 059 */
    @PostMapping(value = "/api/v1/issues/{i}/reports", consumes = "application/json")
    public ResponseEntity<IssueResponses.ReportCreated> addReport(@PathVariable("i") String i, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        onlyParams(request, Set.of());
        UUID issueId = uuid(i);
        object(body, Set.of("text", "quickTemplateKey", "audioId", "kind"), Set.of("text", "kind"));
        String kind = textValue(body.get("kind"));
        if (!"FOLLOWUP".equals(kind) && !"RESOLUTION".equals(kind)) throw invalid();
        var input = new NewReport(kind, reportText(body.get("text")), optionalText(body, "quickTemplateKey"),
                optionalUuid(body, "audioId", true));
        return respond(issues.addReport(issueId, account(authentication), key(request), input));
    }

    /** 062 */
    @PostMapping(value = "/api/v1/reports/{r}/analyze", consumes = "application/json")
    public ResponseEntity<IssueResponses.JobAccepted> analyze(@PathVariable("r") String r, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        onlyParams(request, Set.of());
        UUID reportId = uuid(r);
        object(body, Set.of("expectedEventRevision"), Set.of("expectedEventRevision"));
        return respond(issues.analyze(reportId, account(authentication), key(request),
                revision(body.get("expectedEventRevision"))));
    }

    // ---- 입력 검사 -------------------------------------------------------------------------------------------

    private static <T> ResponseEntity<T> respond(IdempotentResult<T> result) {
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    /** Idempotency-Key는 한 번만, UUID 형식(형식 검사는 IdempotencyRequest) */
    private static String key(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key가 필요합니다.");
        return values.getFirst();
    }

    private static void object(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid();
        for (String name : node.propertyNames()) if (!allowed.contains(name)) throw invalid();
        for (String name : required) if (!node.has(name)) throw invalid();
    }

    /** 보고 원문: 1~1000자(코드포인트), 공백만은 안 된다. 원문은 그대로 저장한다. */
    private static String reportText(JsonNode node) {
        String text = textValue(node);
        if (text == null || UnicodeText.isBlank(text) || text.codePointCount(0, text.length()) > 1000) throw invalid();
        return text;
    }

    private static String optionalText(JsonNode body, String field) {
        if (!body.has(field) || body.get(field).isNull()) return null;
        String value = textValue(body.get(field));
        if (value == null) throw invalid();
        return value;
    }

    private static String optionalEnum(JsonNode body, String field, Set<String> values) {
        if (!body.has(field)) return null;
        String value = textValue(body.get(field));
        if (value == null || !values.contains(value)) throw invalid();
        return value;
    }

    private static UUID optionalUuid(JsonNode body, String field, boolean nullable) {
        if (!body.has(field)) return null;
        return uuidField(body.get(field), nullable);
    }

    private static UUID uuidField(JsonNode node, boolean nullable) {
        if (node.isNull() && nullable) return null;
        String value = textValue(node);
        if (value == null) throw invalid();
        return uuid(value);
    }

    /** DecimalBigInt: 숫자가 아니라 정규 10진 문자열 */
    private static long revision(JsonNode node) {
        String value = textValue(node);
        if (value == null || !value.matches(DECIMAL)) throw invalid();
        return Long.parseLong(value);
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static UUID uuid(String value) {
        if (!value.matches(UUID_FORMAT)) throw invalid();
        return UUID.fromString(value);
    }

    private static void onlyParams(HttpServletRequest request, Set<String> allowed) {
        for (var e : request.getParameterMap().entrySet())
            if (!allowed.contains(e.getKey()) || e.getValue().length != 1) throw invalid();
    }

    private static UUID account(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal.accountId();
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}
