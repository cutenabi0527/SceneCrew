package com.ssafy.scenecrew.invite.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import com.ssafy.scenecrew.global.error.*;

public class InviteService {
    private final JdbcTemplate jdbc;
    private final InviteCipher cipher;
    private final String origin;
    public InviteService(JdbcTemplate jdbc, InviteCipher cipher, String origin) {
        this.jdbc = jdbc; this.cipher = cipher; this.origin = origin;
    }
    // Exclusive Event lock also serializes GET authorization with chief transfer, end, and rotation.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InviteView read(UUID event, UUID account) {
        var row = lock(event); activeAccount(account); chief(event, account);
        if (!open(row.state())) throw error(ErrorCode.NOT_FOUND);
        if (row.hash() == null && row.encrypted() == null) throw error(ErrorCode.NOT_FOUND);
        return view(event, row, cipher.decrypt(event, row.hash(), row.encrypted()));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InviteView rotate(UUID event, UUID account, long expected) {
        var row = lock(event); activeAccount(account); chief(event, account);
        if (!open(row.state())) throw error(ErrorCode.INVALID_STATE);
        if (row.revision() != expected) throw error(ErrorCode.VERSION_CONFLICT);
        // A broken existing ciphertext/key must not be silently repaired by rotation.
        if (row.encrypted() != null) cipher.decrypt(event, row.hash(), row.encrypted());
        String token = cipher.newToken(), hash = InviteJoinService.tokenHash(token);
        long next = Math.incrementExact(row.revision());
        jdbc.update("UPDATE event SET invite_token_hash=?, invite_token_ciphertext=?, revision=? WHERE id=?",
                hash, cipher.encrypt(event, hash, token), next, event);
        return new InviteView(event, origin + "/invites/" + token, Long.toString(next));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Options options(String token, UUID account) {
        String hash = InviteJoinService.tokenHash(token);
        var ids = jdbc.query("SELECT id FROM event WHERE invite_token_hash=?", (r,n) -> r.getObject(1,UUID.class), hash);
        if (ids.size() != 1) throw error(ErrorCode.NOT_FOUND);
        UUID event = ids.getFirst(); var row = lock(event); activeAccount(account);
        if (!hash.equals(row.hash()) || !open(row.state())) throw error(ErrorCode.NOT_FOUND);
        boolean room = row.capacity() != null && row.capacity() > 0 && row.capacity() <= 31
                && jdbc.queryForObject("SELECT count(*) FROM member WHERE event_id=? AND active=true", Long.class, event) < row.capacity();
        var groups = jdbc.query("""
                SELECT g.id, g.name, coalesce((SELECT string_agg(t.title, ' · ' ORDER BY t.start_at, t.id)
                  FROM task t WHERE t.event_id=g.event_id AND t.work_group_id=g.id AND t.status <> 'CANCELED'), '') summary
                FROM work_group g WHERE g.event_id=? AND g.active=true ORDER BY g.name, g.id
                """, (r,n) -> new Group(r.getObject(1,UUID.class), r.getString(2), r.getString(3), room), event);
        var mine = jdbc.query("""
                SELECT id, participation_revision, active, event_role, work_group_id FROM member
                WHERE event_id=? AND account_id=?
                """, (r,n) -> new Participation(r.getObject(1,UUID.class), Long.toString(r.getLong(2)),
                        r.getBoolean(3), r.getString(4), r.getObject(5,UUID.class)), event, account);
        return new Options(event, Long.toString(row.revision()), groups, mine.stream().findFirst().orElse(null));
    }
    private Row lock(UUID event) {
        return jdbc.query("SELECT state, revision, invite_token_hash, invite_token_ciphertext, total_capacity FROM event WHERE id=? FOR UPDATE",
                (r,n) -> new Row(r.getString(1),r.getLong(2),r.getString(3),r.getString(4),r.getObject(5,Long.class)), event)
                .stream().findFirst().orElseThrow(() -> error(ErrorCode.NOT_FOUND));
    }
    private void activeAccount(UUID account) {
        if (jdbc.query("SELECT id FROM account WHERE id=? AND status='ACTIVE' AND signup_completed=true FOR SHARE",
                (r,n) -> r.getObject(1,UUID.class), account).isEmpty()) throw error(ErrorCode.UNAUTHENTICATED);
    }
    private void chief(UUID event, UUID account) {
        var roles = jdbc.query("SELECT event_role FROM member WHERE event_id=? AND account_id=? AND active=true FOR UPDATE",
                (r,n) -> r.getString(1), event, account);
        if (roles.isEmpty()) throw error(ErrorCode.NOT_FOUND);
        if (!roles.getFirst().equals("CHIEF")) throw error(ErrorCode.FORBIDDEN);
    }
    private InviteView view(UUID event, Row row, String token) { return new InviteView(event, origin + "/invites/" + token, Long.toString(row.revision())); }
    private static boolean open(String state) { return state.equals("RECRUITING") || state.equals("LIVE"); }
    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
    private record Row(String state, long revision, String hash, String encrypted, Long capacity) { }
    public record InviteView(UUID eventId, String inviteUrl, String revision) {
        @Override public String toString() { return "InviteView[redacted]"; }
    }
    public record Group(UUID workGroupId, String name, String taskSummary, boolean selectable) { }
    public record Participation(UUID memberId, String participationRevision, boolean active, String eventRole, UUID workGroupId) { }
    public record Options(UUID eventId, String revision, List<Group> groups, Participation myParticipation) { }
}
