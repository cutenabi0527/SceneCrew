package com.ssafy.scenecrew.safety.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.equipment.service.AutoObservations;

/**
 * 내 연결 목록(L01, #393·#394 V9-D58·D59). 내가 연결할 카메라·장비 칸과 내 노트북 수집기가 찾은 수신기를 돌려준다.
 *
 * <p>WORKER는 연결 담당 업무를 맡았거나 지금 자기가 연결해 둔 칸만, CHIEF는 행사의 모든 카메라와 자동 관측 장비를 본다.
 * 다른 칸·집계·서버 현재 화면·연결 식별자(linkId)·수집기 토큰·장비 IP는 넣지 않는다.
 * 수신기는 내가 연결 코드로 등록했고 폐기되지 않은 수집기의 것만이다.
 */
public class MyLinkService {
    private static final Set<String> OPEN_EVENT_STATES = Set.of("RECRUITING", "LIVE");
    private static final Set<String> START_TASK_STATES = Set.of("WAITING", "IN_PROGRESS");

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;

    public MyLinkService(JdbcTemplate jdbc, CurrentMembers members) {
        this.jdbc = jdbc;
        this.members = members;
    }

    public record MyLinks(List<CameraLink> cameras, List<EquipmentLink> equipment, List<Receiver> receivers) {
    }

    /**
     * @param link 지금 연결된 수집기·수신기·채널(없으면 null)
     * @param autoObservation 상태·핵심 값(장비 현황 조회 EXT01·03과 같다)
     */
    public record EquipmentLink(UUID id, String name, String revision, String autoObservationKind, boolean linkedByMe,
            boolean canLink, LinkTask linkTask, ReceiverRef link, AutoObservations.AutoObservation autoObservation) {
    }

    public record ReceiverRef(String collectorId, String receiverKey, int channel) {
    }

    /** 내 수집기가 찾은 수신기. hostLabel은 프로그램이 알린 노트북 이름, linkedAssetId는 연결된 장비 칸(없으면 null) */
    public record Receiver(String collectorId, String hostLabel, String receiverKey, String name, String model, int channels,
            Instant seenAt, UUID linkedAssetId) {
    }

    /**
     * @param linkedByMe 지금 이 사람이 연결해 둔 칸(다른 브라우저일 수도 있다)
     * @param calibrationRequired 구역 보정 전. 인원을 세지 않는다
     * @param canLink 지금 연결을 시작할 수 있는지(시작 자격·행사 상태·사용 중)
     */
    public record CameraLink(UUID id, String name, String revision, boolean enabled, String connectionState, String inputKind,
            boolean linkedByMe, boolean calibrationRequired, boolean canLink, LinkTask linkTask, Instant lastReceivedAt) {
    }

    public record LinkTask(UUID id, String title, String status) {
    }

    @Transactional(readOnly = true)
    public MyLinks list(UUID eventId, UUID accountId) {
        Member me = members.require(eventId, accountId);
        boolean chief = me.getEventRole() == EventRole.CHIEF;
        String eventState = jdbc.queryForObject("SELECT state FROM event WHERE id = ?", String.class, eventId);
        boolean open = OPEN_EVENT_STATES.contains(eventState);
        String mine = chief ? "" : " AND (t.assignee_id = ? OR c.link_member_id = ?)";
        Object[] args = chief ? new Object[] { eventId } : new Object[] { eventId, me.getId(), me.getId() };
        var cameras = jdbc.query("""
                SELECT c.id, c.name, c.revision, c.enabled, c.connection_state, c.input_kind, c.link_member_id,
                       jsonb_array_length(c.coverage) = 0 AS calibration_required, c.last_received_at,
                       t.id AS task_id, t.title AS task_title, t.status AS task_status, t.assignee_id AS task_assignee
                  FROM safety_camera c LEFT JOIN task t ON t.id = c.link_task_id
                 WHERE c.event_id = ?""" + mine + " ORDER BY c.name COLLATE \"C\", c.id", (r, n) -> {
            UUID taskId = r.getObject("task_id", UUID.class);
            LinkTask task = taskId == null ? null : new LinkTask(taskId, r.getString("task_title"), r.getString("task_status"));
            boolean assignee = me.getId().equals(r.getObject("task_assignee", UUID.class))
                    && START_TASK_STATES.contains(r.getString("task_status"));
            boolean enabled = r.getBoolean("enabled");
            var received = r.getTimestamp("last_received_at");
            return new CameraLink(r.getObject("id", UUID.class), r.getString("name"), Long.toString(r.getLong("revision")),
                    enabled, r.getString("connection_state"), r.getString("input_kind"),
                    me.getId().equals(r.getObject("link_member_id", UUID.class)), r.getBoolean("calibration_required"),
                    open && enabled && (chief || assignee), task, received == null ? null : received.toInstant());
        }, args);
        return new MyLinks(List.copyOf(cameras), equipment(eventId, me, chief, open), receivers(me));
    }

