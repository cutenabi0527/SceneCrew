package com.ssafy.scenecrew.aijob.contract;

/**
 * AI 응답의 계약 위반 한 건. path는 JSON 위치(예: result.tasks[0].zoneId), code는 위반 종류다.
 * code: SCHEMA, INVALID_REFERENCE, DUPLICATE, TIME_OUT_OF_RANGE, TIME_OVERLAP, BUNDLE_MISMATCH, ECHO_MISMATCH
 */
public record ContractIssue(String path, String code, String message) {
}
