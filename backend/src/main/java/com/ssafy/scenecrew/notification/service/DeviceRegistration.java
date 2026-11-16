package com.ssafy.scenecrew.notification.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

@Service
public class DeviceRegistration {
    private final JdbcTemplate jdbc;
    private final PushSessionEligibility sessions;

    DeviceRegistration(JdbcTemplate jdbc, PushSessionEligibility sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Transactional
    public void register(Principal principal, UUID installation, String proof, String token) {
        byte[] proofHash = proofHash(proof);
        if (token.isEmpty() || token.length() > 4096 || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        UUID account = principal.accountId();
        boolean consent = lockAccount(principal);

        // The installation row is the serialization point across accounts, including the first claim.
        jdbc.update("""
                INSERT INTO device_installation(device_id, proof_hash, account_id)
                VALUES (?,?,?) ON CONFLICT (device_id) DO NOTHING
                """, installation, proofHash, account);
        Installation existing = verifyInstallation(installation, proofHash);
        UUID pushSession = "ANDROID".equals(principal.channel()) ? principal.sessionId() : null;
        if (pushSession != null && !sessions.isCurrent(pushSession, account, principal.tokenVersion())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        String deviceId = installation.toString();
        if (!consent) {
            // Account switching still revokes the previous owner. Do not collect the new token.
            jdbc.update("UPDATE device_installation SET account_id=?,push_session_id=NULL WHERE device_id=?",
                    account, installation);
            jdbc.update("UPDATE device_token SET active=false,updated_at=clock_timestamp() WHERE device_id=? AND active",
                    deviceId);
            return;
        }
        try {
            jdbc.update("UPDATE device_installation SET account_id=?, token_hash=?,push_session_id=? WHERE device_id=?",
                    account, digest(token.getBytes(StandardCharsets.UTF_8)), pushSession, installation);
        } catch (DuplicateKeyException failure) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        jdbc.update("""
                UPDATE device_token d SET active=false, updated_at=clock_timestamp()
                FROM member m WHERE m.id=d.member_id AND d.device_id=? AND d.active AND m.account_id<>?
                """, deviceId, account);
        jdbc.update("""
                INSERT INTO device_token(id,member_id,device_id,fcm_token,active,updated_at,participation_revision)
                SELECT gen_random_uuid(),m.id,?,?,true,clock_timestamp(),m.participation_revision
                FROM member m JOIN event e ON e.id=m.event_id
                WHERE m.account_id=? AND m.active AND e.state<>'ENDED'
                ON CONFLICT (member_id,device_id) DO UPDATE SET fcm_token=excluded.fcm_token,
                    active=true, updated_at=excluded.updated_at, participation_revision=excluded.participation_revision
                WHERE NOT device_token.active OR device_token.fcm_token IS DISTINCT FROM excluded.fcm_token
                    OR device_token.participation_revision IS DISTINCT FROM excluded.participation_revision OR ?
                """, deviceId, token, account, !Objects.equals(existing.pushSession(), pushSession));
    }

    @Transactional
    public void unregister(Principal principal, UUID installation, String proof) {
        byte[] proofHash = proofHash(proof);
        lockAccount(principal);
        UUID account = verifyInstallation(installation, proofHash).account();
        if (!account.equals(principal.accountId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        jdbc.update("UPDATE device_installation SET push_session_id=NULL WHERE device_id=?", installation);
        jdbc.update("""
                UPDATE device_token d SET active=false, updated_at=clock_timestamp()
                FROM member m WHERE m.id=d.member_id AND m.account_id=? AND d.device_id=? AND d.active
                """, account, installation.toString());
    }

    private boolean lockAccount(Principal principal) {
        var rows = jdbc.queryForList("""
                SELECT COALESCE(consent->'push'='true'::jsonb,false) FROM account
                WHERE id=? AND token_version=? AND status='ACTIVE' AND signup_completed FOR UPDATE
                """, Boolean.class, principal.accountId(), principal.tokenVersion());
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return rows.getFirst();
    }

    private Installation verifyInstallation(UUID id, byte[] proofHash) {
        var rows = jdbc.query("""
                SELECT proof_hash,account_id,push_session_id FROM device_installation WHERE device_id=? FOR UPDATE
                """, (rs, row) -> new Installation(rs.getBytes(1), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class)), id);
        if (rows.isEmpty() || !MessageDigest.isEqual(rows.getFirst().proofHash(), proofHash)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return rows.getFirst();
    }

    private static byte[] proofHash(String proof) {
        if (proof != null && proof.matches("[A-Za-z0-9_-]{43,171}")) {
            try {
                byte[] bytes = Base64.getUrlDecoder().decode(proof);
                if (bytes.length >= 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(proof)) {
                    return digest(bytes);
                }
            } catch (IllegalArgumentException invalidBase64) {
                // Use the same public error for malformed proof values.
            }
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private record Installation(byte[] proofHash, UUID account, UUID pushSession) { }
}
