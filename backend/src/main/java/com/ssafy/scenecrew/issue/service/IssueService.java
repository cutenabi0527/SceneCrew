package com.ssafy.scenecrew.issue.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.aijob.pipeline.AiJobService;
import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.issue.ai.analysis.AnalyzeReportKind;
import com.ssafy.scenecrew.issue.dto.IssueResponses.AnalysisSummary;
import com.ssafy.scenecrew.issue.dto.IssueResponses.IssueCreated;
import com.ssafy.scenecrew.issue.dto.IssueResponses.IssuePage;
import com.ssafy.scenecrew.issue.dto.IssueResponses.IssueView;
import com.ssafy.scenecrew.issue.dto.IssueResponses.JobAccepted;
import com.ssafy.scenecrew.issue.dto.IssueResponses.ReportCreated;
import com.ssafy.scenecrew.issue.dto.IssueResponses.ReportView;
import com.ssafy.scenecrew.issue.dto.IssueResponses.SituationReceipt;
import com.ssafy.scenecrew.issue.dto.IssueResponses.SituationSuggestion;
import com.ssafy.scenecrew.issue.dto.IssueResponses.SituationView;
import com.ssafy.scenecrew.issue.dto.IssueResponses.ZoneCandidate;
import com.ssafy.scenecrew.notification.service.IssueNotificationWriter;

/**
 * #282 공식 현장 이슈·보고(055 목록, 056 최초 보고, 057 조회, 058 상태 변경, 059 추가·해결 보고, 062 분석 접수).
 * 오세진 2026-11-05. 규칙은 backend/docs/issue-report.md.
 *
 * <ul>
 *   <li>총괄은 행사의 모든 이슈·보고·분석을 본다. 팀원은 자기가 보고한 이슈와 자기에게 배정된 업무의 원인 이슈만 보고,
 *       그 안에서도 자기 보고만 받는다(analysis는 null). 볼 수 없는 이슈는 없는 것과 같은 404다.</li>
 *   <li>보고 원문은 불변이다. 같은 키 재요청 기록(receipt)에는 원문을 넣지 않고 응답할 때 Report 행에서 다시 채운다.</li>
 *   <li>보고(056·059)와 이슈 변경(058)은 AI 입력에 영향을 주므로 Event.revision을 1 올린다(POLICIES "보고/이슈 변화").
 *       분석 접수(062)와 분석 결과 반영은 올리지 않는다.</li>
 * </ul>
 */
@Service
public class IssueService {
    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;
    static final int TITLE_MAX = 50;
    static final int TEXT_MAX = 1000;
    /** 보고를 받는 행사 상태(2026-11-05 오세진 결정). 그 밖은 409 INVALID_STATE */
    private static final Set<String> REPORTABLE = Set.of("RECRUITING", "LIVE");
    private static final String STATUS_URL = "/api/v1/ai-jobs/";
    private static final String CURSOR_VERSION = "v1";
    private static final String ISSUE_COLUMNS = """
            i.id, i.event_id, i.work_group_id, i.task_id, i.zone_id, i.title, i.reporter_member_id, i.status,
            i.related_issue_id, i.created_at""";
    /** 팀원이 볼 수 있는 이슈: 자기 보고가 있거나, 자기에게 배정된 업무의 원인 이슈 */
    private static final String WORKER_VISIBLE = """
             (EXISTS (SELECT 1 FROM report r WHERE r.issue_id = i.id AND r.author_member_id = ?)
              OR EXISTS (SELECT 1 FROM task t WHERE t.source_issue_id = i.id AND t.assignee_id = ?))""";

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final AiJobService aiJobs;
    private final AnalyzeReportKind analysis;
    private final JsonMapper json;
    private final IssueNotificationWriter notifications;

