package com.ssafy.scenecrew.account.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.issue.service.AudioAssetService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 본인 동의 조회·변경(SC-API-017, #229·#334, V9-D40).
 * <ul>
 *   <li>서비스 이용 동의는 철회할 수 없다(409 INVALID_STATE). 이용을 멈추려면 탈퇴한다.</li>
 *   <li>바뀐 값만 반영하고 버전·시각을 이력에 남긴다({@link ConsentRecords}). 다른 동의는 그대로 둔다.</li>
 *   <li>푸시 철회: 같은 트랜잭션에서 그 계정의 기기 알림 등록을 끈다.</li>
 *   <li>음성 철회: 새 전사 요청은 TranscriptionService가 거절하고, 늦게 온 전사 결과는 TranscribeKind가 버린다.
 *       커밋 뒤 아직 제출하지 않은 음성 원본·임시 전사를 지운다. 삭제 실패는 24시간 보관 만료 정리가 다시 지운다.</li>
 *   <li>외부 생성 AI: 지금 BE에 이 동의를 쓰는 기능이 없어 값과 이력만 저장한다.</li>
 * </ul>
 */
public final class AccountConsentService {
    private static final Logger log = LoggerFactory.getLogger(AccountConsentService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AudioAssetService audio;
    private final Clock clock;

    public AccountConsentService(JdbcTemplate jdbc, PlatformTransactionManager manager, AudioAssetService audio, Clock clock) {
        this.jdbc = jdbc;
        this.audio = audio;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public View read(SessionAuthenticator.Principal principal) {
        try {
            var rows = jdbc.queryForList("""
                    SELECT consent::text FROM account
                     WHERE id=? AND status='ACTIVE' AND signup_completed=true AND token_version=?
                    """, String.class, principal.accountId(), principal.tokenVersion());
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            return view(ConsentRecords.parse(rows.getFirst()));
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public Summary change(SessionAuthenticator.Principal principal, Map<String, Boolean> requested) {
        if (Boolean.FALSE.equals(requested.get("service")))
            throw new BusinessException(ErrorCode.INVALID_STATE, "서비스 이용 동의는 철회할 수 없습니다. 이용을 멈추려면 탈퇴해 주세요.");
        boolean[] voiceWithdrawn = {false};
        Summary result;
        try {
            result = transaction.execute(status -> {
                var rows = jdbc.queryForList("""
                        SELECT consent::text FROM account
                         WHERE id=? AND status='ACTIVE' AND signup_completed=true AND token_version=?
                         FOR UPDATE
                        """, String.class, principal.accountId(), principal.tokenVersion());
                if (rows.isEmpty()) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                ObjectNode before = ConsentRecords.parse(rows.getFirst());
                Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                String next = ConsentRecords.change(rows.getFirst(), requested, now);
                if (next == null) return summary(before);
                jdbc.update("UPDATE account SET consent=?::jsonb WHERE id=?", next, principal.accountId());
                ObjectNode after = ConsentRecords.parse(next);
                if (ConsentRecords.value(before, "push") && !ConsentRecords.value(after, "push")) {
                    jdbc.update("""
                            UPDATE device_token SET active=false, updated_at=?
                             WHERE member_id IN (SELECT id FROM member WHERE account_id=?) AND active=true
                            """, java.sql.Timestamp.from(now), principal.accountId());
                }
                voiceWithdrawn[0] = ConsentRecords.value(before, "voice") && !ConsentRecords.value(after, "voice");
                return summary(after);
            });
        } catch (BusinessException failure) {
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (voiceWithdrawn[0]) removeVoiceOriginals(principal.accountId());
        return result;
    }

    private void removeVoiceOriginals(UUID accountId) {
        try {
            var ids = jdbc.queryForList("""
                    SELECT a.id FROM audio_asset a JOIN member m ON m.id=a.uploader_member_id
                     WHERE m.account_id=? AND a.storage_key IS NOT NULL
                    """, UUID.class, accountId);
            for (UUID id : ids) {
                if (!audio.removeOriginal(id)) log.warn("음성 동의 철회 뒤 원본을 지우지 못했습니다. 보관 만료 정리가 다시 지웁니다: {}", id);
            }
        } catch (RuntimeException failure) {
            log.warn("음성 동의 철회 뒤 원본 정리 실패. 보관 만료 정리가 다시 지웁니다: {}", accountId, failure);
        }
    }

    private static Summary summary(ObjectNode root) {
        return new Summary(new Consents(ConsentRecords.value(root, "service"), ConsentRecords.value(root, "voice"),
                ConsentRecords.value(root, "push"), ConsentRecords.value(root, "externalAi")));
    }

    private static View view(ObjectNode root) {
        var history = new ArrayList<HistoryEntry>();
        JsonNode list = root.get("history");
        if (list != null && list.isArray()) {
            for (JsonNode e : list) {
                var values = new java.util.LinkedHashMap<String, Boolean>();
                JsonNode v = e.get("values");
                if (v != null && v.isObject())
                    for (String key : ConsentRecords.KEYS) if (v.has(key) && v.get(key).isBoolean()) values.put(key, v.get(key).asBoolean());
                history.add(new HistoryEntry(e.path("at").asString(), e.path("version").asString(), e.path("source").asString(), values));
            }
        }
        JsonNode version = root.get("version");
        return new View(summary(root).consents(), version != null && version.isString() ? version.asString() : null, history);
    }

    public record Consents(boolean service, boolean voice, boolean push, boolean externalAi) {}
    public record Summary(Consents consents) {}
    public record HistoryEntry(String at, String version, String source, Map<String, Boolean> values) {}
    public record View(Consents consents, String version, List<HistoryEntry> history) {}
}
