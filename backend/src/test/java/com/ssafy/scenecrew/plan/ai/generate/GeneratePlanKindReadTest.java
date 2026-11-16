package com.ssafy.scenecrew.plan.ai.generate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.domain.AiJobState;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import static org.assertj.core.api.Assertions.*;

/**
 * #280 GENERATE_PLAN 응답 읽기. ai/plan 예시(test resources/ai-contract/plan)를 그대로 쓰고,
 * 저장 결과가 063 공개 모양(PlanPublicResult)이며 plan이 028 저장 본문 모양인지 본다.
 */
class GeneratePlanKindReadTest {
    final JsonMapper json = JsonMapper.builder().build();
    final GeneratePlanKind kind = new GeneratePlanKind(null, Validation.buildDefaultValidatorFactory().getValidator(),
            new com.ssafy.scenecrew.document.ai.extraction.ExtractionProperties(""));

    @Test void readyBecomesEditableDraftPlanWithoutInternalRecords() {
        AiJobOutcome out = kind.read(envelope(), response(fixture("success-ready.json")));
        assertThat(out.state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(out.resultSchemaVersion()).isEqualTo("5");
        JsonNode r = json.readTree(out.resultJson());
        assertThat(r.propertyNames()).containsExactly("status", "questions", "plan");   // 결과5에는 venueDraft가 없다
        assertThat(r.get("status").asString()).isEqualTo("READY");
        assertThat(r.get("questions").size()).isZero();
        JsonNode plan = r.get("plan");
        // 028(DraftPlanWrite) 본문의 다섯 배열만. 묶음 요약·가정·협업 기록은 공개하지 않는다.
        assertThat(plan.propertyNames()).containsExactly("workGroups", "zones", "programs", "tasks", "instructions");
        // 새 그룹은 tempId만, WORK_GROUP 안내는 그 그룹 instructions로 옮긴다.
        JsonNode reception = plan.get("workGroups").get(0), stage = plan.get("workGroups").get(1);
        assertThat(reception.propertyNames()).containsExactly("tempId", "name", "instructions");
        assertThat(reception.get("instructions").asString()).isEmpty();
        assertThat(stage.get("tempId").asString()).isEqualTo("tmp-wg-stage");
        assertThat(stage.get("instructions").asString()).contains("예비 HDMI");
        // 기존 구역은 id만 남긴다.
        assertThat(plan.get("zones").get(0).propertyNames()).containsExactly("id", "name", "description");
        JsonNode task = plan.get("tasks").get(0);
        assertThat(task.propertyNames()).containsExactly("tempId", "workGroupId", "bundleKey", "zoneId", "programId",
                "title", "startAt", "endAt", "steps", "checklist");
        assertThat(task.get("programId").isNull()).isTrue();
        assertThat(task.get("startAt").asString()).isEqualTo("2026-11-13T23:00:00Z");
        assertThat(task.get("checklist").get(0).get("required").asBoolean()).isTrue();
        assertThat(plan.get("tasks").size()).isEqualTo(7);
        JsonNode instructions = plan.get("instructions");
        assertThat(instructions.size()).isEqualTo(1);
        assertThat(instructions.get(0).get("scope").asString()).isEqualTo("EVENT");
        assertThat(instructions.get(0).get("workGroupId").isNull()).isTrue();
    }

    @Test void needsInfoReturnsOnlyQuestions() {
        AiJobOutcome out = kind.read(envelope(), response(fixture("success-needs-info.json")));
        assertThat(out.state()).isEqualTo(AiJobState.SUCCEEDED);
        JsonNode r = json.readTree(out.resultJson());
        assertThat(r.get("status").asString()).isEqualTo("NEEDS_INFO");
        assertThat(r.get("plan").isNull()).isTrue();
        assertThat(r.get("questions").get(0).propertyNames()).containsExactly("key", "field", "question");
    }

    @Test void aiFailuresKeepCodeAndRetryRule() {
        AiJobOutcome stale = kind.read(envelope(), response(fixture("failure-context-changed.json")));
        assertThat(stale.state()).isEqualTo(AiJobState.STALE_CONTEXT);
        assertThat(stale.error().code()).isEqualTo("READ_CONTEXT_CHANGED");
        AiJobOutcome unavailable = kind.read(envelope(), response(fixture("failure-unavailable.json")));
        assertThat(unavailable.state()).isEqualTo(AiJobState.FAILED);
        assertThat(unavailable.error().code()).isEqualTo("AI_UNAVAILABLE");
        assertThat(unavailable.retryable()).isTrue();
        AiJobOutcome review = kind.read(envelope(), response(fixture("failure-review-budget.json")));
        assertThat(review.error().code()).isEqualTo("AI_REVIEW_FAILED");
        assertThat(review.retryable()).isFalse();
    }

    @Test void contractViolationsAreNotStored() {
        List<String> bodies = new ArrayList<>();
        ObjectNode otherJob = (ObjectNode) json.readTree(fixture("success-ready.json"));
        otherJob.put("jobId", UUID.randomUUID().toString());
        bodies.add(otherJob.toString());
        ObjectNode unknown = (ObjectNode) json.readTree(fixture("success-ready.json"));
        ((ObjectNode) unknown.get("result")).put("extra", true);
        bodies.add(unknown.toString());
        bodies.add("[]");
        for (String body : bodies) {
            AiJobOutcome out = kind.read(envelope(), response(body));
            assertThat(out.state()).as(body).isEqualTo(AiJobState.FAILED);
            assertThat(out.error().code()).isEqualTo("AI_UNAVAILABLE");
            assertThat(out.error().details()).containsEntry("reason", "INVALID_RESULT");
            assertThat(out.retryable()).isFalse();
            assertThat(out.resultJson()).isNull();
        }
    }

    /** 028과 같은 기준: 이름 길이는 코드포인트, 이름 중복은 공백 제거·대소문자 무시, tempId는 결과 전체에서 한 번 (#280 리뷰) */
    @Test void namesAndTempIdsFollowDraftPlanRules() {
        assertThat(readyWith(r -> group(r, 0).put("name", "😀".repeat(20))).state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(readyWith(r -> group(r, 0).put("name", "😀".repeat(21))).state()).isEqualTo(AiJobState.FAILED);
        AiJobOutcome sameName = readyWith(r -> {
            ((ObjectNode) r.get("zones").get(0)).put("name", "Sound");
            ((ObjectNode) r.get("zones").get(1)).put("name", "s o u n d");
        });
        assertThat(sameName.state()).isEqualTo(AiJobState.FAILED);
        AiJobOutcome sameTemp = readyWith(r -> ((ObjectNode) r.get("tasks").get(0)).put("tempId", "tmp-wg-reception"));
        assertThat(sameTemp.state()).isEqualTo(AiJobState.FAILED);
        assertThat(sameTemp.error().code()).isEqualTo("AI_UNAVAILABLE");
    }

    @Test void existingItemsKeepIdAndNewItemsKeepTempIdOnly() {
        JsonNode plan = json.readTree(kind.read(envelope(), response(fixture("success-ready.json"))).resultJson()).get("plan");
        for (String list : List.of("workGroups", "zones", "programs")) {
            for (JsonNode item : plan.get(list)) {
                assertThat(item.has("id") ^ item.has("tempId")).as(list + " " + item).isTrue();
            }
        }
        assertThat(plan.get("zones").get(0).has("id")).isTrue();       // 요청에 있던 구역
        assertThat(plan.get("workGroups").get(0).has("tempId")).isTrue(); // 새 그룹
    }

    /** 관객 수는 signed64다. 32비트를 넘는 값도 요청을 읽고 응답을 대조할 수 있다(#280 리뷰). */
    @Test void visitorCountsBeyond32BitsAreAccepted() {
        AiJobEnvelope base = envelope();
        ObjectNode payload = (ObjectNode) base.payload().deepCopy();
        ((ObjectNode) payload.get("event")).put("expectedVisitors", 3_000_000_000L).put("peakVisitors", 2_147_483_648L);
        AiJobEnvelope big = new AiJobEnvelope(base.jobId(), base.attempt(), base.requestSchemaVersion(), base.kind(),
                base.inputHash(), base.contextRevision(), base.deadlineAt(), null, payload);
        assertThat(kind.read(big, response(fixture("success-ready.json"))).state()).isEqualTo(AiJobState.SUCCEEDED);
    }

    // ---- 결과6 평면도(V9-D54, #391) ----

    static final String DOC = "44444444-4444-4444-8444-444444444444";

    /** floorPlan을 보낸 요청 */
    AiJobEnvelope floorPlanEnvelope() {
        AiJobEnvelope base = envelope();
        ObjectNode payload = (ObjectNode) base.payload().deepCopy();
        payload.putObject("floorPlan").put("documentId", DOC).put("pageNumber", 2).put("mime", "application/pdf")
                .put("sha256", "b".repeat(64)).put("url", "https://example.test/internal/intake-documents/" + DOC);
        return new AiJobEnvelope(base.jobId(), base.attempt(), base.requestSchemaVersion(), base.kind(),
                base.inputHash(), base.contextRevision(), base.deadlineAt(), null, payload);
    }

    /** 결과6: 첫 두 구역을 사각형으로 읽은 평면도 */
    ObjectNode result6(java.util.function.Consumer<ObjectNode> changeLayout) {
        ObjectNode success = (ObjectNode) json.readTree(fixture("success-ready.json"));
        success.put("resultSchemaVersion", "6");
        ObjectNode result = (ObjectNode) success.get("result");
        ObjectNode layout = result.putObject("venueDraft").putObject("layout");
        ((ObjectNode) result.get("venueDraft")).putArray("profiles");
        layout.putObject("source").put("documentId", DOC).put("pageNumber", 2);
        layout.put("width", 1200).put("height", 800);
        ArrayNode zones = layout.putArray("zones");
        zones.addObject().put("name", result.get("zones").get(0).get("name").asString())
                .put("x", 0.1).put("y", 0.2).put("width", 0.3).put("height", 0.4);
        zones.addObject().put("name", result.get("zones").get(1).get("name").asString())
                .put("x", 0.5).put("y", 0.5).put("width", 0.5).put("height", 0.5);
        changeLayout.accept(layout);
        return success;
    }

    @Test void result6LayoutBecomesZonePolygonsOfTheSameCandidate() {
        AiJobOutcome out = kind.read(floorPlanEnvelope(), response(result6(l -> { }).toString()));
        assertThat(out.state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(out.resultSchemaVersion()).isEqualTo("6");
        JsonNode r = json.readTree(out.resultJson());
        assertThat(r.propertyNames()).containsExactly("status", "questions", "plan", "venueDraft");
        JsonNode venue = r.get("venueDraft");
        assertThat(venue.get("profiles").size()).isZero();
        JsonNode layout = venue.get("layout");
        assertThat(layout.propertyNames()).containsExactly("coordinateSystem", "width", "height", "zonePolygons", "source");
        assertThat(layout.get("coordinateSystem").asString()).isEqualTo("normalized-image");
        assertThat(layout.get("width").asDouble()).isEqualTo(1200);
        JsonNode zones = r.get("plan").get("zones");
        JsonNode first = layout.get("zonePolygons").get(0), second = layout.get("zonePolygons").get(1);
        JsonNode zone0 = zones.get(0), zone1 = zones.get(1);
        assertThat(first.get("zoneId").asString()).isEqualTo(zone0.has("id") ? zone0.get("id").asString() : zone0.get("tempId").asString());
        assertThat(second.get("zoneId").asString()).isEqualTo(zone1.has("id") ? zone1.get("id").asString() : zone1.get("tempId").asString());
        JsonNode points = first.get("points");
        assertThat(points.size()).isEqualTo(4);
        assertThat(points.get(0).get("x").asDouble()).isEqualTo(0.1);
        assertThat(points.get(0).get("y").asDouble()).isEqualTo(0.2);
        assertThat(points.get(2).get("x").asDouble()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(points.get(2).get("y").asDouble()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(second.get("points").get(2).get("x").asDouble()).isEqualTo(1.0);     // 오른쪽 끝에 붙은 구역
        assertThat(layout.get("source").get("sourceKind").asString()).isEqualTo("AUTOMATIC_MEASUREMENT");
        assertThat(layout.get("source").get("sourceRef").asString()).isEqualTo("document:" + DOC + "#page=2");
        // AI가 0·1 같은 정수로 적어도 받는다
        AiJobOutcome integers = kind.read(floorPlanEnvelope(), response(result6(l -> ((ObjectNode) l.get("zones").get(0))
                .put("x", 0).put("y", 0).put("width", 1).put("height", 1)).toString()));
        assertThat(integers.state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(json.readTree(integers.resultJson()).get("venueDraft").get("layout").get("zonePolygons").get(0)
                .get("points").get(2).get("x").asDouble()).isEqualTo(1.0);
    }

    @Test void result6WithoutReadableFloorPlanHasNoVenueDraft() {
        ObjectNode noLayout = result6(l -> { });
        ((ObjectNode) noLayout.get("result").get("venueDraft")).putNull("layout");
        JsonNode r = json.readTree(kind.read(floorPlanEnvelope(), response(noLayout.toString())).resultJson());
        assertThat(r.has("venueDraft")).isFalse();
        ObjectNode nullDraft = result6(l -> { });
        ((ObjectNode) nullDraft.get("result")).putNull("venueDraft");
        assertThat(json.readTree(kind.read(floorPlanEnvelope(), response(nullDraft.toString())).resultJson()).has("venueDraft"))
                .isFalse();
        // 기존 결과5도 그대로 받는다
        assertThat(kind.read(floorPlanEnvelope(), response(fixture("success-ready.json"))).resultSchemaVersion()).isEqualTo("5");
    }

    @Test void result6LayoutViolationsAreNotStored() {
        List<String> bodies = new ArrayList<>();
        bodies.add(result6(l -> ((ObjectNode) l.get("zones").get(0)).put("name", "없는 구역")).toString());
        bodies.add(result6(l -> ((ObjectNode) l.get("zones").get(1)).put("name", l.get("zones").get(0).get("name").asString()))
                .toString());
        bodies.add(result6(l -> ((ObjectNode) l.get("zones").get(0)).put("x", 0.8)).toString());          // 0.8+0.3 > 1
        bodies.add(result6(l -> ((ObjectNode) l.get("zones").get(0)).put("width", 0)).toString());
        bodies.add(result6(l -> ((ObjectNode) l.get("zones").get(0)).put("y", -0.1)).toString());
        bodies.add(result6(l -> ((ObjectNode) l.get("zones").get(0)).put("rotation", 3)).toString());
        ObjectNode profiles = result6(l -> { });
        ((ObjectNode) profiles.get("result").get("venueDraft")).putArray("profiles").addObject().put("zoneId", "x");
        bodies.add(profiles.toString());
        bodies.add(result6(l -> ((ObjectNode) l.get("source")).put("pageNumber", 3)).toString());            // 요청과 다른 쪽
        bodies.add(result6(l -> ((ObjectNode) l.get("source")).put("documentId", UUID.randomUUID().toString())).toString());
        bodies.add(result6(l -> l.remove("source")).toString());
        bodies.add(result6(l -> l.remove("width")).toString());
        bodies.add(result6(l -> l.putArray("zones")).toString());                                         // 구역 0개
        ObjectNode equipment = result6(l -> { });
        ((ObjectNode) equipment.get("result")).putObject("equipmentDraft");
        bodies.add(equipment.toString());
        ObjectNode result5WithLayout = result6(l -> { });
        result5WithLayout.put("resultSchemaVersion", "5");
        bodies.add(result5WithLayout.toString());
        for (String body : bodies) {
            AiJobOutcome out = kind.read(floorPlanEnvelope(), response(body));
            assertThat(out.state()).as(body).isEqualTo(AiJobState.FAILED);
            assertThat(out.error().details()).containsEntry("reason", "INVALID_RESULT");
        }
        // 평면도를 보내지 않았는데 평면도 초안이 오면 계약 위반이다
        assertThat(kind.read(envelope(), response(result6(l -> { }).toString())).state()).isEqualTo(AiJobState.FAILED);
    }

    // ---- 카메라·장비 선택 필드와 연결 업무 규칙(V9-D58·D59·D61, #395) ----

    /** 로비 카메라 1대, QLXD4 수신기 1대·믹서 1대, 무대 운영 그룹에 SAFETY 표시 */
    static void devices(ObjectNode r) {
        r.putArray("cameras").addObject().put("name", "객석 카메라 1").put("zoneName", "로비");
        ArrayNode assets = r.putObject("equipmentDraft").putArray("assets");
        assets.addObject().put("name", "QLXD4 수신기 1").put("kind", "무선 수신기").put("model", "QLXD4")
                .put("location", "메인홀").put("autoObservationKind", "QLXD4");
        assets.addObject().put("name", "믹서").put("kind", "믹서").putNull("model").putNull("location")
                .putNull("autoObservationKind");
        group(r, 1).put("purpose", "SAFETY");
        group(r, 0).putNull("purpose");
    }

    @Test void devicesAddLinkTasksAndStayOutOfThePublicResult() {
        AiJobOutcome out = readyWith(GeneratePlanKindReadTest::devices);
        assertThat(out.state()).as(out.resultJson()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(out.resultSchemaVersion()).isEqualTo("5");   // 평면도 없는 결과5에도 싣는다(V9-D61)
        JsonNode r = json.readTree(out.resultJson());
        JsonNode plan = r.get("plan");
        assertThat(plan.get("tasks").size()).isEqualTo(9);
        JsonNode camera = plan.get("tasks").get(7), equipment = plan.get("tasks").get(8);
        // 카메라 연결: SAFETY 표시 그룹(무대 운영), 첫 카메라 구역(로비), 첫 공연(01:00) 30분 전부터 시작까지, 별도 묶음
        assertThat(camera.get("title").asString()).isEqualTo("객석 카메라 연결");
        assertThat(camera.get("workGroupId").asString()).isEqualTo("tmp-wg-stage");
        assertThat(camera.get("zoneId").asString()).isEqualTo("33333333-3333-4333-8333-333333333302");
        assertThat(camera.get("bundleKey").asString()).isEqualTo("camera-link");
        assertThat(camera.get("startAt").asString()).isEqualTo("2026-11-14T00:30:00Z");
        assertThat(camera.get("endAt").asString()).isEqualTo("2026-11-14T01:00:00Z");
        assertThat(camera.get("programId").isNull()).isTrue();
        assertThat(camera.get("steps").size()).isEqualTo(3);
        assertThat(camera.get("checklist").get(0).get("text").asString()).isEqualTo("모두 연결됨 확인");
        assertThat(camera.get("checklist").get(0).get("required").asBoolean()).isTrue();
        // 장비 연결: EQUIPMENT 표시 그룹이 없어 "장비 운영" 그룹을 새로 만든다. 구역은 첫 연결 장비 위치(메인홀)
        assertThat(equipment.get("title").asString()).isEqualTo("장비 자동 수집 연결");
        assertThat(equipment.get("bundleKey").asString()).isEqualTo("equipment-link");
        assertThat(equipment.get("zoneId").asString()).isEqualTo("33333333-3333-4333-8333-333333333301");
        JsonNode newGroup = plan.get("workGroups").get(2);
        assertThat(newGroup.get("name").asString()).isEqualTo("장비 운영");
        assertThat(equipment.get("workGroupId").asString()).isEqualTo(newGroup.get("tempId").asString());
        // 그룹 표시(purpose)는 화면 결과에 싣지 않는다(028 모양 그대로)
        assertThat(plan.get("workGroups").get(1).propertyNames()).containsExactly("tempId", "name", "instructions");
        // 카메라·장비 목록은 저장 결과의 deviceDraft에만 있고 063 응답에는 없다
        JsonNode devices = r.get("deviceDraft");
        assertThat(devices.get("cameras").get(0).get("zoneName").asString()).isEqualTo("로비");
        assertThat(devices.get("assets").size()).isEqualTo(2);
        assertThat(devices.get("assets").get(1).get("autoObservationKind").isNull()).isTrue();
        var exposed = json.valueToTree(json.treeToValue(r, com.ssafy.scenecrew.aijob.read.AiJobRead.PlanResult.class));
        assertThat(exposed.propertyNames()).containsExactly("status", "questions", "plan");
    }

    @Test void linkTaskFallsBackToNamedGroupAndPrepStart() {
        // 자동 관측 장비가 없으면 장비 연결 업무가 없다. SAFETY 표시가 없으면 같은 이름(안전) 그룹을 쓴다
        AiJobOutcome out = readyWith(r -> {
            devices(r);
            group(r, 1).putNull("purpose");
            group(r, 0).put("name", "안전");
            ((ObjectNode) r.get("equipmentDraft").get("assets").get(0)).putNull("autoObservationKind");
        });
        JsonNode plan = json.readTree(out.resultJson()).get("plan");
        assertThat(plan.get("tasks").size()).isEqualTo(8);
        assertThat(plan.get("workGroups").size()).isEqualTo(2);
        assertThat(plan.get("tasks").get(7).get("workGroupId").asString()).isEqualTo("tmp-wg-reception");
        // 첫 공연이 준비 시작 30분 안이면 준비 시작으로 당기고, 공연이 없으면 준비 시작부터 30분이다
        var mapper = com.ssafy.scenecrew.aijob.contract.AiContractJson.mapper();
        var result = mapper.treeToValue(json.readTree(fixture("success-ready.json")).get("result"),
                com.ssafy.scenecrew.aijob.contract.plan.PlanSuccess.Result.class);
        ObjectNode event = (ObjectNode) envelope().payload().get("event").deepCopy();
        event.put("prepStartAt", "2026-11-14T00:45:00Z");
        var late = mapper.treeToValue(event, com.ssafy.scenecrew.aijob.contract.plan.PlanRequest.Event.class);
        assertThat(LinkTaskRule.window(result, late)).containsExactly(Instant.parse("2026-11-14T00:45:00Z"),
                Instant.parse("2026-11-14T01:00:00Z"));
        event.put("prepStartAt", "2026-11-14T01:00:00Z");
        assertThat(LinkTaskRule.window(result, mapper.treeToValue(event, com.ssafy.scenecrew.aijob.contract.plan.PlanRequest.Event.class)))
                .containsExactly(Instant.parse("2026-11-14T01:00:00Z"), Instant.parse("2026-11-14T01:30:00Z"));
        // 카메라·장비가 없으면 연결 업무도 deviceDraft도 없다
        JsonNode plain = json.readTree(kind.read(envelope(), response(fixture("success-ready.json"))).resultJson());
        assertThat(plain.get("plan").get("tasks").size()).isEqualTo(7);
        assertThat(plain.has("deviceDraft")).isFalse();
    }

    /** camera-link·equipment-link는 규칙 업무 전용이다. AI 업무가 쓰면 그 묶음의 키를 바꾼다(#395 리뷰). */
    @Test void reservedBundleKeysBelongToRuleTasksOnly() {
        java.util.function.Consumer<ObjectNode> aiUsesCameraLink = r -> {
            for (JsonNode t : r.get("tasks"))
                if ("stage-b".equals(t.get("bundleKey").asString())) ((ObjectNode) t).put("bundleKey", "camera-link");
            for (JsonNode b : r.get("bundles"))
                if ("stage-b".equals(b.get("bundleKey").asString())) ((ObjectNode) b).put("bundleKey", "camera-link");
        };
        AiJobOutcome out = readyWith(r -> { aiUsesCameraLink.accept(r); devices(r); });
        assertThat(out.state()).as(out.resultJson()).isEqualTo(AiJobState.SUCCEEDED);
        JsonNode tasks = json.readTree(out.resultJson()).get("plan").get("tasks");
        assertThat(tasks.get(5).get("bundleKey").asString()).isEqualTo("camera-link-ai");
        assertThat(tasks.get(7).get("bundleKey").asString()).isEqualTo("camera-link");
        assertThat(tasks.get(7).get("title").asString()).isEqualTo("객석 카메라 연결");
        long reserved = 0;
        for (JsonNode t : tasks) if ("camera-link".equals(t.get("bundleKey").asString())) reserved++;
        assertThat(reserved).isEqualTo(1);
        // 카메라가 없어 규칙 업무를 더하지 않아도 AI 업무는 그 키를 쓰지 않는다(공개 뒤 카메라 등록이 잘못 지정하지 않게)
        JsonNode plain = json.readTree(readyWith(aiUsesCameraLink).resultJson()).get("plan").get("tasks");
        assertThat(plain.get(5).get("bundleKey").asString()).isEqualTo("camera-link-ai");
    }

    @Test void deviceFieldViolationsAreNotStored() {
        List<java.util.function.Consumer<ObjectNode>> changes = List.of(
                r -> { devices(r); ((ObjectNode) r.get("cameras").get(0)).put("zoneName", "없는 구역"); },
                r -> { devices(r); ((ArrayNode) r.get("cameras")).addObject().put("name", "객석 카메라 1").put("zoneName", "메인홀"); },
                r -> { devices(r); ((ObjectNode) r.get("equipmentDraft").get("assets").get(1)).put("name", "QLXD4 수신기 1"); },
                r -> { devices(r); ((ObjectNode) r.get("equipmentDraft").get("assets").get(0)).put("autoObservationKind", "OTHER"); },
                r -> { devices(r); group(r, 1).put("purpose", "OTHER"); },
                r -> { devices(r); ((ObjectNode) r.get("equipmentDraft")).putArray("connections"); },
                r -> { devices(r); ((ObjectNode) r.get("cameras").get(0)).put("ip", "10.0.0.1"); });
        for (var change : changes) {
            AiJobOutcome out = readyWith(change);
            assertThat(out.state()).as(out.toString()).isEqualTo(AiJobState.FAILED);
            assertThat(out.error().details()).containsEntry("reason", "INVALID_RESULT");
        }
        // NEEDS_INFO에는 싣지 않는다
        ObjectNode needs = (ObjectNode) json.readTree(fixture("success-needs-info.json"));
        ((ObjectNode) needs.get("result")).putArray("cameras").addObject().put("name", "카메라").put("zoneName", "로비");
        assertThat(kind.read(envelope(), response(needs.toString())).state()).isEqualTo(AiJobState.FAILED);
    }

    /** V9-D67: 조건 receiverLinkBy가 CHIEF이면 장비 연결 업무만 빼고, 카메라 업무와 장비 칸 목록은 그대로다 */
    @Test void chiefReceiverLinkSkipsEquipmentLinkTask() {
        for (String value : List.of("CHIEF", " CHIEF ")) {
            AiJobOutcome out = readyWith(payloadWith(p -> ((ObjectNode) p.get("event")).putObject("confirmedInput")
                    .put("receiverLinkBy", value)), GeneratePlanKindReadTest::devices);
            assertThat(out.state()).as(out.resultJson()).isEqualTo(AiJobState.SUCCEEDED);
            JsonNode r = json.readTree(out.resultJson());
            List<String> bundles = new ArrayList<>();
            r.get("plan").get("tasks").forEach(t -> bundles.add(t.get("bundleKey").asString()));
            assertThat(bundles).contains("camera-link").doesNotContain("equipment-link");
            assertThat(r.get("plan").get("workGroups").size()).isEqualTo(2);   // "장비 운영" 그룹도 새로 만들지 않는다
            assertThat(r.get("deviceDraft").get("assets").size()).isEqualTo(2);
        }
        // 다른 값(소문자·담당 이름)은 기존 규칙대로 업무를 만든다
        AiJobOutcome other = readyWith(payloadWith(p -> ((ObjectNode) p.get("event")).putObject("confirmedInput")
                .put("receiverLinkBy", "chief")), GeneratePlanKindReadTest::devices);
        assertThat(other.resultJson()).contains("equipment-link");
    }

    /** V9-D66: 요청4에 scheduleItems를 실어도 저장 요청을 다시 읽어 응답을 대조할 수 있다(계약 record가 키를 안다) */
    @Test void requestWithScheduleItemsIsRead() {
        AiJobEnvelope withSchedule = payloadWith(p -> p.putArray("scheduleItems").addObject()
                .put("key", "d1p7r1").put("title", "A구역 동선 점검")
                .put("startAt", "2026-11-13T23:00:00+09:00").putNull("endAt").put("timeText", "08:00~미정")
                .put("workGroupName", "안전 통제").put("zoneName", "A구역").putNull("bundle")
                .putObject("source").put("documentId", DOC).put("pageNumber", 7));
        ((ObjectNode) withSchedule.payload().get("scheduleItems").get(0)).putArray("steps").add("동선 확인");
        ((ObjectNode) withSchedule.payload().get("scheduleItems").get(0)).putArray("checklist").add("통제선 확인");
        assertThat(kind.read(withSchedule, response(fixture("success-ready.json"))).state()).isEqualTo(AiJobState.SUCCEEDED);
    }

    AiJobEnvelope payloadWith(java.util.function.Consumer<ObjectNode> change) {
        AiJobEnvelope base = envelope();
        ObjectNode payload = (ObjectNode) base.payload().deepCopy();
        change.accept(payload);
        return new AiJobEnvelope(base.jobId(), base.attempt(), base.requestSchemaVersion(), base.kind(),
                base.inputHash(), base.contextRevision(), base.deadlineAt(), null, payload);
    }

    AiJobOutcome readyWith(AiJobEnvelope request, java.util.function.Consumer<ObjectNode> change) {
        ObjectNode success = (ObjectNode) json.readTree(fixture("success-ready.json"));
        change.accept((ObjectNode) success.get("result"));
        return kind.read(request, response(success.toString()));
    }

    AiJobOutcome readyWith(java.util.function.Consumer<ObjectNode> change) {
        ObjectNode success = (ObjectNode) json.readTree(fixture("success-ready.json"));
        change.accept((ObjectNode) success.get("result"));
        return kind.read(envelope(), response(success.toString()));
    }

    static ObjectNode group(ObjectNode result, int i) { return (ObjectNode) result.get("workGroups").get(i); }

    AiJobEnvelope envelope() {
        JsonNode r = json.readTree(fixture("request.json"));
        return new AiJobEnvelope(UUID.fromString(r.get("jobId").asString()), r.get("attempt").asInt(),
                r.get("requestSchemaVersion").asString(), r.get("kind").asString(), r.get("inputHash").asString(),
                r.get("contextRevision").asLong(), Instant.parse(r.get("deadlineAt").asString()), null, r.get("payload"));
    }

    static AiResponse response(String body) { return new AiResponse(200, body); }

    static String fixture(String name) {
        try (InputStream in = GeneratePlanKindReadTest.class.getResourceAsStream("/ai-contract/plan/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(name, e);
        }
    }
}
