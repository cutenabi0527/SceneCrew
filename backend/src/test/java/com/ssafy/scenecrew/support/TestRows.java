package com.ssafy.scenecrew.support;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;

/** 시험에 필요한 계정·행사·인원 행을 SQL로 바로 넣는다. 각 시험이 새 id를 쓰므로 서로 겹치지 않는다. */
public final class TestRows {
    private static final AtomicLong CREW_NO = new AtomicLong(1);
    private final JdbcTemplate jdbc;

    public TestRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into account (id, display_name, status, signup_completed, token_version, consent, created_at)
                values (?, '시험 계정', 'ACTIVE', true, 0, '{}'::jsonb, now())
                """, id);
        return id;
    }

    public UUID event(String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into event (id, name, state, revision, last_input_modified_at) values (?, '시험 행사', ?, 1, now())",
                id, state);
        return id;
    }

    public UUID member(UUID eventId, UUID accountId, String role, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into member (id, event_id, account_id, crew_no, event_role, display_name, available, is_reserve,
                                    active, participation_revision)
                values (?, ?, ?, ?, ?, '시험 인원', true, false, ?, 1)
                """, id, eventId, accountId, CREW_NO.getAndIncrement(), role, active);
        return id;
    }

    public void setEventState(UUID eventId, String state) {
        jdbc.update("update event set state = ? where id = ?", state, eventId);
    }
}
