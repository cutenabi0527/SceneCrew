package com.ssafy.scenecrew.change.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ssafy.scenecrew.change.dto.ChangeResponses.AckTarget;
import com.ssafy.scenecrew.change.dto.ChangeResponses.AcknowledgmentPage;
import com.ssafy.scenecrew.change.dto.ChangeResponses.AcknowledgmentView;
import com.ssafy.scenecrew.change.dto.ChangeResponses.ChangeSetPage;
import com.ssafy.scenecrew.change.dto.ChangeResponses.ChangeSetView;
import com.ssafy.scenecrew.change.dto.ChangeResponses.DiffItem;
import com.ssafy.scenecrew.equipment.service.RecoveryService;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.notification.service.ChangeAcknowledgmentNotificationWriter;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation.Selection;
import com.ssafy.scenecrew.safety.service.SafetySupportProtection;

/**
 * #283 변경안(065~071)과 팀원 변경 확인(076·077), 근거 기록 조회(R2). 오세진 2026-11-05. 규칙은 backend/docs/change-set.md.
 *
 * <ul>
 *   <li>상태: DRAFT(생성·편집) → READY(069 미리보기) → APPLIED(070) / DISCARDED(071). 편집하면 미리보기를 지우고 DRAFT로 돌아간다.</li>
 *   <li>revision은 생성 1에서 시작해 편집·미리보기·적용·폐기마다 1 오른다. 생성·편집·미리보기 revision마다 근거 기록 한 행을 남긴다.</li>
 *   <li>적용은 두 기대 revision·previewHash·미리보기 때의 행사 revision을 다시 확인하고, 연산 반영·Event.revision 증가·APPLIED·
 *       작업 기록·변경 확인 대상을 한 트랜잭션에 저장한다. 다시 계산한 diff가 미리보기와 다르면 409.</li>
 * </ul>
 */
@Service
public class ChangeSetService {
    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;
    private static final Set<String> RUNNING = Set.of("RECRUITING", "LIVE");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final OperationEngine engine;
    private final PendingAcknowledgments pending;
    private final ChangeAcknowledgmentNotificationWriter notifications;
    private final SafetySupportPreparation safetySupport;
    private final SafetySupportProtection protection;

