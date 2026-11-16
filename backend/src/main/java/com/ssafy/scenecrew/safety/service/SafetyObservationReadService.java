package com.ssafy.scenecrew.safety.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.web.SignedCursor;
import com.ssafy.scenecrew.safety.service.SafetyViews.ObservationPage;
import com.ssafy.scenecrew.safety.service.SafetyViews.ObservationView;

/**
 * 안전 관측 목록 EXT13·조회 EXT14. CHIEF 전용이다(WORKER는 업무 상세의 본인 구역 집계만 받는다, BE07).
 * 최근 수신 순(received_at 내림차순·id)이다. 관측은 카메라 수신(safety/collector, #349)이 저장하며, 수신을 켜지 않으면 빈 목록이 정상이다.
 * 공개 관측 객체에 원본 저장 키·프레임 참조는 없다(저장하지 않는다).
 */
public class SafetyObservationReadService {
    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final SignedCursor cursor;

    public SafetyObservationReadService(JdbcTemplate jdbc, CurrentMembers members, byte[] signingKey) {
        this.jdbc = jdbc;
        this.members = members;
        this.cursor = new SignedCursor(signingKey, "safety-observations");
    }

    @Transactional(readOnly = true)
    public ObservationPage list(UUID eventId, UUID accountId, String after, int limit) {
        Member member = members.require(eventId, accountId);
        SafetyMapService.requireChief(member);
        String scope = eventId + ":" + member.getId() + ":" + member.getParticipationRevision();
        SignedCursor.Position position = cursor.decode(scope, after);
        List<Object> args = new ArrayList<>(List.of(eventId));
        String where = "";
        if (position != null) {
            where = " AND (o.received_at, o.id) < (?::timestamptz, ?)";
            args.add(position.sortKey());
            args.add(position.id());
        }
        args.add(limit + 1);
        List<ObservationView> rows = jdbc.query("SELECT " + SafetyViews.OBSERVATION_COLUMNS
                + " FROM safety_observation o WHERE o.event_id = ?" + where + " ORDER BY o.received_at DESC, o.id DESC LIMIT ?",
                SafetyViews::observation, args.toArray());
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            ObservationView last = rows.getLast();
            next = cursor.encode(scope, new SignedCursor.Position(last.receivedAt().toString(), last.id()));
        }
        return new ObservationPage(List.copyOf(rows), next);
    }

    @Transactional(readOnly = true)
    public ObservationView get(UUID observationId, UUID accountId) {
        UUID eventId = jdbc.query("SELECT event_id FROM safety_observation WHERE id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, observationId);
        if (eventId == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        SafetyMapService.requireChief(members.require(eventId, accountId));
        return jdbc.query("SELECT " + SafetyViews.OBSERVATION_COLUMNS + " FROM safety_observation o WHERE o.id = ?",
                SafetyViews::observation, observationId).getFirst();
    }
}
