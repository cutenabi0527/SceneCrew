package com.ssafy.scenecrew.plan.ai.generate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.aijob.contract.plan.PlanRequest;
import com.ssafy.scenecrew.aijob.contract.plan.PlanSuccess;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * 카메라·장비 연결 업무 규칙(V9-D58·D59·D60, #395). AI가 아니라 BE 규칙이 계획 후보(063 plan)에 업무를 더한다.
 *
 * <ul>
 *   <li>카메라가 1대 이상이면 "객석 카메라 연결", 자동 관측 종류(QLXD4)가 있는 장비가 1대 이상이면 "장비 자동 수집 연결"을 1건씩 더한다.</li>
 *   <li>그룹: AI가 SAFETY·EQUIPMENT 표시를 붙인 초안 순서상 첫 그룹. 없으면 같은 이름("안전"·"장비 운영")의 그룹, 그것도 없으면 새로 만든다.</li>
 *   <li>시간: 첫 공연(가장 이른 Program 시작) 30분 전부터 시작까지. 준비 시작보다 앞서면 준비 시작으로 당기고,
 *       공연이 없거나 그 결과 길이가 없으면 준비 시작부터 30분이다.</li>
 *   <li>구역: 카메라는 첫 카메라가 비추는 구역, 장비는 첫 연결 장비의 위치와 같은 이름의 구역. 없으면 초안 첫 구역.</li>
 *   <li>별도 묶음(bundleKey camera-link·equipment-link). 공개(029) 때 이 묶음 키로 연결 업무를 찾는다(PublishLinkSlots).
 *       두 키는 규칙 업무 전용이라, AI 업무가 같은 키를 쓰면 그 AI 업무들의 키를 camera-link-ai처럼 바꾼다(#395 리뷰).</li>
 * </ul>
 * 조건 receiverLinkBy가 CHIEF이면(V9-D67) 총괄이 자기 노트북으로 수신기를 연결하므로 "장비 자동 수집 연결"은 더하지 않는다.
 * 카메라·장비 목록 자체는 화면용 결과에 싣지 않고 저장 결과의 deviceDraft로만 둔다(공개 때 칸을 만든다).
 */
public final class LinkTaskRule {
    public static final String CAMERA_BUNDLE = "camera-link";
    public static final String EQUIPMENT_BUNDLE = "equipment-link";
    /** 저장 결과(ai_job.result)에만 두는 키. 063 공개 응답에는 나가지 않는다. */
    public static final String DEVICE_DRAFT = "deviceDraft";
    /** 수신기 연결 담당 조건 키와 총괄 값(V9-D67). 문서 부록 "키 값" 표에서 온다. */
    static final String RECEIVER_LINK_BY = "receiverLinkBy";
    static final String CHIEF = "CHIEF";
    static final String CAMERA_TITLE = "객석 카메라 연결";
    static final String EQUIPMENT_TITLE = "장비 자동 수집 연결";
    static final String SAFETY_GROUP = "안전";
    static final String EQUIPMENT_GROUP = "장비 운영";
    static final Duration LEAD = Duration.ofMinutes(30);
    static final List<String> STEPS = List.of("연결 화면 열기", "연결과 방향·수신기 확인", "모두 연결됨 확인");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private LinkTaskRule() {
    }

    /** READY 계획 후보(plan)에 연결 업무와 필요한 새 그룹을 더한다. 구역이 하나도 없으면 업무를 놓을 수 없어 더하지 않는다. */
    static void add(ObjectNode plan, PlanSuccess.Result r, PlanRequest.Event event) {
        reserveBundles(plan);
        if (r.zones().isEmpty()) return;
        boolean cameras = !r.cameras().isEmpty();
        // 총괄이 직접 수신기를 연결하면(V9-D67) 장비 연결 업무를 팀원에게 만들지 않는다. 장비 칸(deviceDraft)은 그대로 만든다.
        List<PlanSuccess.Asset> linked = chiefLinksReceivers(event) ? List.of()
                : r.assets().stream().filter(a -> a.autoObservationKind() != null).toList();
        if (!cameras && linked.isEmpty()) return;

        Set<String> refs = new HashSet<>();
        for (JsonNode g : plan.path("workGroups")) refs.add(ref(g));
        for (JsonNode z : plan.path("zones")) refs.add(ref(z));
        for (JsonNode p : plan.path("programs")) refs.add(ref(p));
        for (JsonNode t : plan.path("tasks")) refs.add(t.get("tempId").asString());
        Instant[] window = window(r, event);
        if (cameras) {
            String group = group(plan, r, PlanSuccess.Purpose.SAFETY, SAFETY_GROUP, refs);
            String zone = zone(r, z -> UnicodeText.nameKey(z.name()).equals(UnicodeText.nameKey(r.cameras().get(0).zoneName())));
            task(plan, unique("tmp-" + CAMERA_BUNDLE, refs), group, CAMERA_BUNDLE, zone, CAMERA_TITLE, window);
        }
        if (!linked.isEmpty()) {
            String group = group(plan, r, PlanSuccess.Purpose.EQUIPMENT, EQUIPMENT_GROUP, refs);
            String location = linked.get(0).location();
            String zone = zone(r, z -> location != null && UnicodeText.nameKey(z.name()).equals(UnicodeText.nameKey(location)));
            task(plan, unique("tmp-" + EQUIPMENT_BUNDLE, refs), group, EQUIPMENT_BUNDLE, zone, EQUIPMENT_TITLE, window);
        }
    }

    /** 조건 receiverLinkBy가 앞뒤 공백을 뺀 뒤 정확히 CHIEF인지(V9-D67). 키가 없거나 다른 값이면 기존 규칙대로 업무를 만든다. */
    static boolean chiefLinksReceivers(PlanRequest.Event event) {
        Object value = event.confirmedInput().get(RECEIVER_LINK_BY);
        return value instanceof String text && CHIEF.equals(text.strip());
    }

    /**
     * 규칙 업무 전용 묶음 키(camera-link·equipment-link)를 AI 업무가 쓰면 그 업무들의 키를 겹치지 않는 값(…-ai)으로 바꾼다.
     * 공개 때와 공개 뒤 카메라 등록 때 이 키로 연결 업무를 찾으므로, AI 업무가 연결 업무로 잡히지 않게 한다.
     * 같은 묶음의 업무는 함께 바꾸므로 묶음(같은 그룹·시간 안 겹침)은 그대로다.
     */
    static void reserveBundles(ObjectNode plan) {
        Set<String> used = new HashSet<>();
        for (JsonNode t : plan.path("tasks")) used.add(t.get("bundleKey").asString());
        for (String reserved : List.of(CAMERA_BUNDLE, EQUIPMENT_BUNDLE)) {
            if (!used.contains(reserved)) continue;
            String renamed = unique(reserved + "-ai", used);
            for (JsonNode t : plan.path("tasks"))
                if (reserved.equals(t.get("bundleKey").asString())) ((ObjectNode) t).put("bundleKey", renamed);
            used.remove(reserved);
        }
    }

    /** 공개 때 칸을 만들 카메라·장비 목록. READY이고 하나라도 있을 때만, 아니면 null */
    static ObjectNode deviceDraft(PlanSuccess.Result r) {
        if (r.status() != PlanSuccess.Status.READY || r.cameras().isEmpty() && r.assets().isEmpty()) return null;
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode cameras = out.putArray("cameras");
        for (PlanSuccess.Camera c : r.cameras()) cameras.addObject().put("name", c.name()).put("zoneName", c.zoneName());
        ArrayNode assets = out.putArray("assets");
        for (PlanSuccess.Asset a : r.assets()) {
            ObjectNode node = assets.addObject();
            node.put("name", a.name());
            node.put("kind", a.kind());
            node.put("model", a.model());
            node.put("location", a.location());
            node.put("autoObservationKind", a.autoObservationKind() == null ? null : a.autoObservationKind().name());
        }
        return out;
    }

    /** 표시 그룹(초안 순서상 첫 그룹) → 같은 이름 그룹 → 새 그룹 */
    private static String group(ObjectNode plan, PlanSuccess.Result r, PlanSuccess.Purpose purpose, String name, Set<String> refs) {
        for (PlanSuccess.WorkGroup g : r.workGroups()) if (g.purpose() == purpose) return g.ref();
        ArrayNode groups = (ArrayNode) plan.get("workGroups");
        for (JsonNode g : groups)
            if (UnicodeText.nameKey(g.get("name").asString()).equals(UnicodeText.nameKey(name))) return ref(g);
        String tempId = unique("tmp-link-" + purpose.name().toLowerCase(), refs);
        ObjectNode node = groups.addObject();
        node.put("tempId", tempId);
        node.put("name", name);
        node.put("instructions", "");
        return tempId;
    }

    private static String zone(PlanSuccess.Result r, Predicate<PlanSuccess.Zone> match) {
        return r.zones().stream().filter(match).findFirst().orElse(r.zones().get(0)).ref();
    }

    static Instant[] window(PlanSuccess.Result r, PlanRequest.Event event) {
        Instant first = r.programs().stream().map(PlanSuccess.Program::startAt).min(Instant::compareTo).orElse(null);
        Instant prep = event.prepStartAt();
        if (first == null || !first.isAfter(prep)) return new Instant[] { prep, prep.plus(LEAD) };
        Instant start = first.minus(LEAD);
        return new Instant[] { start.isBefore(prep) ? prep : start, first };
    }

    private static void task(ObjectNode plan, String tempId, String group, String bundle, String zone, String title,
            Instant[] window) {
        ObjectNode node = ((ArrayNode) plan.get("tasks")).addObject();
        node.put("tempId", tempId);
        node.put("workGroupId", group);
        node.put("bundleKey", bundle);
        node.put("zoneId", zone);
        node.putNull("programId");
        node.put("title", title);
        node.put("startAt", window[0].toString());
        node.put("endAt", window[1].toString());
        ArrayNode steps = node.putArray("steps");
        STEPS.forEach(steps::add);
        node.putArray("checklist").addObject().put("key", "all-linked").put("text", "모두 연결됨 확인").put("required", true);
    }

    /** 이미 쓰인 값이면 뒤에 -2, -3…을 붙인다(AI가 같은 tempId·묶음 키를 쓴 드문 경우). 묶음 키 상한(40자) 안이다. */
    private static String unique(String base, Set<String> used) {
        String value = base;
        for (int i = 2; used.contains(value); i++) value = base + "-" + i;
        used.add(value);
        return value;
    }

    private static String ref(JsonNode node) {
        return node.has("id") ? node.get("id").asString() : node.get("tempId").asString();
    }
}
