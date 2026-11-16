package com.ssafy.scenecrew.aijob.kind;

/**
 * 같은 종류의 AI 작업을 동시에 몇 개까지 진행할지(V9-D43 "진행 중 1개 규칙"). 넘으면 접수 때 429 RATE_LIMITED다.
 *
 * <ul>
 *   <li>NONE: 공통 한도 없음. 종류가 자기 규칙을 둔다(전사는 음성별, 보고 분석은 보고별).</li>
 *   <li>EVENT: 같은 행사·같은 종류 진행 중 1개(계획 생성·최초 배정 등).</li>
 *   <li>ACCOUNT: 같은 요청 계정·같은 종류 진행 중 1개(문서 추출, be/05 "계정별 활성 추출1").</li>
 * </ul>
 */
public enum ActiveLimit {
    NONE, EVENT, ACCOUNT
}