    private List<EquipmentLink> equipment(UUID eventId, Member me, boolean chief, boolean open) {
        String mine = chief ? "" : " AND (t.assignee_id = ? OR a.link_member_id = ?)";
        Object[] args = chief ? new Object[] { eventId } : new Object[] { eventId, me.getId(), me.getId() };
        List<EquipmentLink> rows = jdbc.query("""
                SELECT a.id, a.name, a.revision, a.auto_observation_kind, a.link_member_id, a.link_collector_id,
                       a.link_source_ref, a.link_channel,
                       t.id AS task_id, t.title AS task_title, t.status AS task_status, t.assignee_id AS task_assignee
                  FROM equipment_asset a LEFT JOIN task t ON t.id = a.link_task_id
                 WHERE a.event_id = ? AND a.auto_observation_kind IS NOT NULL""" + mine + " ORDER BY a.name COLLATE \"C\", a.id",
                (r, n) -> {
                    UUID taskId = r.getObject("task_id", UUID.class);
                    LinkTask task = taskId == null ? null : new LinkTask(taskId, r.getString("task_title"), r.getString("task_status"));
                    boolean assignee = me.getId().equals(r.getObject("task_assignee", UUID.class))
                            && START_TASK_STATES.contains(r.getString("task_status"));
                    String collector = r.getString("link_collector_id");
                    return new EquipmentLink(r.getObject("id", UUID.class), r.getString("name"), Long.toString(r.getLong("revision")),
                            r.getString("auto_observation_kind"), me.getId().equals(r.getObject("link_member_id", UUID.class)),
                            open && (chief || assignee), task,
                            collector == null ? null : new ReceiverRef(collector, r.getString("link_source_ref"), r.getInt("link_channel")),
                            null);
                }, args);
        var auto = AutoObservations.of(jdbc, eventId, rows.stream().map(EquipmentLink::id).toList(), Instant.now());
        return rows.stream().map(e -> new EquipmentLink(e.id(), e.name(), e.revision(), e.autoObservationKind(), e.linkedByMe(),
                e.canLink(), e.linkTask(), e.link(), auto.get(e.id()))).toList();
    }

    private List<Receiver> receivers(Member me) {
        return List.copyOf(jdbc.query("""
                SELECT r.collector_id, c.host_label, r.receiver_key, r.name, r.model, r.channels, r.seen_at, a.id AS asset_id
                  FROM equipment_collector c
                  JOIN equipment_collector_receiver r ON r.collector_id = c.id
                  LEFT JOIN equipment_asset a ON a.link_collector_id = r.collector_id AND a.link_source_ref = r.receiver_key
                 WHERE c.member_id = ? AND c.revoked_at IS NULL
                 ORDER BY c.created_at, r.receiver_key""", (r, n) -> new Receiver(r.getString("collector_id"),
                r.getString("host_label"), r.getString("receiver_key"), r.getString("name"), r.getString("model"), r.getInt("channels"),
                r.getTimestamp("seen_at").toInstant(), r.getObject("asset_id", UUID.class)), me.getId()));
    }
}
