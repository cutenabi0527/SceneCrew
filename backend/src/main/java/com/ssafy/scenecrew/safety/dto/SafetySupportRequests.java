package com.ssafy.scenecrew.safety.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.ssafy.scenecrew.change.service.Operations;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation.Candidate;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation.Request;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation.Selection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

public final class SafetySupportRequests {
    private static final Set<String> SCOPE = Set.of("observationId", "sourceZoneId", "targetZoneId",
            "workGroupId", "startAt", "endAt");
    private static final Set<String> QUERY = Set.of("observationId", "sourceZoneId", "targetZoneId",
            "workGroupId", "startAt", "endAt", "expectedEventRevision");
    private static final Set<String> SELECTION = Set.of("observationId", "sourceZoneId", "targetZoneId",
            "workGroupId", "startAt", "endAt", "candidates");
    private static final Set<String> CANDIDATE = Set.of("memberId", "participationRevision", "sourceTaskId");

    private SafetySupportRequests() { }

    public static Request query(UUID eventId, HttpServletRequest request) {
        StrictJson.onlyParams(request, QUERY);
        var scope = JsonNodeFactory.instance.objectNode();
        for (String field : SCOPE) scope.put(field, request.getParameter(field));
        return scope(eventId, StrictJson.revision(request.getParameter("expectedEventRevision"), "expectedEventRevision"),
                scope, "");
    }

    public static Selection selection(UUID eventId, long revision, JsonNode selection) {
        String path = "supportSelection";
        StrictJson.object(selection, path, SELECTION, SELECTION);
        // Each selection becomes one release and one add operation.
        List<Candidate> candidates = StrictJson.list(selection.get("candidates"), path + ".candidates",
                Operations.MAX_OPERATIONS / 2, SafetySupportRequests::candidate);
        if (candidates.isEmpty()) throw StrictJson.invalid(path + ".candidates", "REQUIRED");
        return new Selection(scope(eventId, revision, selection, path + "."), candidates);
    }

    private static Request scope(UUID eventId, long revision, JsonNode scope, String path) {
        return new Request(eventId, StrictJson.uuid(scope.get("observationId"), path + "observationId"),
                StrictJson.uuid(scope.get("sourceZoneId"), path + "sourceZoneId"),
                StrictJson.uuid(scope.get("targetZoneId"), path + "targetZoneId"),
                StrictJson.uuid(scope.get("workGroupId"), path + "workGroupId"),
                StrictJson.time(scope.get("startAt"), path + "startAt"),
                StrictJson.time(scope.get("endAt"), path + "endAt"), revision);
    }

    private static Candidate candidate(JsonNode node, String path) {
        StrictJson.object(node, path, CANDIDATE, CANDIDATE);
        return new Candidate(StrictJson.uuid(node.get("memberId"), path + ".memberId"),
                StrictJson.revision(node.get("participationRevision"), path + ".participationRevision"),
                StrictJson.uuid(node.get("sourceTaskId"), path + ".sourceTaskId"));
    }

    public record CandidatePage(String eventRevision, List<CandidateView> candidates) { }

    public record CandidateView(UUID memberId, String participationRevision, UUID sourceTaskId,
            List<String> eligibilityReasons) {
        public static CandidateView from(Candidate candidate) {
            return new CandidateView(candidate.memberId(), Long.toString(candidate.participationRevision()),
                    candidate.sourceTaskId(), List.of("CURRENT_PLACEMENT", "AVAILABLE", "MINIMUM_CREW_SATISFIED"));
        }
    }
}
