package com.ssafy.scenecrew.impact.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 이슈 하나의 영향 분석 결과.
 *
 * - 영향받는 업무: 이슈가 난 구역의 업무와 이슈가 가리킨 업무 중 대기·진행 중인 것
 * - 담당자: 업무의 현재 담당자(없으면 null)
 * - 투입 후보: 같은 직무 그룹의 활성·투입 가능 팀원 중, 담당자가 아니고 그 시간에 겹치는 대기·진행 업무가 없는 사람
 *
 * 두 구현의 결과를 비교할 수 있도록 순서를 고정한다(업무는 시작 시각, 후보는 예비 인원 먼저).
 */
public record ImpactResult(UUID issueId, List<AffectedTask> tasks) {

    public ImpactResult {
        tasks = tasks.stream()
                .sorted(Comparator.comparing(AffectedTask::startAt).thenComparing(AffectedTask::taskId))
                .toList();
    }

    public record AffectedTask(UUID taskId, String title, Instant startAt, Instant endAt, UUID workGroupId,
            MemberRef assignee, List<MemberRef> candidates) {

        public AffectedTask {
            candidates = candidates.stream()
                    .sorted(Comparator.comparing(MemberRef::reserve).reversed()
                            .thenComparing(MemberRef::displayName)
                            .thenComparing(MemberRef::memberId))
                    .toList();
        }
    }

    public record MemberRef(UUID memberId, String displayName, boolean reserve) {
    }
}
