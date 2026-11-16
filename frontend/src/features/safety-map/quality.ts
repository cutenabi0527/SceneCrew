// 관측 품질 사유(qualityReasons) 코드를 화면 문구로 바꾼다.
// 코드는 계약상 대문자·숫자·_ 문자열이며 AI(#360 ai/crowd/README.md), BE(CameraAnalyzer·StubCrowdEstimator),
// 입력 검토 문서의 제안 목록(docs/ai/crowd-density-input-review.md 3절)에서 온다.
// 모르는 코드는 코드 그대로 보이지 않고 "기타 관측 품질 문제"로 묶는다. 코드가 아닌 글(예: 한국어 문장)은 그대로 둔다.

const QUALITY_REASON: Record<string, string> = {
    // AI 밀집도 서버(#360)
    LOW_LIGHT: '어두움',
    OVEREXPOSED: '너무 밝음',
    BLURRED: '화면 흐림',
    OUT_OF_VIEW: '구역이 화면 밖',
    ROI_OUTSIDE_FRAME: '구역 일부가 화면 밖',
    ROI_MISSING: '관측 범위 미설정',
    // BE 관측 저장
    PARTIAL_COVERAGE: '구역 일부만 관측',
    UNDETERMINED_SCOPE: '관측 범위 판정 불가',
    INVALID_MAPPING: '카메라 재보정 필요',
    NOT_RETURNED: '분석 결과 없음',
    TEST_VALUE: '시험값(실제 인원 아님)',
    // 입력 검토 문서의 제안 코드
    AREA_UNCONFIRMED: '면적 미확인',
    COVERAGE_UNDETERMINED: '관측 범위 판정 불가',
    OCCLUSION: '가림',
    FRAME_DISAGREEMENT: '프레임마다 인원 차이 큼',
    CAPTURE_TIME_UNTRUSTED: '촬영 시각 불확실',
    MAPPING_INVALID: '카메라 재보정 필요',
    RULE_MISSING: '기준 없음',
};

const CODE = /^[A-Z][A-Z0-9_]*$/;

export function qualityReasonText(reason: string) {
    if (Object.hasOwn(QUALITY_REASON, reason)) return QUALITY_REASON[reason];
    return CODE.test(reason) ? '기타 관측 품질 문제' : reason;
}

/** 여러 사유를 화면 문구 한 줄로. 같은 문구는 한 번만 쓴다 */
export function qualityReasonsText(reasons: readonly string[]) {
    return [...new Set(reasons.map(qualityReasonText))].join(' / ');
}
