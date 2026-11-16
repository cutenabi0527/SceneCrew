package com.ssafy.scenecrew.invite.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import com.ssafy.scenecrew.event.service.InviteTokens;

/** Mandatory propagation keeps issuance inside the lifecycle transaction, including its receipt. */
public class StoredInviteTokens implements InviteTokens {
    private final JdbcTemplate jdbc;
    private final InviteCipher cipher;
    public StoredInviteTokens(JdbcTemplate jdbc, InviteCipher cipher) { this.jdbc = jdbc; this.cipher = cipher; }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void issue(UUID eventId) { prepare(eventId); }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void activate(UUID eventId) { prepare(eventId); }

    private void prepare(UUID id) {
        var row = jdbc.query("SELECT state, invite_token_hash, invite_token_ciphertext FROM event WHERE id=? FOR UPDATE",
                (r,n) -> new String[]{r.getString(1), r.getString(2), r.getString(3)}, id).stream().findFirst()
                .orElseThrow(InviteCipher::unavailable);
        // activate runs after DRAFT -> RECRUITING, still in the same transaction.
        if (!row[0].equals("DRAFT") && !row[0].equals("RECRUITING")) throw InviteCipher.unavailable();
        if (row[1] == null && row[2] == null) {
            String token = cipher.newToken(), hash = InviteJoinService.tokenHash(token);
            String encrypted = cipher.encrypt(id, hash, token);
            jdbc.update("UPDATE event SET invite_token_hash=?, invite_token_ciphertext=? WHERE id=?", hash, encrypted, id);
        } else {
            if (row[1] == null || row[2] == null) throw InviteCipher.unavailable();
            cipher.decrypt(id, row[1], row[2]);
        }
    }
}
