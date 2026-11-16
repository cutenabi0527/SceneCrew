package com.ssafy.scenecrew.invite.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.ssafy.scenecrew.invite.dto.JoinRequest;

@Repository
public class InviteJoinRepository {
    private final JdbcTemplate jdbc;
    public InviteJoinRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID findEventId(String hash) {
        List<UUID> ids = jdbc.query("SELECT id FROM event WHERE invite_token_hash=?", (r,n) -> r.getObject(1, UUID.class), hash);
        // 기존 데이터의 해시가 모호하면 거절한다. 공용 스키마는 변경하지 않는다.
        return ids.size() == 1 ? ids.getFirst() : null;
    }
    public EventRow lockEvent(UUID id) {
        return jdbc.query("SELECT id, invite_token_hash, state, revision, total_capacity FROM event WHERE id=? FOR UPDATE",
                (r,n) -> new EventRow(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                        r.getLong(4), r.getObject(5, Long.class)), id).stream().findFirst().orElse(null);
    }
    public String lockAccount(UUID account, long tokenVersion) {
        return jdbc.query("""
                SELECT display_name FROM account WHERE id=? AND status='ACTIVE' AND signup_completed=true
                AND token_version=? FOR SHARE
                """, (r,n) -> r.getString(1), account, tokenVersion).stream().findFirst().orElse(null);
    }
    public MemberRow member(UUID event, UUID account) {
        return jdbc.query("""
                SELECT id, work_group_id, participation_revision, active, event_role FROM member
                WHERE event_id=? AND account_id=? FOR UPDATE
                """, (r,n) -> new MemberRow(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                        r.getLong(3), r.getBoolean(4), r.getString(5)), event, account).stream().findFirst().orElse(null);
    }
    public boolean lockGroup(UUID event, UUID group) {
        return !jdbc.query("SELECT id FROM work_group WHERE id=? AND event_id=? AND active=true FOR SHARE",
                (r,n) -> r.getObject(1, UUID.class), group, event).isEmpty();
    }
    public long activeCount(UUID event) {
        return jdbc.queryForObject("SELECT count(*) FROM member WHERE event_id=? AND active=true", Long.class, event);
    }
    public MemberRow create(UUID event, UUID account, String name, JoinRequest request) {
        UUID id = UUID.randomUUID();
        long number = jdbc.queryForObject("SELECT coalesce(max(crew_no), 0)+1 FROM member WHERE event_id=?", Long.class, event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,
                                    available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,'WORKER',?,?,?,true,1)
                """, id, event, account, request.workGroupId(), number, name, request.available(), request.isReserve());
        return new MemberRow(id, request.workGroupId(), 1, true, "WORKER");
    }
    public MemberRow rejoin(MemberRow old, String name, JoinRequest request) {
        long round = Math.addExact(old.round(), 1);
        jdbc.update("""
                UPDATE member SET work_group_id=?,event_role='WORKER',display_name=?,available=?,is_reserve=?,
                                  active=true,participation_revision=? WHERE id=?
                """, request.workGroupId(), name, request.available(), request.isReserve(), round, old.id());
        return new MemberRow(old.id(), request.workGroupId(), round, true, "WORKER");
    }
    public void advanceRevision(UUID event, long revision) {
        jdbc.update("UPDATE event SET revision=? WHERE id=?", revision, event);
    }
    public String assignment(UUID event, UUID member) {
        boolean assigned = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM task WHERE event_id=? AND assignee_id=? AND status IN ('WAITING','IN_PROGRESS'))
                """, Boolean.class, event, member));
        // 최초 배정 접수·상태 조회는 미연결이다. 업무 존재만으로 접수·완료를 보장하지 않는다.
        return assigned ? "ASSIGNED" : "UNKNOWN";
    }
    public record EventRow(UUID id, String hash, String state, long revision, Long capacity) { }
    public record MemberRow(UUID id, UUID group, long round, boolean active, String role) { }
}
