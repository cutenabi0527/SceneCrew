package com.ssafy.scenecrew.aijob.contract.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ssafy.scenecrew.aijob.contract.AiContractJson;
import com.ssafy.scenecrew.aijob.contract.ContractIssue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI의 최초 계획 응답(JSON 원문)을 BE가 보낸 요청과 대조해 READY / NEEDS_INFO / FAILED / INVALID로 나눈다.
 *
 * 1. 엄격한 JSON 읽기(AiContractJson): 모르는 필드·타입 변환 거절
 * 2. 형식 검사(Bean Validation): 필수·길이·패턴·상한
 * 3. 식별 대조: jobId·attempt·requestSchemaVersion·inputHash·contextRevision이 요청과 같은지
 * 4. 내용 재검사(PlanResultChecks): 참조·시간·묶음·분기 규칙, 결과6이면 평면도(구역 이름·범위)
 * 하나라도 어기면 INVALID이며 결과를 저장하지 않는다.
 */
@Component
public class PlanResponseReader {
    private final JsonMapper mapper = AiContractJson.mapper();
    private final Validator validator;

    public PlanResponseReader(Validator validator) {
        this.validator = validator;
    }

    public PlanOutcome read(PlanRequest request, String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException e) {
            return invalid("response", "JSON으로 읽을 수 없습니다.");
        }
        if (root == null || !root.isObject()) {
            return invalid("response", "응답은 JSON 객체여야 합니다.");
        }
        return root.has("error") ? readFailure(request, root) : readSuccess(request, root);
    }

    private PlanOutcome readSuccess(PlanRequest request, JsonNode root) {
        PlanSuccess res;
        try {
            res = mapper.treeToValue(root, PlanSuccess.class);
        } catch (JacksonException e) {
            return invalid("response", "성공 응답 형식이 계약과 다릅니다: " + e.getOriginalMessage());
        }
        List<ContractIssue> issues = validate(res);
        if (!issues.isEmpty()) {
            return PlanOutcome.invalid(issues);
        }
        issues = new ArrayList<>(PlanResultChecks.echo(request, res.jobId(), res.attempt(),
                res.requestSchemaVersion(), res.inputHash(), res.contextRevision()));
        issues.addAll(PlanResultChecks.success(request, res.result()));
        if (issues.isEmpty()) {
            issues.addAll(PlanResultChecks.venue(request, res.resultSchemaVersion(), res.result()));
            issues.addAll(PlanResultChecks.devices(res.result()));
        }
        return issues.isEmpty() ? PlanOutcome.of(res) : PlanOutcome.invalid(issues);
    }

    private PlanOutcome readFailure(PlanRequest request, JsonNode root) {
        PlanFailure res;
        try {
            res = mapper.treeToValue(root, PlanFailure.class);
        } catch (JacksonException e) {
            return invalid("response", "실패 응답 형식이 계약과 다릅니다: " + e.getOriginalMessage());
        }
        List<ContractIssue> issues = validate(res);
        if (!issues.isEmpty()) {
            return PlanOutcome.invalid(issues);
        }
        issues = new ArrayList<>(PlanResultChecks.failure(res));
        // BE는 항상 유효한 요청만 보내므로 접수 전 실패(식별 null)도 요청과 맞지 않는 응답으로 본다.
        issues.addAll(PlanResultChecks.echo(request, res.jobId(), res.attempt(), res.requestSchemaVersion(),
                res.inputHash(), res.contextRevision()));
        return issues.isEmpty() ? PlanOutcome.of(res) : PlanOutcome.invalid(issues);
    }

    private <T> List<ContractIssue> validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        return violations.stream()
                .map(v -> new ContractIssue("response." + v.getPropertyPath(), "SCHEMA", v.getMessage()))
                .sorted(Comparator.comparing(ContractIssue::path))
                .toList();
    }

    private static PlanOutcome invalid(String path, String message) {
        return PlanOutcome.invalid(List.of(new ContractIssue(path, "SCHEMA", message)));
    }
}
