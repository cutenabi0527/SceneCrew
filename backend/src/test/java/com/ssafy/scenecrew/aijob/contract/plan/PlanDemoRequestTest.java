package com.ssafy.scenecrew.aijob.contract.plan;

import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.READER;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.json;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ssafy.scenecrew.aijob.contract.AiContractJson;
import com.ssafy.scenecrew.aijob.contract.plan.PlanOutcome.Branch;
import com.ssafy.scenecrew.aijob.pipeline.AiJobInputs;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;

/**
 * S15P31A202-253 대표 시연 행사 조건을 BE의 PlanRequest로 만들어 AI 요청 JSON을 고정한다.
 *
 * 아직 DRAFT(Event·Zone·Program·WorkGroup)를 읽어 요청을 만드는 BE 코드가 없어, 같은 DTO와 직렬화 설정으로
 * 대신 만든다. 요청 생성 코드가 생기면 그 출력과 이 파일을 다시 대조한다.
 * inputHash는 접수(AiJobService.accept)와 같은 AiJobInputs 규칙(키를 재귀 정렬한 JSON의 SHA-256)으로 계산한다.
 *
 * 파일 다시 쓰기: PLAN_DEMO_WRITE=src/test/resources/ai-contract/plan/demo ./gradlew test --tests '*PlanDemoRequestTest'
 * 같은 파일을 ai/plan/examples/demo/에 복사해 flow.run을 실행한다.
 */
class PlanDemoRequestTest {
    private static final JsonMapper MAPPER = AiContractJson.mapper();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static final UUID JOB_ID = UUID.fromString("25300000-0000-4000-8000-000000000001");
    static final UUID STAGE = UUID.fromString("25300000-0000-4000-8000-000000000101");
    static final UUID FRONT = UUID.fromString("25300000-0000-4000-8000-000000000102");
    static final UUID REAR = UUID.fromString("25300000-0000-4000-8000-000000000103");
    static final UUID GATE = UUID.fromString("25300000-0000-4000-8000-000000000104");
    static final UUID SHOW = UUID.fromString("25300000-0000-4000-8000-000000000201");

    /** 2026-11-18(KST) 시연 행사. 준비13:00·개장14:00·종료14:30·정리15:00 KST, 공연14:10~14:15. */
    static PlanRequest.Payload demoPayload(Instant prepStartAt, LocalDate date) {
        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("equipment", "무대에 메인 마이크 1개와 예비 마이크 1개를 둔다.");
        confirmed.put("audienceCamera", "객석 고정 카메라가 공연 중 구역별 관객 분포를 계속 관측한다.");

        PlanRequest.Event event = new PlanRequest.Event(
                "씬크루 시연 콘서트",
                "한 공연팀의 약 5분 공연을 운영하며 장비 이상 대응과 객석 안전 지원을 함께 준비한다.",
                date,
                prepStartAt,
                Instant.parse("2026-11-18T05:00:00Z"),
                Instant.parse("2026-11-18T05:30:00Z"),
                Instant.parse("2026-11-18T06:00:00Z"),
                "멀티캠퍼스 역삼",
                "서울특별시 강남구 테헤란로 212",
                7,
                null,
                null,
                1,
                confirmed);
        List<PlanRequest.Zone> zones = List.of(
                new PlanRequest.Zone(STAGE, "무대", "공연과 마이크·믹서 장비가 있는 구역"),
                new PlanRequest.Zone(FRONT, "무대 앞 객석", "무대 바로 앞 스탠딩 관객 구역"),
                new PlanRequest.Zone(REAR, "후방 객석", "무대에서 먼 관객 구역"),
                new PlanRequest.Zone(GATE, "입장 게이트", "관객 입장 확인 구역"));
        List<PlanRequest.Program> programs = List.of(
                new PlanRequest.Program(SHOW, STAGE, "공연 1팀",
                        Instant.parse("2026-11-18T05:10:00Z"), Instant.parse("2026-11-18T05:15:00Z")));
        List<PlanRequest.WorkGroup> groups = List.of(
                new PlanRequest.WorkGroup(UUID.fromString("25300000-0000-4000-8000-000000000301"), "장비 운영"),
                new PlanRequest.WorkGroup(UUID.fromString("25300000-0000-4000-8000-000000000302"), "장비·시스템 장애 대응"),
                new PlanRequest.WorkGroup(UUID.fromString("25300000-0000-4000-8000-000000000303"), "무대영상"),
                new PlanRequest.WorkGroup(UUID.fromString("25300000-0000-4000-8000-000000000304"), "관객 안전"));
        return new PlanRequest.Payload(event, zones, programs, groups);
    }

