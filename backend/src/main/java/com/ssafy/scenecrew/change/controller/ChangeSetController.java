package com.ssafy.scenecrew.change.controller;

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
import com.ssafy.scenecrew.change.service.ChangeSetService;
import com.ssafy.scenecrew.change.service.ChangeSetService.ChangeSetEdit;
import com.ssafy.scenecrew.change.service.ChangeSetService.NewChangeSet;
import com.ssafy.scenecrew.change.service.Operations;
import com.ssafy.scenecrew.change.dto.ChangeResponses.AcknowledgmentPage;
import com.ssafy.scenecrew.change.dto.ChangeResponses.AcknowledgmentView;
import com.ssafy.scenecrew.change.dto.ChangeResponses.ChangeSetPage;
import com.ssafy.scenecrew.change.dto.ChangeResponses.ChangeSetView;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.safety.dto.SafetySupportRequests;

/**
 * #283 변경안 065~071·근거 기록(R2)과 변경 확인 076·077. 인증과 WEB 쓰기 검사는 PublicAuthFilter가 끝낸다.
 * 본문은 계약 스키마대로 엄격하게 읽는다(모르는 필드·잘못된 타입·필수 누락 400).
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class ChangeSetController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String DECIMAL = "0|[1-9][0-9]{0,17}";
    private final ChangeSetService changes;

    public ChangeSetController(ChangeSetService changes) { this.changes = changes; }

    /** 065 */
    @GetMapping("/api/v1/events/{e}/change-sets")
    public ResponseEntity<ChangeSetPage> list(@PathVariable("e") String e, HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of("cursor", "limit"));
        UUID eventId = uuid(e);
        return noStore(changes.list(eventId, account(auth), request.getParameter("cursor"), limit(request)));
    }

    /** 066 */
    @PostMapping(value = "/api/v1/events/{e}/change-sets", consumes = "application/json")
    public ResponseEntity<ChangeSetView> create(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of());
        UUID eventId = uuid(e);
        if (body != null && body.has("supportSelection")) {
            var fields = Set.of("mode", "expectedEventRevision", "supportSelection");
            object(body, fields, fields);
            if (!"MANUAL".equals(text(body.get("mode")))) throw invalid();
            long expected = StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision");
            var selection = SafetySupportRequests.selection(eventId, expected, body.get("supportSelection"));
            return respond(changes.createSupport(account(auth), key(request), selection));
        }
        object(body, Set.of("mode", "operations", "sourceJobId", "issueId", "rationale", "expectedEventRevision",
                "simulationRefs"), Set.of("mode", "operations"));
        String mode = text(body.get("mode"));
        if (!"MANUAL".equals(mode) && !"AI".equals(mode)) throw invalid();
        var input = new NewChangeSet(mode, Operations.check(body.get("operations")), optionalUuid(body, "sourceJobId"),
                optionalUuid(body, "issueId"), body.has("rationale") ? requiredText(body.get("rationale")) : "",
                body.has("expectedEventRevision") ? revision(body.get("expectedEventRevision")) : null,
                Operations.simulationRefs(body.get("simulationRefs")));
        return respond(changes.create(eventId, account(auth), key(request), input));
    }

    /** 067 */
    @GetMapping("/api/v1/change-sets/{c}")
    public ResponseEntity<ChangeSetView> get(@PathVariable("c") String c, HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of());
        return noStore(changes.get(uuid(c), account(auth)));
    }

    /** R2 과거 revision 근거 */
    @GetMapping("/api/v1/change-sets/{c}/evidence/{revision}")
    public ResponseEntity<JsonNode> evidence(@PathVariable("c") String c, @PathVariable("revision") String revision,
            HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of());
        UUID id = uuid(c);
        if (!revision.matches(DECIMAL)) throw invalid();
        return noStore(changes.evidence(id, Long.parseLong(revision), account(auth)));
    }

    /** 068. 생략은 유지, null은 거절. 두 기대 revision 외에 바꿀 항목이 하나 이상 있어야 한다. */
    @PatchMapping(value = "/api/v1/change-sets/{c}", consumes = "application/json")
    public ResponseEntity<ChangeSetView> edit(@PathVariable("c") String c, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of());
        UUID id = uuid(c);
        object(body, Set.of("operations", "rationale", "expectedChangeSetRevision", "expectedEventRevision", "evidenceRefs",
                "simulationRefs"), Set.of("expectedChangeSetRevision", "expectedEventRevision"));
        if (body.size() < 3) throw invalid();
        for (String name : body.propertyNames()) if (body.get(name).isNull()) throw invalid();
        var edit = new ChangeSetEdit(body.has("operations") ? Operations.check(body.get("operations")) : null,
                body.has("rationale") ? requiredText(body.get("rationale")) : null,
                body.has("evidenceRefs") ? Operations.evidenceRefs(body.get("evidenceRefs")) : null,
                body.has("simulationRefs") ? Operations.simulationRefs(body.get("simulationRefs")) : null,
                revision(body.get("expectedChangeSetRevision")), revision(body.get("expectedEventRevision")));
        return noStore(changes.edit(id, account(auth), edit));
    }

    /** 069 */
    @PostMapping(value = "/api/v1/change-sets/{c}/preview", consumes = "application/json")
    public ResponseEntity<ChangeSetView> preview(@PathVariable("c") String c, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of());
        UUID id = uuid(c);
        object(body, Set.of("expectedEventRevision", "expectedChangeSetRevision"),
                Set.of("expectedEventRevision", "expectedChangeSetRevision"));
        return noStore(changes.preview(id, account(auth), revision(body.get("expectedChangeSetRevision")),
                revision(body.get("expectedEventRevision"))));
    }

    /** 070 */
    @PostMapping(value = "/api/v1/change-sets/{c}/apply", consumes = "application/json")
    public ResponseEntity<ChangeSetView> apply(@PathVariable("c") String c, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of());
        UUID id = uuid(c);
        object(body, Set.of("expectedEventRevision", "expectedChangeSetRevision", "previewHash"),
                Set.of("expectedEventRevision", "expectedChangeSetRevision", "previewHash"));
        return respond(changes.apply(id, account(auth), key(request), revision(body.get("expectedChangeSetRevision")),
                revision(body.get("expectedEventRevision")), requiredText(body.get("previewHash"))));
    }

    /** 071 */
    @PostMapping(value = "/api/v1/change-sets/{c}/discard", consumes = "application/json")
    public ResponseEntity<ChangeSetView> discard(@PathVariable("c") String c, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of());
        UUID id = uuid(c);
        object(body, Set.of("expectedChangeSetRevision"), Set.of("expectedChangeSetRevision"));
        return noStore(changes.discard(id, account(auth), revision(body.get("expectedChangeSetRevision"))));
    }

    /** 076 */
    @GetMapping("/api/v1/events/{e}/my-changes")
    public ResponseEntity<AcknowledgmentPage> myChanges(@PathVariable("e") String e, HttpServletRequest request,
            Authentication auth) {
        onlyParams(request, Set.of("cursor", "limit"));
        UUID eventId = uuid(e);
        return noStore(changes.myChanges(eventId, account(auth), request.getParameter("cursor"), limit(request)));
    }

    /** 077 */
    @PostMapping(value = "/api/v1/change-acknowledgments/{a}/ack", consumes = "application/json")
    public ResponseEntity<AcknowledgmentView> acknowledge(@PathVariable("a") String a, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of());
        UUID id = uuid(a);
        object(body, Set.of("targetsHash"), Set.of("targetsHash"));
        return noStore(changes.acknowledge(id, account(auth), requiredText(body.get("targetsHash"))));
    }

    // ---- 입력 검사 -------------------------------------------------------------------------------------------

    private static <T> ResponseEntity<T> respond(IdempotentResult<T> result) {
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    private static String key(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key가 필요합니다.");
        return values.getFirst();
    }

    private static Integer limit(HttpServletRequest request) {
        String limit = request.getParameter("limit");
        if (limit == null) return null;
        if (!limit.matches("[1-9][0-9]{0,2}")) throw invalid();
        return Integer.parseInt(limit);
    }

    private static void object(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid();
        for (String name : node.propertyNames()) if (!allowed.contains(name)) throw invalid();
        for (String name : required) if (!node.has(name)) throw invalid();
    }

    private static UUID optionalUuid(JsonNode body, String field) {
        if (!body.has(field) || body.get(field).isNull()) return null;
        String value = text(body.get(field));
        if (value == null) throw invalid();
        return uuid(value);
    }

    private static String requiredText(JsonNode node) {
        String value = text(node);
        if (value == null) throw invalid();
        return value;
    }

    private static long revision(JsonNode node) {
        String value = text(node);
        if (value == null || !value.matches(DECIMAL)) throw invalid();
        return Long.parseLong(value);
    }

    private static String text(JsonNode node) {
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
