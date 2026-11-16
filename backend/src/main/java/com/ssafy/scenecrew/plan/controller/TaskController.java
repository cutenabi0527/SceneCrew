package com.ssafy.scenecrew.plan.controller;

import java.util.UUID;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.plan.service.InitialAssignmentService.TaskView;
import com.ssafy.scenecrew.plan.service.TaskReadService;
import com.ssafy.scenecrew.plan.service.TaskCursor;
import com.ssafy.scenecrew.plan.service.TaskExecutionService;
import com.ssafy.scenecrew.plan.service.TaskExecutionService.Command;
import com.ssafy.scenecrew.plan.service.TaskExecutionService.Check;

/** 050~054 manual MVP. Completion-trigger production is deferred to #267. */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class TaskController {
    private final TaskReadService tasks;
    private final TaskExecutionService execution;
    private final TaskCursor cursor;

    public TaskController(TaskReadService tasks, TaskExecutionService execution, TaskCursor cursor) {
        this.tasks = tasks; this.execution = execution; this.cursor = cursor;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
    static class CursorConfig {
        @Bean TaskCursor taskCursor(@Value("${scenecrew.auth.signing-key-base64}") String key) {
            return new TaskCursor(Base64.getDecoder().decode(key));
        }
    }

    @GetMapping("/api/v1/events/{e}/tasks")
    public ResponseEntity<TaskReadService.TaskPage> list(@PathVariable("e") String eventId, HttpServletRequest request,
            Authentication authentication) {
        for (var entry : request.getParameterMap().entrySet())
            if (!Set.of("cursor", "limit").contains(entry.getKey()) || entry.getValue().length != 1) throw invalid();
        String limit = request.getParameter("limit");
        if (limit != null && !limit.matches("[1-9][0-9]{0,2}")) throw invalid();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(tasks.list(uuid(eventId),
                principal(authentication).accountId(), request.getParameter("cursor"), limit == null ? null : Integer.valueOf(limit), cursor));
    }

    @GetMapping("/api/v1/tasks/{t}")
    public ResponseEntity<TaskView> get(@PathVariable("t") String taskId, HttpServletRequest request,
            Authentication authentication) {
        if (!request.getParameterMap().isEmpty()) throw invalid();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(tasks.get(uuid(taskId), principal(authentication).accountId()));
    }

    @PostMapping(value = "/api/v1/tasks/{t}/start", consumes = "application/json")
    public ResponseEntity<TaskExecutionService.Result> start(@PathVariable("t") String taskId, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        return execute(taskId, body, request, authentication, Command.START);
    }
    @PostMapping(value = "/api/v1/tasks/{t}/complete", consumes = "application/json")
    public ResponseEntity<TaskExecutionService.Result> complete(@PathVariable("t") String taskId, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        return execute(taskId, body, request, authentication, Command.COMPLETE);
    }
    @PatchMapping(value = "/api/v1/tasks/{t}/checklist", consumes = "application/json")
    public ResponseEntity<TaskExecutionService.Result> checklist(@PathVariable("t") String taskId, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        return execute(taskId, body, request, authentication, Command.CHECKLIST);
    }

    private ResponseEntity<TaskExecutionService.Result> execute(String taskId, JsonNode body, HttpServletRequest request,
            Authentication authentication, Command command) {
        var allowed = command == Command.CHECKLIST ? Set.of("expectedEventRevision", "checks") : Set.of("expectedEventRevision");
        if (!request.getParameterMap().isEmpty() || !body.isObject() || !body.propertyNames().equals(allowed)) throw invalid();
        var revision = body.get("expectedEventRevision");
        if (!revision.isString() || !revision.asString().matches("0|[1-9][0-9]{0,18}")) throw invalid();
        long expected;
        try { expected = Long.parseLong(revision.asString()); }
        catch (NumberFormatException invalid) { throw invalid(); }
        List<Check> checks = List.of();
        if (command == Command.CHECKLIST) {
            var array = body.get("checks");
            if (!array.isArray() || array.size() > 6) throw invalid();
            var parsed = new ArrayList<Check>();
            Set<String> keys = new HashSet<>();
            for (var item : array) {
                if (!item.isObject() || !item.propertyNames().equals(Set.of("key", "checked"))
                        || !item.get("key").isString() || !item.get("checked").isBoolean()
                        || !keys.add(item.get("key").asString())) throw invalid();
                parsed.add(new Check(item.get("key").asString(), item.get("checked").asBoolean()));
            }
            checks = parsed;
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(execution.execute(uuid(taskId), principal(authentication), expected, command, checks));
    }
    private static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw invalid();
        return UUID.fromString(value);
    }
    private static SessionAuthenticator.Principal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal;
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}
