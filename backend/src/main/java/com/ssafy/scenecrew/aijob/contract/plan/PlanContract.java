package com.ssafy.scenecrew.aijob.contract.plan;

/**
 * 최초 계획 생성(GENERATE_PLAN) 계약 상수. 기준: ai/plan/contract.py, schema/*.json
 */
public final class PlanContract {
    public static final String KIND = "GENERATE_PLAN";
    public static final String REQUEST_SCHEMA_VERSION = "4";
    public static final String RESULT_SCHEMA_VERSION = "5";
    /** 결과5에 평면도 초안(venueDraft.layout)을 더한 결과6(V9-D45·V9-D54, #391). floorPlan을 보낸 요청에 온다. */
    public static final String RESULT_SCHEMA_VERSION_FLOOR_PLAN = "6";

    static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    static final String TEMP_ID = "^tmp-[A-Za-z0-9_-]{1,40}$";
    /** 기존 항목 UUID 또는 결과 안에서 새로 만든 tempId */
    static final String REF = "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|tmp-[A-Za-z0-9_-]{1,40})$";
    static final String SHA256 = "^[0-9a-f]{64}$";
    static final String KEY = "^[A-Za-z0-9_-]{1,40}$";

    // P01 상한
    public static final int MAX_ZONES = 10;
    public static final int MAX_PROGRAMS = 20;
    /** 요청4 scheduleItems 상한(V9-D66, ai/plan/contract.py MAX_SCHEDULE_ITEMS) */
    public static final int MAX_SCHEDULE_ITEMS = 50;
    public static final int MAX_WORK_GROUPS = 31;
    static final int MAX_TASKS = 200;
    static final int MAX_MEMBERS = 31;
    /** 카메라·장비 선택 필드 상한(ai/plan devices.py, V9-D43 행사당 카메라 20) */
    public static final int MAX_CAMERAS = 20;
    public static final int MAX_ASSETS = 50;

    private PlanContract() {
    }
}