    static PlanRequest demoRequest(PlanRequest.Payload payload, int attempt) {
        String inputHash = AiJobInputs.sha256(AiJobInputs.canonicalJson(MAPPER, payload));
        return new PlanRequest(JOB_ID, attempt, "4", "GENERATE_PLAN", inputHash,
                3L, Instant.parse("2026-11-18T03:40:00Z"), payload);
    }

    /** 파일 이름 → 요청. ready: 확인된 조건 그대로, short-prep: 준비 시작을 13:45로(개장 15분 전, AI 선검사 30분 미만), date-mismatch: 날짜만 다음 날로. */
    static Map<String, PlanRequest> demoRequests() {
        Instant prep = Instant.parse("2026-11-18T04:00:00Z");
        LocalDate date = LocalDate.parse("2026-11-18");
        Map<String, PlanRequest> requests = new LinkedHashMap<>();
        requests.put("request-ready.json", demoRequest(demoPayload(prep, date), 1));
        requests.put("request-short-prep.json", demoRequest(demoPayload(Instant.parse("2026-11-18T04:45:00Z"), date), 1));
        requests.put("request-date-mismatch.json", demoRequest(demoPayload(prep, date.plusDays(1)), 1));
        return requests;
    }

    @Test
    void demoRequestsMatchSavedJson() throws IOException {
        String writeDir = System.getenv("PLAN_DEMO_WRITE");
        Map<String, String> texts = new LinkedHashMap<>();
        for (Map.Entry<String, PlanRequest> e : demoRequests().entrySet()) {
            String text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(e.getValue()) + "\n";
            texts.put(e.getKey(), text);
            if (writeDir != null) {
                Path dir = Path.of(writeDir);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(e.getKey()), text, StandardCharsets.UTF_8);
            }
        }
        for (Map.Entry<String, String> e : texts.entrySet()) {
            String text = e.getValue();
            assertThat(MAPPER.readTree(json("demo/" + e.getKey())))
                    .as("%s를 PLAN_DEMO_WRITE로 다시 생성하세요", e.getKey())
                    .isEqualTo(MAPPER.readTree(text));
        }
    }

    @Test
    void demoRequestsPassBeValidationAndReadBack() {
        // 날짜 불일치 요청도 DTO 검사는 통과한다. 준비~정리의 현지 날짜 대조는 AI의 request_issues만 한다.
        for (PlanRequest req : demoRequests().values()) {
            Set<ConstraintViolation<PlanRequest>> violations = VALIDATOR.validate(req);
            assertThat(violations).isEmpty();
            assertThat(MAPPER.readValue(MAPPER.writeValueAsString(req), PlanRequest.class)).isEqualTo(req);
        }
    }

    /** ai/plan flow.run 실행 결과(demo/*)를 BE가 같은 분기로 판정하는지 확인한다. */
    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
            "request-ready.json, ready.json, READY",
            "request-short-prep.json, short-prep.json, NEEDS_INFO",
            "request-date-mismatch.json, date-mismatch.json, FAILED",
            "request-ready.json, timeout.json, FAILED",
            "request-ready.json, gms-ready.json, READY",
    })
    void flowOutputsFollowBeBranches(String requestFile, String responseFile, Branch branch) {
        PlanOutcome outcome = READER.read(request("demo/" + requestFile), json("demo/" + responseFile));

        assertThat(outcome.issues()).isEmpty();
        assertThat(outcome.branch()).isEqualTo(branch);
        assertThat(outcome.canApplyToDraft()).isEqualTo(branch == Branch.READY);
    }
}