    public ChangeSetService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency, OperationEngine engine,
            PendingAcknowledgments pending, ChangeAcknowledgmentNotificationWriter notifications,
            SafetySupportPreparation safetySupport, SafetySupportProtection protection) {
        this.jdbc = jdbc;
        this.members = members;
        this.idempotency = idempotency;
        this.engine = engine;
        this.pending = pending;
        this.notifications = notifications;
        this.safetySupport = safetySupport;
        this.protection = protection;
    }

    /** 066 입력 */
    public record NewChangeSet(String mode, ArrayNode operations, UUID sourceJobId, UUID issueId, String rationale,
            Long expectedEventRevision, ArrayNode simulationRefs) { }

    /** 068 입력. 바꾸지 않는 항목은 null */
    public record ChangeSetEdit(ArrayNode operations, String rationale, ArrayNode evidenceRefs, ArrayNode simulationRefs,
            long expectedChangeSetRevision, long expectedEventRevision) { }

    private record Row(UUID id, UUID eventId, UUID issueId, UUID sourceJobId, String mode, String status, long revision,
            long baseRevision, String operations, String diff, String previewHash, String rationale, String evidenceRefs,
            String simulationRefs) { }

    private record EventRow(String state, long revision) { }

    // ---- 조회 -------------------------------------------------------------------------------------------------

    /** 065. 최신 생성순(ID가 생성 시각 순서인 UUID라 ID 내림차순) */
    @Transactional(readOnly = true)
    public ChangeSetPage list(UUID eventId, UUID accountId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) throw invalid();
        UUID after = cursor == null ? null : decodeCursor(cursor);
        members.requireChief(eventId, accountId);
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM change_set WHERE event_id = ?"
                + (after == null ? "" : " AND id < ?") + " ORDER BY id DESC LIMIT ?", (r, n) -> row(r),
                after == null ? new Object[] {eventId, size + 1} : new Object[] {eventId, after, size + 1});
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        long revision = eventRevision(eventId);
        return new ChangeSetPage(page.stream().map(r -> view(r, revision)).toList(),
                more ? encodeCursor(page.getLast().id()) : null);
    }

    /** 067 */
    @Transactional(readOnly = true)
    public ChangeSetView get(UUID changeSetId, UUID accountId) {
        Row row = row(changeSetId, false);
        members.requireChief(row.eventId(), accountId);
        return view(row, eventRevision(row.eventId()));
    }

    /** R2 과거 revision의 근거 기록 */
    @Transactional(readOnly = true)
    public JsonNode evidence(UUID changeSetId, long revision, UUID accountId) {
        Row row = row(changeSetId, false);
        members.requireChief(row.eventId(), accountId);
        var snapshots = jdbc.queryForList("SELECT snapshot::text FROM change_set_evidence_revision WHERE change_set_id = ? AND revision = ?",
                String.class, changeSetId, revision);
        if (snapshots.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return MAPPER.readTree(snapshots.getFirst());
    }

    // ---- 066 생성 ---------------------------------------------------------------------------------------------

    @Transactional
    public IdempotentResult<ChangeSetView> create(UUID eventId, UUID accountId, String key, NewChangeSet input) {
        return create(eventId, accountId, key, input, null);
    }

    /** 066 support branch. Only the selection verified by the server is persisted. */
    @Transactional
    public IdempotentResult<ChangeSetView> createSupport(UUID accountId, String key, Selection selection) {
        var scope = selection.request();
        return create(scope.eventId(), accountId, key, null, selection);
    }

    private IdempotentResult<ChangeSetView> create(UUID eventId, UUID accountId, String key, NewChangeSet input,
            Selection selection) {
        members.requireChief(eventId, accountId);
        event(eventId, true);
        Member chief = members.requireChiefLocked(eventId, accountId);
        String hash = selection == null
                ? IdempotencyRequest.hash(eventId, input.mode(), canonical(input.operations()), input.sourceJobId(),
                        input.issueId(), input.rationale(), input.expectedEventRevision(), canonical(input.simulationRefs()))
                : IdempotencyRequest.hash("supportSelection", MAPPER.writeValueAsString(selection));
        var request = new IdempotencyRequest(accountId, "SC-API-066", key, hash,
                eventId, chief.getId(), chief.getParticipationRevision());
        return idempotency.run(request, ChangeSetView.class, () -> {
            NewChangeSet command = input;
            if (selection != null) {
                var scope = selection.request();
                protection.requireCapacity(protection.capture(eventId, now()), scope.sourceZoneId(), scope.workGroupId());
                command = safetySupport.prepare(accountId, scope, selection.candidates());
            }
            EventRow event = event(eventId, true);
            if ("ENDED".equals(event.state()))
                throw new BusinessException(ErrorCode.INVALID_STATE, "종료된 행사에는 변경안을 만들 수 없습니다.");
            if (command.expectedEventRevision() != null && command.expectedEventRevision() != event.revision())
                throw conflict("event");
            if (command.issueId() != null
                    && jdbc.queryForList("SELECT 1 FROM issue WHERE id = ? AND event_id = ?", command.issueId(), eventId).isEmpty())
                throw reference("issueId");
            if ("AI".equals(command.mode())) {
                // AI 변경안은 이 행사의 끝난 변경 생성 작업(064, #343)에서만 만든다.
                if (command.sourceJobId() == null || jdbc.queryForList("""
                        SELECT 1 FROM ai_job WHERE id = ? AND event_id = ? AND kind = 'GENERATE_CHANGE' AND state = 'SUCCEEDED'""",
                        command.sourceJobId(), eventId).isEmpty())
                    throw reference("sourceJobId");
            } else if (command.sourceJobId() != null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "수동 변경안에는 sourceJobId가 없습니다.");
            }
            requireCandidate(eventId, command.simulationRefs(), command.operations(), false);
            UUID id = timeOrderedId();
            jdbc.update("""
                    INSERT INTO change_set (id, event_id, issue_id, source_job_id, created_by_member_id, mode, status, revision,
                                            base_revision, operations, diff, preview_hash, rationale, evidence_refs, simulation_refs)
                    VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?::jsonb, '[]'::jsonb, NULL, ?, '[]'::jsonb, ?::jsonb)""",
                    id, eventId, command.issueId(), command.sourceJobId(), chief.getId(), command.mode(), event.revision(),
                    MAPPER.writeValueAsString(command.operations()), command.rationale(),
                    MAPPER.writeValueAsString(command.simulationRefs()));
            dryRun(eventId, id, chief.getId(), command.operations());
            if (selection != null) protection.record(eventId, id, chief.getId(), selection);
            snapshot(id, 1, MAPPER.createArrayNode());
            ChangeSetView view = view(row(id, false), event.revision());
            return IdempotentResult.of(201, view, "CHANGE_SET_CREATED", Map.of("changeSetId", id));
        });
    }

    // ---- 064 AI 대응안 저장(#343) -----------------------------------------------------------------------------

    /**
     * AI 대응안 결과를 AI 변경안(DRAFT, revision 1)으로 저장한다. GENERATE_CHANGE 결과 기록과 같은 트랜잭션
     * (GenerateChangeKind.apply)에서 부르며, 공통 처리가 행사 revision·외부 AI 동의를 이미 확인했다.
     * 066 생성과 같은 검사(연산 미리 적용해 보기)를 하고, 통과하지 못하면 저장점까지 되돌려 아무것도 남기지 않고 예외를 던진다.
     * 요청한 Member가 지금도 활성 총괄이어야 한다.
     */
    public UUID createFromAi(UUID eventId, UUID issueId, UUID jobId, UUID chiefMemberId, ArrayNode operations, String rationale,
            ArrayNode evidenceRefs) {
        EventRow event = event(eventId, false);
        if ("ENDED".equals(event.state()))
            throw new BusinessException(ErrorCode.INVALID_STATE, "종료된 행사에는 변경안을 만들 수 없습니다.");
        if (chiefMemberId == null || jdbc.queryForList("""
                SELECT 1 FROM member WHERE id = ? AND event_id = ? AND active AND event_role = 'CHIEF'""",
                chiefMemberId, eventId).isEmpty())
            throw new BusinessException(ErrorCode.FORBIDDEN);
        jdbc.execute("SAVEPOINT ai_change_set");
        try {
            UUID id = timeOrderedId();
            jdbc.update("""
                    INSERT INTO change_set (id, event_id, issue_id, source_job_id, created_by_member_id, mode, status, revision,
                                            base_revision, operations, diff, preview_hash, rationale, evidence_refs, simulation_refs)
                    VALUES (?, ?, ?, ?, ?, 'AI', 'DRAFT', 1, ?, ?::jsonb, '[]'::jsonb, NULL, ?, ?::jsonb, '[]'::jsonb)""",
                    id, eventId, issueId, jobId, chiefMemberId, event.revision(), MAPPER.writeValueAsString(operations),
                    rationale, MAPPER.writeValueAsString(evidenceRefs));
            dryRun(eventId, id, chiefMemberId, operations);
            snapshot(id, 1, evidenceRefs);
            jdbc.execute("RELEASE SAVEPOINT ai_change_set");
            return id;
        } catch (BusinessException e) {
            jdbc.execute("ROLLBACK TO SAVEPOINT ai_change_set");
            jdbc.execute("RELEASE SAVEPOINT ai_change_set");
            throw e;
        }
    }

    // ---- 068 편집 ---------------------------------------------------------------------------------------------

    @Transactional
    public ChangeSetView edit(UUID changeSetId, UUID accountId, ChangeSetEdit edit) {
        Row found = row(changeSetId, false);
        members.requireChief(found.eventId(), accountId);
        EventRow event = event(found.eventId(), true);
        Member chief = members.requireChiefLocked(found.eventId(), accountId);
        Row row = row(changeSetId, true);
        requireOpen(row);
        if (row.revision() != edit.expectedChangeSetRevision()) throw conflict("changeSet");
        if (event.revision() != edit.expectedEventRevision()) throw conflict("event");
        if (edit.operations() != null && protection.selection(row.eventId(), changeSetId) != null
                && !MAPPER.readTree(row.operations()).equals(edit.operations()))
            throw SafetySupportProtection.state("SUPPORT_OPERATIONS_CHANGED");
        String operations = edit.operations() == null ? row.operations() : MAPPER.writeValueAsString(edit.operations());
        if (edit.operations() != null) dryRun(row.eventId(), changeSetId, chief.getId(), edit.operations());
        String evidence = edit.evidenceRefs() == null ? row.evidenceRefs() : MAPPER.writeValueAsString(edit.evidenceRefs());
        String simulationRefs = edit.simulationRefs() == null ? row.simulationRefs()
                : MAPPER.writeValueAsString(edit.simulationRefs());
        requireCandidate(row.eventId(), (ArrayNode) MAPPER.readTree(simulationRefs == null ? "[]" : simulationRefs),
                (ArrayNode) MAPPER.readTree(operations), false);
        long revision = row.revision() + 1;
        // 어떤 편집이든 이전 검토를 무효로 한다(be/04): DRAFT·previewHash null·diff [].
        jdbc.update("""
                UPDATE change_set SET operations = ?::jsonb, rationale = ?, evidence_refs = ?::jsonb, simulation_refs = ?::jsonb,
                       status = 'DRAFT', preview_hash = NULL, diff = '[]'::jsonb, revision = ?, base_revision = ? WHERE id = ?""",
                operations, edit.rationale() == null ? row.rationale() : edit.rationale(), evidence,
                simulationRefs, revision, event.revision(), changeSetId);
        snapshot(changeSetId, revision, (ArrayNode) MAPPER.readTree(evidence == null ? "[]" : evidence));
        return view(row(changeSetId, false), event.revision());
    }

    // ---- 069 미리보기 ------------------------------------------------------------------------------------------

    @Transactional
    public ChangeSetView preview(UUID changeSetId, UUID accountId, long expectedChangeSetRevision, long expectedEventRevision) {
        Row found = row(changeSetId, false);
        members.requireChief(found.eventId(), accountId);
        EventRow event = runningEvent(found.eventId());
        Member chief = members.requireChiefLocked(found.eventId(), accountId);
        Row row = row(changeSetId, true);
        requireOpen(row);
        if (row.revision() != expectedChangeSetRevision) throw conflict("changeSet");
        if (event.revision() != expectedEventRevision) throw conflict("event");
        ArrayNode operations = (ArrayNode) MAPPER.readTree(row.operations());
        recheckSupport(row, accountId, event.revision(), operations);
        requireCandidate(row.eventId(), simulationRefs(row), operations, true);
        List<DiffItem> diff = dryRun(row.eventId(), changeSetId, chief.getId(), operations).diff();
        long revision = row.revision() + 1;
        String diffJson = MAPPER.writeValueAsString(diff);
        String hash = previewHash(changeSetId, revision, event.revision(), operations, diff);
        jdbc.update("""
                UPDATE change_set SET status = 'READY', diff = ?::jsonb, preview_hash = ?, revision = ?, base_revision = ?
                 WHERE id = ?""", diffJson, hash, revision, event.revision(), changeSetId);
        snapshot(changeSetId, revision, (ArrayNode) MAPPER.readTree(row.evidenceRefs() == null ? "[]" : row.evidenceRefs()));
        return view(row(changeSetId, false), event.revision());
    }

    // ---- 070 적용 ---------------------------------------------------------------------------------------------

    @Transactional
    public IdempotentResult<ChangeSetView> apply(UUID changeSetId, UUID accountId, String key,
            long expectedChangeSetRevision, long expectedEventRevision, String previewHash) {
        Row found = row(changeSetId, false);
        members.requireChief(found.eventId(), accountId);
        event(found.eventId(), true);
        Member chief = members.requireChiefLocked(found.eventId(), accountId);
        var request = new IdempotencyRequest(accountId, "SC-API-070", key,
                IdempotencyRequest.hash(changeSetId, expectedChangeSetRevision, expectedEventRevision, previewHash),
                found.eventId(), chief.getId(), chief.getParticipationRevision());
        return idempotency.run(request, ChangeSetView.class, () -> {
            EventRow event = runningEvent(found.eventId());
            Row row = row(changeSetId, true);
            if (!"READY".equals(row.status()))
                throw new BusinessException(ErrorCode.INVALID_STATE, "미리보기를 마친(READY) 변경안만 적용할 수 있습니다.");
            if (row.revision() != expectedChangeSetRevision || !row.previewHash().equals(previewHash))
                throw conflict("changeSet");
            if (event.revision() != expectedEventRevision || row.baseRevision() != event.revision()) throw conflict("event");
            Instant now = now();
            ArrayNode operations = (ArrayNode) MAPPER.readTree(row.operations());
            recheckSupport(row, accountId, event.revision(), operations);
            requireCandidate(row.eventId(), simulationRefs(row), operations, true);
            var before = protection.capture(row.eventId(), now);
            OperationEngine.Outcome outcome = engine.apply(row.eventId(), changeSetId, chief.getId(), operations, now);
            protection.requirePreserved(row.eventId(), now, before);
            // 같은 행사 revision이라도 다른 경로의 변경이 있었으면 미리보기와 결과가 다르다. 그때는 다시 미리보기한다.
            if (!MAPPER.readTree(row.diff()).equals(MAPPER.readTree(MAPPER.writeValueAsString(outcome.diff()))))
                throw conflict("event");
            long eventRevision = jdbc.queryForObject("UPDATE event SET revision = revision + 1 WHERE id = ? RETURNING revision",
                    Long.class, row.eventId());
            jdbc.update("""
                    UPDATE change_set SET status = 'APPLIED', revision = revision + 1, approved_by_member_id = ?, applied_at = ?
                     WHERE id = ?""", chief.getId(), Timestamp.from(now), changeSetId);
            Selection support = protection.selection(row.eventId(), row.id());
            if (support != null) {
                var scope = support.request();
                protection.requireCapacity(protection.capture(row.eventId(), now), scope.sourceZoneId(), scope.workGroupId());
            }
            pending.supersede(row.eventId(), outcome.affected());
            acknowledgments(row.eventId(), changeSetId, outcome.targets(), now);
            ChangeSetView view = view(row(changeSetId, false), eventRevision);
            return IdempotentResult.of(200, view, "CHANGE_SET_APPLIED",
                    Map.of("changeSetId", changeSetId, "eventRevision", eventRevision));
        });
    }

    private void recheckSupport(Row row, UUID accountId, long eventRevision, ArrayNode operations) {
        Selection selection = protection.selection(row.eventId(), row.id());
        if (selection == null) return;
        var scope = selection.request();
        protection.requireCapacity(protection.capture(row.eventId(), now()), scope.sourceZoneId(), scope.workGroupId());
        var current = new SafetySupportPreparation.Request(scope.eventId(), scope.observationId(), scope.sourceZoneId(),
                scope.targetZoneId(), scope.workGroupId(), scope.startAt(), scope.endAt(), eventRevision);
        if (!safetySupport.prepare(accountId, current, selection.candidates()).operations().equals(operations))
            throw SafetySupportProtection.state("SUPPORT_OPERATIONS_CHANGED");
    }

    // ---- 071 폐기 ---------------------------------------------------------------------------------------------

    @Transactional
    public ChangeSetView discard(UUID changeSetId, UUID accountId, long expectedChangeSetRevision) {
        Row found = row(changeSetId, false);
        members.requireChief(found.eventId(), accountId);
        event(found.eventId(), true);
        members.requireChiefLocked(found.eventId(), accountId);
        Row row = row(changeSetId, true);
        requireOpen(row);
        if (row.revision() != expectedChangeSetRevision)
            throw new BusinessException(ErrorCode.INVALID_STATE, "변경안이 바뀌었습니다. 현재 상태를 다시 확인해 주세요.");
        jdbc.update("UPDATE change_set SET status = 'DISCARDED', revision = revision + 1 WHERE id = ?", changeSetId);
        return view(row(changeSetId, false), eventRevision(row.eventId()));
    }

    // ---- 076·077 변경 확인 -------------------------------------------------------------------------------------

    /** 076. 지금 참여 회차의 본인 확인 대상, 적용 최신순 */
    @Transactional(readOnly = true)
    public AcknowledgmentPage myChanges(UUID eventId, UUID accountId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) throw invalid();
        AckCursor after = cursor == null ? null : AckCursor.decode(cursor);
        Member me = members.require(eventId, accountId);
        // 초안 생성 순서와 적용 순서는 다를 수 있다. 같은 적용 시각은 변경안 ID로 안정 정렬한다.
        List<Object[]> rows = jdbc.query("""
                SELECT a.id, a.participation_revision, a.targets::text, a.targets_hash, a.acknowledged_at, a.change_set_id,
                       c.applied_at
                  FROM change_acknowledgment a JOIN change_set c ON c.id = a.change_set_id
                 WHERE a.event_id = ? AND a.member_id = ? AND a.participation_revision = ? AND c.applied_at IS NOT NULL"""
                + (after == null ? "" : " AND (c.applied_at < ? OR (c.applied_at = ? AND c.id < ?))")
                + " ORDER BY c.applied_at DESC, c.id DESC LIMIT ?",
                (r, n) -> new Object[] {ackView(r), new AckCursor(r.getTimestamp(7).toInstant(), r.getObject(6, UUID.class))},
                after == null ? new Object[] {eventId, me.getId(), me.getParticipationRevision(), size + 1}
                        : new Object[] {eventId, me.getId(), me.getParticipationRevision(), Timestamp.from(after.appliedAt()),
                                Timestamp.from(after.appliedAt()), after.changeSetId(), size + 1});
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        return new AcknowledgmentPage(page.stream().map(r -> (AcknowledgmentView) r[0]).toList(),
                more ? ((AckCursor) page.getLast()[1]).encode() : null);
    }

    /** 077. 화면에 보인 대상 묶음(targetsHash)이 지금과 같을 때만 확인한다. 같은 확인을 다시 보내면 그대로 돌려준다. */
    @Transactional
    public AcknowledgmentView acknowledge(UUID ackId, UUID accountId, String targetsHash) {
        var found = jdbc.queryForList("SELECT event_id FROM change_acknowledgment WHERE id = ?", UUID.class, ackId);
        if (found.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        UUID eventId = found.getFirst();
        members.require(eventId, accountId);
        EventRow event = event(eventId, true);
        Member me = members.requireLocked(eventId, accountId);
        var rows = jdbc.query("""
                SELECT event_id, member_id, participation_revision, targets_hash, acknowledged_at, targets::text
                  FROM change_acknowledgment WHERE id = ? FOR UPDATE""",
                (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3), r.getString(4),
                        r.getTimestamp(5), r.getString(6)}, ackId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        Object[] ack = rows.getFirst();
        if (!me.getId().equals(ack[1]) || !me.getParticipationRevision().equals(ack[2]))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!ack[3].equals(targetsHash))
            throw new BusinessException(ErrorCode.INVALID_STATE, "확인할 변경 내용이 바뀌었습니다. 다시 조회해 주세요.");
        if (ack[4] == null) {
            if (MAPPER.readTree((String) ack[5]).isEmpty()) throw new BusinessException(ErrorCode.INVALID_STATE);
            if (!RUNNING.contains(event.state())) throw new BusinessException(ErrorCode.INVALID_STATE);
            jdbc.update("UPDATE change_acknowledgment SET acknowledged_at = ?, acknowledged_targets = targets WHERE id = ?",
                    Timestamp.from(now()), ackId);
        }
        return jdbc.query("""
                SELECT id, participation_revision, targets::text, targets_hash, acknowledged_at FROM change_acknowledgment
                 WHERE id = ?""", (r, n) -> ackView(r), ackId).getFirst();
    }

    // ---- 내부 ------------------------------------------------------------------------------------------------

    private static final String COLUMNS = """
            id, event_id, issue_id, source_job_id, mode, status, revision, base_revision, operations::text, diff::text,
            preview_hash, rationale, evidence_refs::text, simulation_refs::text""";

    private static Row row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Row(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                r.getObject(4, UUID.class), r.getString(5), r.getString(6), r.getLong(7), r.getLong(8), r.getString(9),
                r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getString(14));
    }

    private Row row(UUID id, boolean lock) {
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM change_set WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> row(r), id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private ChangeSetView view(Row r, long eventRevision) {
        JsonNode[] ai = aiRecords(r);
        var snapshots = jdbc.queryForList("""
                SELECT snapshot::text FROM change_set_evidence_revision WHERE change_set_id = ? AND revision <= ?
                 ORDER BY revision DESC LIMIT 1""", String.class, r.id(), r.revision());
        return new ChangeSetView(r.id(), r.eventId(), r.status(), Long.toString(r.revision()), Long.toString(r.baseRevision()),
                r.mode(), MAPPER.readTree(r.operations()), r.rationale(), r.sourceJobId(), r.issueId(), r.previewHash(),
                // JSONB는 키 순서를 바꾸므로 계약 순서(operationIndex·type·targetId·summary)로 다시 만든다.
                MAPPER.valueToTree(MAPPER.readValue(r.diff() == null ? "[]" : r.diff(), DiffItem[].class)),
                Long.toString(eventRevision),
                MAPPER.readTree(r.evidenceRefs() == null ? "[]" : r.evidenceRefs()),
                MAPPER.readTree(r.simulationRefs() == null ? "[]" : r.simulationRefs()),
                snapshots.isEmpty() ? null : MAPPER.readTree(snapshots.getFirst()),
                ai[0], ai[1]);
    }

    /**
     * AI 변경안이면 만든 대응안 작업 결과의 참고 기록 [alternatives, coordination]을 한 번에 읽는다(목록에서 건마다 한 번).
     * 수동 변경안·기록 없음은 null이다.
     */
    private JsonNode[] aiRecords(Row r) {
        if (!"AI".equals(r.mode()) || r.sourceJobId() == null) return new JsonNode[2];
        var rows = jdbc.query("""
                SELECT (result -> 'alternatives')::text, (result -> 'coordination')::text FROM ai_job
                 WHERE id = ? AND kind = 'GENERATE_CHANGE'""",
                (rs, n) -> new JsonNode[] {json(rs.getString(1)), json(rs.getString(2))}, r.sourceJobId());
        return rows.isEmpty() ? new JsonNode[2] : rows.getFirst();
    }

    private static JsonNode json(String text) {
        return text == null ? null : MAPPER.readTree(text);
    }

    private static ArrayNode simulationRefs(Row row) {
        return (ArrayNode) MAPPER.readTree(row.simulationRefs() == null ? "[]" : row.simulationRefs());
    }

    /**
     * 고른 복구 후보(simulationRefs, 최대 1개)가 지금도 쓸 수 있는지 본다(V9-D44). 같은 행사 결과·inputHash·후보가 있어야 하고,
     * 변경안 연산이 그 후보의 연산과 같아야 한다(다른 후보로 자동 대체하지 않는다). 만료(완료 7일 뒤)는 생성·편집에서 422,
     * 미리보기·적용에서 410 RECOVERY_RESULT_EXPIRED다.
     */
    private void requireCandidate(UUID eventId, ArrayNode refs, ArrayNode operations, boolean review) {
        if (refs == null || refs.isEmpty()) return;
        JsonNode ref = refs.get(0);
        var rows = jdbc.query("SELECT input_hash, calculated_at, candidates::text FROM recovery_simulation WHERE id = ? AND event_id = ?",
                (r, n) -> new Object[] {r.getString(1), r.getTimestamp(2).toInstant(), r.getString(3)},
                UUID.fromString(ref.get("simulationId").asString()), eventId);
        if (rows.isEmpty()) throw reference("simulationRefs[0].simulationId");
        Object[] sim = rows.getFirst();
        if (!now().isBefore(((Instant) sim[1]).plus(RecoveryService.LIFETIME))) {
            if (review) throw new BusinessException(ErrorCode.RECOVERY_RESULT_EXPIRED);
            throw new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.RECOVERY_RESULT_EXPIRED.getMessage(),
                    Map.of("field", "simulationRefs[0].simulationId", "reason", "RECOVERY_RESULT_EXPIRED"));
        }
        if (!sim[0].equals(ref.get("inputHash").asString())) throw reference("simulationRefs[0].inputHash");
        JsonNode candidate = null;
        for (JsonNode c : MAPPER.readTree((String) sim[2]))
            if (c.path("candidateId").asString().equals(ref.get("candidateId").asString())) candidate = c;
        if (candidate == null) throw reference("simulationRefs[0].candidateId");
        if (!candidate.path("operations").equals(operations))
            throw new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                    Map.of("field", "operations", "reason", "CANDIDATE_MISMATCH"));
    }

    /** 저장점 안에서 연산을 반영해 검사·diff를 얻고 되돌린다. 실패하면 오류를 그대로 던진다(트랜잭션 전체가 되돌아간다). */
    private OperationEngine.Outcome dryRun(UUID eventId, UUID changeSetId, UUID actor, ArrayNode operations) {
        jdbc.execute("SAVEPOINT change_set_preview");
        OperationEngine.Outcome outcome = engine.apply(eventId, changeSetId, actor, operations, now());
        jdbc.execute("ROLLBACK TO SAVEPOINT change_set_preview");
        jdbc.execute("RELEASE SAVEPOINT change_set_preview");
        return outcome;
    }

    /** R2: 이 revision의 근거(확인 요약·대상·버전·확인 시각·전제)와 채택한 복구 후보. 저장 뒤 고치지 않는다. */
    private void snapshot(UUID changeSetId, long revision, ArrayNode evidenceRefs) {
        Instant now = now();
        ObjectNode snap = MAPPER.createObjectNode();
        snap.put("changeSetId", changeSetId.toString());
        snap.put("changeSetRevision", Long.toString(revision));
        snap.put("capturedAt", now.toString());
        ArrayNode evidence = snap.putArray("evidence");
        for (JsonNode ref : evidenceRefs) {
            ObjectNode item = evidence.addObject();
            item.set("kind", ref.get("kind"));
            item.set("id", ref.get("id"));
            item.set("revision", ref.get("revision"));
            item.set("contentHash", ref.get("contentHash"));
            item.put("summary", ref.get("kind").asString() + " 근거");
            item.set("confirmedAt", ref.get("observedAt"));
            item.set("preconditions", ref.get("preconditions"));
        }
        snap.putArray("selections");
        jdbc.update("""
                INSERT INTO change_set_evidence_revision (change_set_id, revision, snapshot, captured_at)
                VALUES (?, ?, ?::jsonb, ?)""", changeSetId, revision, MAPPER.writeValueAsString(snap), Timestamp.from(now));
    }

    /** 적용 결과로 담당이 바뀌거나 업무가 바뀐 활성 Member마다 본인 확인 대상을 만든다. */
    private void acknowledgments(UUID eventId, UUID changeSetId, Map<UUID, Map<UUID, OperationEngine.Target>> targets,
            Instant now) {
        for (var entry : targets.entrySet()) {
            var member = jdbc.queryForList("SELECT participation_revision FROM member WHERE id = ? AND event_id = ? AND active",
                    Long.class, entry.getKey(), eventId);
            if (member.isEmpty()) continue;
            List<AckTarget> list = new ArrayList<>();
            entry.getValue().forEach((taskId, t) -> list.add(new AckTarget(taskId, t.title(), List.copyOf(t.kinds()),
                    t.removed(), now.toString())));
            String json = MAPPER.writeValueAsString(list);
            UUID ackId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO change_acknowledgment (id, event_id, change_set_id, member_id, participation_revision, targets,
                                                       targets_hash, acknowledged_at, acknowledged_targets)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NULL, NULL)""",
                    ackId, eventId, changeSetId, entry.getKey(), member.getFirst(), json, sha256(canonical(MAPPER.readTree(json))));
            notifications.write(new ChangeAcknowledgmentNotificationWriter.Input(eventId, changeSetId, ackId,
                    entry.getKey(), member.getFirst(), now));
        }
    }

    private AcknowledgmentView ackView(java.sql.ResultSet r) throws java.sql.SQLException {
        List<AckTarget> targets = new ArrayList<>();
        for (JsonNode t : MAPPER.readTree(r.getString(3))) {
            List<String> kinds = new ArrayList<>();
            for (JsonNode k : t.get("changeKinds")) kinds.add(k.asString());
            targets.add(new AckTarget(UUID.fromString(t.get("taskId").asString()), t.get("title").asString(), kinds,
                    t.get("removed").asBoolean(), t.get("changedAt").asString()));
        }
        Timestamp acknowledged = r.getTimestamp(5);
        String state = acknowledged != null ? "ACKNOWLEDGED" : targets.isEmpty() ? "NO_CURRENT_TARGETS" : "PENDING";
        return new AcknowledgmentView(r.getObject(1, UUID.class), Long.toString(r.getLong(2)), state, r.getString(4), targets,
                acknowledged == null ? null : acknowledged.toInstant().toString());
    }

    private EventRow event(UUID eventId, boolean lock) {
        return jdbc.query("SELECT state, revision FROM event WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new EventRow(r.getString(1), r.getLong(2)), eventId).getFirst();
    }

    /** 미리보기·적용은 모집 중·운영 중 행사에서만(be/04) */
    private EventRow runningEvent(UUID eventId) {
        EventRow event = event(eventId, true);
        if (!RUNNING.contains(event.state()))
            throw new BusinessException(ErrorCode.INVALID_STATE, "지금 행사 상태에서는 변경을 적용할 수 없습니다.");
        return event;
    }

    private long eventRevision(UUID eventId) {
        return jdbc.queryForObject("SELECT revision FROM event WHERE id = ?", Long.class, eventId);
    }

    private static void requireOpen(Row row) {
        if (!Set.of("DRAFT", "READY").contains(row.status()))
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 적용했거나 폐기한 변경안입니다.");
    }

    /** 사람이 본 변경안 revision·행사 revision·연산·diff를 묶은 값. 적용 때 그대로 다시 보내야 한다. */
    static String previewHash(UUID changeSetId, long revision, long baseRevision, ArrayNode operations, List<DiffItem> diff) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("changeSetId", changeSetId.toString());
        content.put("revision", revision);
        content.put("baseRevision", baseRevision);
        content.put("operations", operations);
        content.put("diff", MAPPER.readTree(MAPPER.writeValueAsString(diff)));
        return sha256(canonical(MAPPER.valueToTree(content)));
    }

    /** 키를 정렬한 JSON 문자열 */
    static String canonical(JsonNode node) {
        if (node == null) return "null";
        return MAPPER.writeValueAsString(sorted(MAPPER.convertValue(node, Object.class)));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(sorted(v)));
            return out;
        }
        return value;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 생성 시각(밀리초)이 앞에 오는 UUID(v7 형식). 목록을 최신순으로 넘길 때 ID 순서를 쓴다. */
    static UUID timeOrderedId() {
        long millis = System.currentTimeMillis();
        long high = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFF);
        long low = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low);
    }

    private record AckCursor(Instant appliedAt, UUID changeSetId) {
        String encode() {
            long micros = ChronoUnit.MICROS.between(Instant.EPOCH, appliedAt);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(("ack1|" + micros + "|" + changeSetId).getBytes(StandardCharsets.UTF_8));
        }
        static AckCursor decode(String value) {
            if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3 || !"ack1".equals(parts[0]) || !parts[1].matches("-?[0-9]{1,19}")) throw invalid();
                UUID id = UUID.fromString(parts[2]);
                if (!id.toString().equals(parts[2])) throw invalid();
                return new AckCursor(Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS), id);
            } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException e) { throw invalid(); }
        }
    }

    private static String encodeCursor(UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID decodeCursor(String value) {
        if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (!raw.startsWith("v1|")) throw invalid();
            UUID id = UUID.fromString(raw.substring(3));
            if (!id.toString().equals(raw.substring(3))) throw invalid();
            return id;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessException conflict(String target) {
        return new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(), Map.of("target", target));
    }

    private static BusinessException reference(String field) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(), Map.of("field", field));
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}
