package com.ssafy.scenecrew.event.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.document.service.IntakeDocumentService;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.dto.EventListResponse.EventItem;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * SC-API-026 확인한 행사 입력 반영(PATCH /events/{e}/input). 규칙은 backend/docs/event-input.md.
 *
 * <ul>
 *   <li>CHIEF·DRAFT·기대 revision. 잠금 순서는 다른 행사 쓰기와 같은 Event → Member다.</li>
 *   <li>confirmedInput 전체를 교체한다. 빈 객체는 확인한 조건을 모두 비운다. 한도는 컨트롤러가 검사한다(V9-D42).</li>
 *   <li>실제로 바뀌면 revision을 1 올리고 last_input_modified_at을 지금으로 바꾼다. 그래서 바뀌기 전 입력으로 만든
 *       추출·계획 생성 결과가 새 입력을 덮어쓰지 못한다. 남은 입력 문서의 보관 만료도 지금부터 7일로 다시 정한다.
 *       같은 내용이면 아무것도 바꾸지 않는다(024와 같은 원칙).</li>
 *   <li>문서 추출 중에도 직접 조건 수정은 받는다(be/05). revision이 올라 늦게 온 추출 결과는 적용되지 않는다.</li>
 * </ul>
 * 응답은 행사 조회(023)와 같은 Event 모양이다. 공개 Event에는 confirmedInput이 없다.
 */
@Service
public class EventInputService {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;

    public EventInputService(JdbcTemplate jdbc, CurrentMembers members) {
        this.jdbc = jdbc;
        this.members = members;
    }

    /** confirmedInput은 요청 순서를 지킨 문자열 사전이다. */
    @Transactional
    public EventItem replace(UUID eventId, UUID accountId, LinkedHashMap<String, String> confirmedInput,
            long expectedRevision) {
        var event = jdbc.query("SELECT state, revision, confirmed_input::text AS input FROM event WHERE id = ? FOR UPDATE",
                (r, n) -> new EventRow(r.getString("state"), r.getLong("revision"), r.getString("input")), eventId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Member chief = members.requireChiefLocked(eventId, accountId);
        if (!"DRAFT".equals(event.state())) throw new BusinessException(ErrorCode.INVALID_STATE);
        if (event.revision() != expectedRevision) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                    Map.of("target", "event"));
        }
        String next = MAPPER.writeValueAsString(confirmedInput);
        // 키 순서만 다른 같은 사전은 같은 입력이다(JSON 객체 비교).
        if (!MAPPER.readTree(event.input()).equals(MAPPER.readTree(next))) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            jdbc.update("""
                    UPDATE event SET confirmed_input = ?::jsonb, revision = revision + 1, last_input_modified_at = ?
                     WHERE id = ?""", next, now.atOffset(ZoneOffset.UTC), eventId);
            // 입력 묶음이 바뀌었으므로 아직 남은 입력 문서의 보관 만료도 지금부터 다시 센다(file-storage.md, 문서 등록과 같은 방식).
            // 행사를 먼저 잠갔으므로 문서 등록·정리·만료 정리와 엇갈리지 않는다.
            jdbc.update("""
                    UPDATE intake_document SET expires_at = ?
                     WHERE event_id = ? AND storage_key IS NOT NULL AND expires_at > ?""",
                    now.plus(IntakeDocumentService.RETENTION).atOffset(ZoneOffset.UTC), eventId, now.atOffset(ZoneOffset.UTC));
        }
        return jdbc.query(EventListService.SELECT + " AND m.id = ?", (r, n) -> EventListService.row(r, n).item(),
                accountId, chief.getId()).getFirst();
    }

    private record EventRow(String state, long revision, String input) { }
}