    public IssueService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency, AiJobService aiJobs,
            AnalyzeReportKind analysis, JsonMapper json, IssueNotificationWriter notifications) {
        this.jdbc = jdbc;
        this.members = members;
        this.idempotency = idempotency;
        this.aiJobs = aiJobs;
        this.analysis = analysis;
        this.json = json;
        this.notifications = notifications;
    }

    /** 056 입력. workGroupId·taskId·relatedIssueId·safetyObservationId·quickTemplateKey·audioId는 없을 수 있다. */
    public record NewIssue(UUID zoneId, UUID workGroupId, UUID taskId, UUID relatedIssueId, UUID safetyObservationId,
            String text, String quickTemplateKey, UUID audioId) { }

    /** 059 입력 */
    public record NewReport(String kind, String text, String quickTemplateKey, UUID audioId) { }

    /** 058 입력. 바꿀 값만 null이 아니다. */
    public record IssueChange(String title, String status, long expectedEventRevision) { }

    /** EXT19 입력(V9SituationWrite). primaryType=null은 명시 미분류, relatedTypes=[]는 연관 없음이다. */
    public record SituationCorrection(String primaryType, List<String> relatedTypes, String reason,
            long expectedEventRevision) { }

    // ---- 조회 -------------------------------------------------------------------------------------------------

    /** 055 */
    @Transactional(readOnly = true)
    public IssuePage list(UUID eventId, UUID accountId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) throw invalid();
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        Member me = members.require(eventId, accountId);
        boolean chief = me.getEventRole() == EventRole.CHIEF;
        StringBuilder sql = new StringBuilder("SELECT " + ISSUE_COLUMNS + " FROM issue i WHERE i.event_id = ?");
        List<Object> args = new ArrayList<>(List.of(eventId));
        if (!chief) {
            sql.append(" AND").append(WORKER_VISIBLE);
            args.add(me.getId());
            args.add(me.getId());
        }
        if (after != null) {
            // 최신순. 같은 시각은 ID 내림차순.
            sql.append(" AND (i.created_at < ? OR (i.created_at = ? AND i.id < ?))");
            args.add(Timestamp.from(after.createdAt()));
            args.add(Timestamp.from(after.createdAt()));
            args.add(after.id());
        }
        sql.append(" ORDER BY i.created_at DESC, i.id DESC LIMIT ?");
        args.add(size + 1);
        List<IssueRow> rows = jdbc.query(sql.toString(), (r, n) -> IssueRow.of(r), args.toArray());
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        long revision = eventRevision(eventId);
        var views = views(page, me, revision);
        String next = more ? new Cursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
        return new IssuePage(views, next);
    }

    /** 057 */
    @Transactional(readOnly = true)
    public IssueView get(UUID issueId, UUID accountId) {
        IssueRow issue = issue(issueId, false);
        Member me = members.require(issue.eventId(), accountId);
        if (!visible(issue, me)) throw notFound();
        return views(List.of(issue), me, eventRevision(issue.eventId())).getFirst();
    }

    // ---- 056 최초 보고 -------------------------------------------------------------------------------------------

    @Transactional
    public IdempotentResult<IssueCreated> create(UUID eventId, UUID accountId, String key, NewIssue input) {
        members.require(eventId, accountId);
        lockEvent(eventId);
        Member me = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, "SC-API-056", key,
                IdempotencyRequest.hash(eventId, input.zoneId(), input.workGroupId(), input.taskId(),
                        input.relatedIssueId(), input.safetyObservationId(), input.text(), input.quickTemplateKey(),
                        input.audioId()),
                eventId, me.getId(), me.getParticipationRevision());
        var result = idempotency.run(request, IssueCreated.class, () -> {
            reportableEvent(eventId);
            UUID taskGroup = null;
            if (input.taskId() != null) {
                var task = jdbc.query("SELECT work_group_id, assignee_id FROM task WHERE id = ? AND event_id = ?",
                        (r, n) -> new UUID[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class)},
                        input.taskId(), eventId);
                // 팀원은 자기 업무에만 보고를 연결한다.
                if (task.isEmpty() || me.getEventRole() != EventRole.CHIEF && !me.getId().equals(task.getFirst()[1]))
                    throw reference("taskId");
                taskGroup = task.getFirst()[0];
            }
            UUID group = input.workGroupId() != null ? input.workGroupId()
                    : taskGroup != null ? taskGroup : me.getWorkGroupId();
            if (group == null) throw new BusinessException(ErrorCode.INVALID_REQUEST, "보고할 직무 그룹을 정해 주세요.");
            if (!exists("SELECT 1 FROM work_group WHERE id = ? AND event_id = ? AND active", group, eventId))
                throw reference("workGroupId");
            if (!exists("SELECT 1 FROM zone WHERE id = ? AND event_id = ?", input.zoneId(), eventId))
                throw reference("zoneId");
            if (input.relatedIssueId() != null) {
                var related = jdbc.query("SELECT " + ISSUE_COLUMNS + " FROM issue i WHERE i.id = ? AND i.event_id = ?",
                        (r, n) -> IssueRow.of(r), input.relatedIssueId(), eventId);
                if (related.isEmpty() || !visible(related.getFirst(), me)) throw reference("relatedIssueId");
            }
            // 안전 관측은 아직 이슈에 저장할 칸이 없다. 이 행사의 관측인지만 확인한다.
            if (input.safetyObservationId() != null
                    && !exists("SELECT 1 FROM safety_observation WHERE id = ? AND event_id = ?",
                            input.safetyObservationId(), eventId))
                throw reference("safetyObservationId");
            checkAudio(input.audioId(), eventId, me);

            Instant now = now();
            UUID issueId = UUID.randomUUID();
            String normalized = normalize(input.text());
            jdbc.update("""
                    INSERT INTO issue (id, event_id, work_group_id, task_id, zone_id, title, reporter_member_id, status,
                                       related_issue_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?)""",
                    issueId, eventId, group, input.taskId(), input.zoneId(), title(normalized), me.getId(),
                    input.relatedIssueId(), Timestamp.from(now));
            UUID reportId = insertReport(issueId, me, "INITIAL", input.text(), normalized, input.audioId(),
                    input.quickTemplateKey(), now);
            long revision = bumpRevision(eventId);
            // 분석 입력은 이 보고가 들어간 revision으로 만든다.
            UUID jobId = aiJobs.accept(eventId, me.getId(), AnalyzeReportKind.KIND, revision,
                    analysis.input(eventId, reportId, normalized, input.zoneId())).getId();
            notifications.reportReceived(eventId, issueId, reportId, now);

            IssueView view = views(List.of(issue(issueId, false)), me, revision).getFirst();
            ReportView report = view.reports().stream().filter(r -> r.id().equals(reportId)).findFirst().orElseThrow();
            Map<String, Object> refs = new LinkedHashMap<>();
            refs.put("issueId", issueId);
            refs.put("reportId", reportId);
            refs.put("jobId", jobId);
            return IdempotentResult.of(201, redact(new IssueCreated(view, report, jobId)), "ISSUE_CREATED", refs);
        });
        return withTexts(result, restore(result.body()));
    }

    // ---- 059 추가·해결 보고 --------------------------------------------------------------------------------------

    @Transactional
    public IdempotentResult<ReportCreated> addReport(UUID issueId, UUID accountId, String key, NewReport input) {
        IssueRow found = issue(issueId, false);
        members.require(found.eventId(), accountId);
        lockEvent(found.eventId());
        Member me = members.requireLocked(found.eventId(), accountId);
        IssueRow issue = issue(issueId, true);
        // 현재 조회 권한은 재요청에도 확인한다. 새 보고 자격은 최초 실행에만 검사한다.
        if (!visible(issue, me)) throw notFound();
        var request = new IdempotencyRequest(accountId, "SC-API-059", key,
                IdempotencyRequest.hash(issueId, input.kind(), input.text(), input.quickTemplateKey(), input.audioId()),
                issue.eventId(), me.getId(), me.getParticipationRevision());
        var result = idempotency.run(request, ReportCreated.class, () -> {
            if (!mayReport(issue, me)) {
                if (me.getEventRole() == EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
                throw notFound();
            }
            reportableEvent(issue.eventId());
            if ("CLOSED".equals(issue.status())) throw new BusinessException(ErrorCode.INVALID_STATE, "종료된 이슈입니다.");
            checkAudio(input.audioId(), issue.eventId(), me);
            Instant now = now();
            UUID reportId = insertReport(issueId, me, input.kind(), input.text(), normalize(input.text()),
                    input.audioId(), input.quickTemplateKey(), now);
            // 해결 보고는 접수·대응 중인 이슈를 "해결 보고됨"으로 바꾼다. 종료(CLOSED)는 총괄이 058로 한다(be/04 R04).
            if ("RESOLUTION".equals(input.kind()) && Set.of("RECEIVED", "RESPONDING").contains(issue.status()))
                jdbc.update("UPDATE issue SET status = 'RESOLUTION_REPORTED' WHERE id = ?", issueId);
            long revision = bumpRevision(issue.eventId());
            notifications.reportReceived(issue.eventId(), issueId, reportId, now);
            ReportView report = reportViews("r.id = ?", reportId).getFirst();
            Map<String, Object> refs = Map.of("issueId", issueId, "reportId", reportId);
            return IdempotentResult.of(201, new ReportCreated(report.withText(null), null,
                    Long.toString(revision)), "REPORT_CREATED", refs);
        });
        ReportCreated body = result.body();
        return withTexts(result, new ReportCreated(texts(List.of(body.report())).getFirst(), body.analysisJobId(),
                body.eventRevision()));
    }

    // ---- 058 상태 변경 ---------------------------------------------------------------------------------------

    @Transactional
    public IssueView change(UUID issueId, UUID accountId, IssueChange change) {
        IssueRow found = issue(issueId, false);
        members.requireChief(found.eventId(), accountId);
        lockEvent(found.eventId());
        Member chief = members.requireChiefLocked(found.eventId(), accountId);
        IssueRow issue = issue(issueId, true);
        EventRow event = reportableEvent(issue.eventId());
        if (event.revision() != change.expectedEventRevision())
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                    Map.of("target", "event"));
        if ("CLOSED".equals(issue.status())) throw new BusinessException(ErrorCode.INVALID_STATE, "종료된 이슈입니다.");
        boolean changed = false;
        String status = change.status();
        boolean statusChanged = status != null && !status.equals(issue.status());
        Instant statusChangedAt = null;
        if (statusChanged) {
            // 총괄은 대응 시작(RESPONDING)과 종료(CLOSED)만 정한다. 접수·해결 보고 상태는 보고가 만든다.
            if (!Set.of("RESPONDING", "CLOSED").contains(status))
                throw new BusinessException(ErrorCode.INVALID_STATE, "총괄이 바꿀 수 없는 상태입니다.");
            statusChangedAt = now();
            jdbc.update("UPDATE issue SET status = ?, closed_at = ? WHERE id = ?", status,
                    "CLOSED".equals(status) ? Timestamp.from(statusChangedAt) : null, issueId);
            changed = true;
        }
        if (change.title() != null && !change.title().equals(issue.title())) {
            jdbc.update("UPDATE issue SET title = ? WHERE id = ?", change.title(), issueId);
            changed = true;
        }
        // 같은 값만 보낸 요청은 바뀐 것이 없으므로 revision을 올리지 않는다.
        long revision = changed ? bumpRevision(issue.eventId()) : event.revision();
        if (statusChanged) notifications.statusChanged(issue.eventId(), issueId, status, revision, statusChangedAt);
        return views(List.of(issue(issueId, false)), chief, revision).getFirst();
    }

    // ---- 062 분석 접수 ---------------------------------------------------------------------------------------

    @Transactional
    public IdempotentResult<JobAccepted> analyze(UUID reportId, UUID accountId, String key, long expectedEventRevision) {
        var found = jdbc.query("""
                SELECT i.event_id, r.normalized_text, i.zone_id FROM report r JOIN issue i ON i.id = r.issue_id
                 WHERE r.id = ?""",
                (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getString(2), r.getObject(3, UUID.class)}, reportId);
        if (found.isEmpty()) throw notFound();
        UUID eventId = (UUID) found.getFirst()[0];
        members.requireChief(eventId, accountId);
        lockEvent(eventId);
        Member chief = members.requireChiefLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, "SC-API-062", key,
                IdempotencyRequest.hash(reportId, expectedEventRevision), eventId, chief.getId(),
                chief.getParticipationRevision());
        return idempotency.run(request, JobAccepted.class, () -> {
            EventRow event = reportableEvent(eventId);
            if (event.revision() != expectedEventRevision)
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                        Map.of("target", "event"));
            UUID jobId = aiJobs.accept(eventId, chief.getId(), AnalyzeReportKind.KIND, event.revision(),
                    analysis.input(eventId, reportId, (String) found.getFirst()[1], (UUID) found.getFirst()[2])).getId();
            return IdempotentResult.of(202, new JobAccepted(jobId, STATUS_URL + jobId), "ANALYSIS_ACCEPTED",
                    Map.of("reportId", reportId, "jobId", jobId));
        });
    }

    // ---- 공통 ------------------------------------------------------------------------------------------------

    private record EventRow(String state, long revision) { }

    record IssueRow(UUID id, UUID eventId, UUID workGroupId, UUID taskId, UUID zoneId, String title, UUID reporterMemberId,
            String status, UUID relatedIssueId, Instant createdAt) {
        static IssueRow of(java.sql.ResultSet r) throws java.sql.SQLException {
            return new IssueRow(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class),
                    r.getObject("work_group_id", UUID.class), r.getObject("task_id", UUID.class),
                    r.getObject("zone_id", UUID.class), r.getString("title"), r.getObject("reporter_member_id", UUID.class),
                    r.getString("status"), r.getObject("related_issue_id", UUID.class),
                    r.getTimestamp("created_at").toInstant());
        }
    }

    private IssueRow issue(UUID issueId, boolean lock) {
        var rows = jdbc.query("SELECT " + ISSUE_COLUMNS + " FROM issue i WHERE i.id = ?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> IssueRow.of(r), issueId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private EventRow reportableEvent(UUID eventId) {
        // 쓰기 명령은 행사 행을 잠가 기대 revision 확인과 revision 증가 사이의 경쟁을 막는다.
        EventRow event = jdbc.query("SELECT state, revision FROM event WHERE id = ? FOR UPDATE",
                (r, n) -> new EventRow(r.getString(1), r.getLong(2)), eventId).getFirst();
        if (!REPORTABLE.contains(event.state()))
            throw new BusinessException(ErrorCode.INVALID_STATE, "지금 행사 상태에서는 보고를 받을 수 없습니다.");
        return event;
    }

    private void lockEvent(UUID eventId) {
        jdbc.queryForObject("SELECT id FROM event WHERE id = ? FOR UPDATE", UUID.class, eventId);
    }

    private long bumpRevision(UUID eventId) {
        return jdbc.queryForObject("UPDATE event SET revision = revision + 1 WHERE id = ? RETURNING revision", Long.class,
                eventId);
    }

    private long eventRevision(UUID eventId) {
        return jdbc.queryForObject("SELECT revision FROM event WHERE id = ?", Long.class, eventId);
    }

    private boolean visible(IssueRow issue, Member me) {
        if (me.getEventRole() == EventRole.CHIEF) return true;
        return exists("SELECT 1 FROM issue i WHERE i.id = ? AND" + WORKER_VISIBLE, issue.id(), me.getId(), me.getId());
    }

    /** 보고 자격: 자기가 보고한 이슈, 또는 자기에게 배정된 업무의 원인 이슈(be/04 R04) */
    private boolean mayReport(IssueRow issue, Member me) {
        return me.getId().equals(issue.reporterMemberId())
                || exists("SELECT 1 FROM task WHERE source_issue_id = ? AND assignee_id = ?", issue.id(), me.getId());
    }

    /** 음성 보고면 이 행사에서 본인이 올린 음성이어야 하고, 한 음성은 한 보고에만 쓴다. */
    private void checkAudio(UUID audioId, UUID eventId, Member me) {
        if (audioId == null) return;
        if (!exists("SELECT 1 FROM audio_asset WHERE id = ? AND event_id = ? AND uploader_member_id = ?",
                audioId, eventId, me.getId()))
            throw reference("audioId");
        if (exists("SELECT 1 FROM report WHERE audio_id = ?", audioId))
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 보고에 쓴 음성입니다.");
    }

    private UUID insertReport(UUID issueId, Member author, String kind, String text, String normalized, UUID audioId,
            String quickTemplateKey, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO report (id, issue_id, author_member_id, kind, text, normalized_text, audio_id, quick_template_key,
                                    analysis, created_at, participation_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)""",
                id, issueId, author.getId(), kind, text, normalized, audioId, quickTemplateKey, Timestamp.from(now),
                author.getParticipationRevision());
        return id;
    }

    /** 이슈마다 보고(총괄은 전부, 팀원은 자기 것)와 분석(총괄만)을 붙인다. */
    private List<IssueView> views(List<IssueRow> issues, Member me, long revision) {
        if (issues.isEmpty()) return List.of();
        boolean chief = me.getEventRole() == EventRole.CHIEF;
        UUID[] ids = issues.stream().map(IssueRow::id).toArray(UUID[]::new);
        List<ReportView> reports = chief ? reportViews("r.issue_id = ANY(?)", (Object) ids)
                : reportViews("r.issue_id = ANY(?) AND r.author_member_id = ?", ids, me.getId());
        Map<UUID, List<ReportView>> byIssue = new HashMap<>();
        for (ReportView r : reports) byIssue.computeIfAbsent(r.issueId(), k -> new ArrayList<>()).add(r);
        Map<UUID, AnalysisSummary> analyses = chief ? analyses(ids, revision) : Map.of();
        // 운영 상황은 총괄에게만 보인다(V9-D48). 팀원 응답은 situation=null이다.
        Map<UUID, SituationView> situations = chief ? situations(ids) : Map.of();
        String rev = Long.toString(revision);
        return issues.stream().map(i -> new IssueView(i.id(), i.title(), i.status(), i.zoneId(),
                i.workGroupId(), i.taskId(), i.relatedIssueId(), byIssue.getOrDefault(i.id(), List.of()),
                analyses.get(i.id()), situations.get(i.id()), rev, i.eventId(), i.createdAt())).toList();
    }

    private List<ReportView> reportViews(String where, Object... args) {
        return jdbc.query("""
                SELECT r.id, r.issue_id, r.author_member_id, r.text, r.audio_id, r.kind, r.participation_revision,
                       r.created_at
                  FROM report r WHERE\s""" + where + " ORDER BY r.created_at, r.id",
                (r, n) -> new ReportView(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                        r.getString(4), r.getObject(5, UUID.class), r.getString(6), Long.toString(r.getLong(7)),
                        r.getTimestamp(8).toInstant()),
                args);
    }

    /**
     * 이슈의 분석 = 가장 최근에 작성된 보고의 분석(V9-D27). 그 보고의 분석이 아직 없으면(진행 중·실패) null이며
     * 이전 보고의 분석으로 대신하지 않는다. 분석 때 revision이 지금과 같으면 CURRENT, 다르면 STALE.
     * 후보별로 일부만 낡았는지(PARTIAL_STALE)는 아직 판단하지 않는다.
     */
    private Map<UUID, AnalysisSummary> analyses(UUID[] issueIds, long revision) {
        Map<UUID, AnalysisSummary> result = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (r.issue_id) r.issue_id, r.analysis::text FROM report r
                 WHERE r.issue_id = ANY(?)
                 ORDER BY r.issue_id, r.created_at DESC, r.id DESC""", rs -> {
            if (rs.getString(2) == null) return;
            JsonNode node = json.readTree(rs.getString(2));
            List<ZoneCandidate> candidates = new ArrayList<>();
            for (JsonNode c : node.path("zoneCandidates"))
                candidates.add(new ZoneCandidate(UUID.fromString(c.get("zoneId").asString()), c.get("source").asString(),
                        UUID.fromString(c.get("sourceRef").asString()), Long.toString(c.get("contextRevision").asLong())));
            String validity = node.path("contextRevision").asLong() == revision ? "CURRENT" : "STALE";
            result.put(rs.getObject(1, UUID.class), new AnalysisSummary(validity, textOrNull(node.get("category")),
                    textOrNull(node.get("reportedState")), candidates));
        }, (Object) issueIds);
        return result;
    }

    /**
     * 이슈의 운영 상황(V9-D48). 추천은 가장 최근에 작성된 보고의 분석 결과4 상황이다(V9-D27과 같은 기준).
     * 총괄이 정정한 행이 있으면 그 값이 우선하고, 추천은 suggestion으로 따로 보인다. 둘 다 없으면 null이다.
     */
    private Map<UUID, SituationView> situations(UUID[] issueIds) {
        Map<UUID, SituationSuggestion> suggestions = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (r.issue_id) r.issue_id, (r.analysis -> 'situation')::text FROM report r
                 WHERE r.issue_id = ANY(?)
                 ORDER BY r.issue_id, r.created_at DESC, r.id DESC""", rs -> {
            String raw = rs.getString(2);
            if (raw == null) return;
            JsonNode node = json.readTree(raw);
            if (!node.isObject()) return;
            suggestions.put(rs.getObject(1, UUID.class),
                    new SituationSuggestion(textOrNull(node.get("primary")), types(node.get("related"))));
        }, (Object) issueIds);
        Map<UUID, SituationView> result = new HashMap<>();
        jdbc.query("""
                SELECT issue_id, primary_type, related_types::text, confirmed_by_member_id, confirmed_at, reason
                  FROM operational_situation WHERE issue_id = ANY(?) AND confirmed_at IS NOT NULL""", rs -> {
            UUID issueId = rs.getObject(1, UUID.class);
            result.put(issueId, new SituationView(rs.getString(2), types(json.readTree(rs.getString(3))), true,
                    rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant(), rs.getString(6),
                    suggestions.get(issueId)));
        }, (Object) issueIds);
        suggestions.forEach((issueId, s) -> result.putIfAbsent(issueId,
                new SituationView(s.primaryType(), s.relatedTypes(), false, null, null, null, s)));
        return result;
    }

    private static List<String> types(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) for (JsonNode t : node) out.add(t.asString());
        return out;
    }

    // ---- EXT19 운영 상황 정정 -------------------------------------------------------------------------------

    /**
     * 총괄이 이슈의 운영 상황을 확인·정정한다(V9-D48). 최종값·확인자·시각·사유만 이슈당 1행에 덮어쓰고,
     * 보고별 AI 추천(Report.analysis)은 지우지 않는다. 대응안 입력이 바뀌므로 Event.revision을 1 올린다.
     */
    @Transactional
    public IdempotentResult<SituationReceipt> correctSituation(UUID issueId, UUID accountId, String key,
            SituationCorrection input) {
        IssueRow found = issue(issueId, false);
        members.requireChief(found.eventId(), accountId);
        lockEvent(found.eventId());
        Member chief = members.requireChiefLocked(found.eventId(), accountId);
        var request = new IdempotencyRequest(accountId, "SC-API-EXT19", key,
                IdempotencyRequest.hash(issueId, input.primaryType(), String.join(",", input.relatedTypes()),
                        input.reason(), input.expectedEventRevision()),
                found.eventId(), chief.getId(), chief.getParticipationRevision());
        return idempotency.run(request, SituationReceipt.class, () -> {
            EventRow event = reportableEvent(found.eventId());
            if (event.revision() != input.expectedEventRevision())
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                        Map.of("target", "event"));
            IssueRow issue = issue(issueId, true);
            if ("CLOSED".equals(issue.status())) throw new BusinessException(ErrorCode.INVALID_STATE, "종료된 이슈입니다.");
            Instant now = now();
            String refs = json.writeValueAsString(List.of(Map.of("kind", "ISSUE", "id", issueId.toString())));
            UUID id = jdbc.queryForObject("""
                    INSERT INTO operational_situation (id, event_id, issue_id, primary_type, related_types, source_kind,
                                                       source_refs, confirmed_by_member_id, confirmed_at, reason)
                    VALUES (?, ?, ?, ?, ?::jsonb, 'FORMAL_REPORT', ?::jsonb, ?, ?, ?)
                    ON CONFLICT (issue_id) WHERE issue_id IS NOT NULL DO UPDATE SET
                        primary_type = EXCLUDED.primary_type, related_types = EXCLUDED.related_types,
                        confirmed_by_member_id = EXCLUDED.confirmed_by_member_id,
                        confirmed_at = EXCLUDED.confirmed_at, reason = EXCLUDED.reason
                    RETURNING id""", UUID.class, UUID.randomUUID(), issue.eventId(), issueId, input.primaryType(),
                    json.writeValueAsString(input.relatedTypes()), refs, chief.getId(), Timestamp.from(now),
                    input.reason());
            String revision = Long.toString(bumpRevision(issue.eventId()));
            return IdempotentResult.of(200, new SituationReceipt(id, revision, revision), "SITUATION_CONFIRMED",
                    Map.of("issueId", issueId, "situationId", id));
        });
    }

    /** 재요청 기록에는 원문을 남기지 않는다(계약 056·059 "Report.text를 receipt에 복제하지 않는다"). */
    private static IssueCreated redact(IssueCreated body) {
        return new IssueCreated(body.issue().withReports(body.issue().reports().stream().map(r -> r.withText(null)).toList()),
                body.report().withText(null), body.analysisJobId());
    }

    /** 원문을 불변 Report 행에서 다시 채운다. 최초 응답과 재요청 응답이 같은 경로를 탄다. */
    private IssueCreated restore(IssueCreated body) {
        List<ReportView> reports = texts(body.issue().reports());
        ReportView report = texts(List.of(body.report())).getFirst();
        return new IssueCreated(body.issue().withReports(reports), report, body.analysisJobId());
    }

    private List<ReportView> texts(List<ReportView> reports) {
        if (reports.isEmpty()) return reports;
        Map<UUID, String> texts = new HashMap<>();
        jdbc.query("SELECT id, text FROM report WHERE id = ANY(?)", rs -> {
            texts.put(rs.getObject(1, UUID.class), rs.getString(2));
        }, (Object) reports.stream().map(ReportView::id).toArray(UUID[]::new));
        return reports.stream().map(r -> r.withText(Objects.requireNonNull(texts.get(r.id()), "보고 원문이 없습니다.")))
                .toList();
    }

    private static <T> IdempotentResult<T> withTexts(IdempotentResult<T> result, T body) {
        return new IdempotentResult<>(result.httpStatus(), body, result.resultType(), result.resultRefs(), result.replayed());
    }

    private boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql, args).isEmpty();
    }

    /** AI 보고 분석과 같은 정규화(NFC, 연속 공백 1개, 앞뒤 공백 제거). ai/report-api/contract.py normalize */
    public static String normalize(String text) {
        return UnicodeText.collapseWhitespace(Normalizer.normalize(text, Normalizer.Form.NFC));
    }

    /** 제목은 정규화한 원문의 앞 50자(코드포인트). 총괄이 058로 고친다(2026-11-05 오세진 결정). */
    static String title(String normalized) {
        return normalized.codePointCount(0, normalized.length()) <= TITLE_MAX ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, TITLE_MAX));
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessException notFound() { return new BusinessException(ErrorCode.NOT_FOUND); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
    private static BusinessException reference(String field) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                Map.of("field", field));
    }

    /** "v1|<생성 시각 마이크로초>|<이슈 ID>"를 base64url로 감싼 값. 형식이 다르면 400 */
    record Cursor(Instant createdAt, UUID id) {
        String encode() {
            long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((CURSOR_VERSION + "|" + micros + "|" + id).getBytes(StandardCharsets.UTF_8));
        }
        static Cursor decode(String value) {
            if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9_-]+")) throw invalid();
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3 || !CURSOR_VERSION.equals(parts[0]) || !parts[1].matches("-?[0-9]{1,19}")) throw invalid();
                UUID id = UUID.fromString(parts[2]);
                if (!id.toString().equals(parts[2])) throw invalid();
                return new Cursor(Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS), id);
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw invalid();
            }
        }
    }
}
