package com.ssafy.scenecrew.issue.dto;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * #282 보고·이슈 응답(v9 계약 !128 IssueView·IssuePage·IssueCreated·ReportCreated·JobAccepted).
 * 계약상 모든 필드가 필수라 값이 없어도 null로 보낸다. revision은 DecimalBigInt라 10진 문자열이다.
 */
public final class IssueResponses {
    private IssueResponses() { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "issueId", "authorMemberId", "text", "audioId", "kind", "participationRevision", "createdAt"})
    public record ReportView(UUID id, UUID issueId, UUID authorMemberId, String text, UUID audioId, String kind,
            String participationRevision, java.time.Instant createdAt) {
        public ReportView withText(String value) {
            return new ReportView(id, issueId, authorMemberId, value, audioId, kind, participationRevision, createdAt);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"zoneId", "source", "sourceRef", "contextRevision"})
    public record ZoneCandidate(UUID zoneId, String source, UUID sourceRef, String contextRevision) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"analysisValidity", "category", "reportedState", "zoneCandidates"})
    public record AnalysisSummary(String analysisValidity, String category, String reportedState,
            List<ZoneCandidate> zoneCandidates) { }

    /** 최신 보고의 AI 상황 추천(결과4). primaryType=null은 미분류다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"primaryType", "relatedTypes"})
    public record SituationSuggestion(String primaryType, List<String> relatedTypes) { }

    /**
     * 이슈의 운영 상황(V9-D48, 총괄 전용). 총괄이 정정했으면 그 값(confirmed=true)이 우선하고, 아니면 최신 보고의
     * AI 추천을 그대로 보인다. suggestion은 정정 뒤에도 최신 추천을 따로 보여 준다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"primaryType", "relatedTypes", "confirmed", "confirmedByMemberId", "confirmedAt", "reason",
            "suggestion"})
    public record SituationView(String primaryType, List<String> relatedTypes, boolean confirmed,
            UUID confirmedByMemberId, java.time.Instant confirmedAt, String reason, SituationSuggestion suggestion) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "title", "status", "zoneId", "workGroupId", "taskId", "relatedIssueId", "reports",
            "analysis", "situation", "eventRevision", "eventId", "createdAt"})
    public record IssueView(UUID id, String title, String status, UUID zoneId, UUID workGroupId,
            UUID taskId, UUID relatedIssueId, List<ReportView> reports, AnalysisSummary analysis, SituationView situation,
            String eventRevision, UUID eventId, java.time.Instant createdAt) {
        public IssueView withReports(List<ReportView> value) {
            return new IssueView(id, title, status, zoneId, workGroupId, taskId, relatedIssueId, value, analysis,
                    situation, eventRevision, eventId, createdAt);
        }
    }

    /** EXT19 응답(FinalReceipt). 상세는 이슈 조회로 다시 읽는다. revision은 이 정정이 만든 Event.revision이다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "revision", "eventRevision"})
    public record SituationReceipt(UUID id, String revision, String eventRevision) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record IssuePage(List<IssueView> items, String nextCursor) { }

    /** 056 응답. 같은 키 재요청에는 최초 응답을 그대로 돌려준다(당시 이슈 상태 포함). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"issue", "report", "analysisJobId"})
    public record IssueCreated(IssueView issue, ReportView report, UUID analysisJobId) { }

    /** 059 응답 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"report", "analysisJobId", "eventRevision"})
    public record ReportCreated(ReportView report, UUID analysisJobId, String eventRevision) { }

    /** 062 응답. 분석 결과는 statusUrl(AI Job 조회, #235)로 따로 본다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"jobId", "statusUrl"})
    public record JobAccepted(UUID jobId, String statusUrl) { }
}
