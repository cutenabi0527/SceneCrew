package com.ssafy.scenecrew.account.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.issue.service.AudioAssetService;

/**
 * 탈퇴 삭제 요청의 익명화(#334, V9-D39). 탈퇴한 계정을 주기적으로 처리해 탈퇴 후 24시간 안에 끝낸다.
 * <ul>
 *   <li>계정·참여자 표시 이름을 '탈퇴한 사용자'로 바꾼다.</li>
 *   <li>이메일·비밀번호·소셜 연결(auth_identity), 기기 등록(device_token·발송 기록), 음성 원본을 지운다.</li>
 *   <li>보고 원문·채팅 내용·업무 기록은 익명 업무 사실로 남긴다.</li>
 * </ul>
 * 음성 원본 삭제가 실패하면 로그인 수단을 남겨 두어(처리 전 상태 유지) 다음 회차에 다시 시도한다.
 */
@Component
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class AccountAnonymizer {
    public static final String ANONYMOUS_NAME = "탈퇴한 사용자";
    private static final Logger log = LoggerFactory.getLogger(AccountAnonymizer.class);
    private static final int BATCH = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AudioAssetService audio;

    public AccountAnonymizer(JdbcTemplate jdbc, PlatformTransactionManager manager, AudioAssetService audio) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.audio = audio;
    }

    @Scheduled(initialDelayString="${scenecrew.account.deletion.interval:PT5M}",
            fixedDelayString="${scenecrew.account.deletion.interval:PT5M}")
    void scheduled() {
        try {
            int done = processPending();
            if (done > 0) log.info("탈퇴 계정 {}개를 익명화했습니다.", done);
        } catch (RuntimeException e) {
            log.warn("탈퇴 계정 익명화 중 오류", e);
        }
    }

    /** 접수 순으로 최대 BATCH개를 처리하고 완료한 수를 돌려준다. 시험은 직접 부른다. */
    public int processPending() {
        int done = 0;
        // 처리 전 = 탈퇴했는데 로그인 수단이나 이름이 아직 남은 계정. 익명화는 같은 트랜잭션에서 둘 다 정리한다.
        var pending = jdbc.queryForList("""
                SELECT a.id FROM account a
                 WHERE a.status='DISABLED' AND a.deactivated_at IS NOT NULL
                   AND (a.display_name<>? OR EXISTS (SELECT 1 FROM auth_identity i WHERE i.account_id=a.id))
                 ORDER BY a.deactivated_at LIMIT ?
                """, UUID.class, ANONYMOUS_NAME, BATCH);
        for (UUID accountId : pending) {
            try {
                if (anonymize(accountId)) done++;
            } catch (RuntimeException e) {
                log.warn("탈퇴 계정 익명화 실패, 다음 회차에 다시 시도합니다: {}", accountId, e);
            }
        }
        return done;
    }

    private boolean anonymize(UUID accountId) {
        var audioIds = jdbc.queryForList("""
                SELECT a.id FROM audio_asset a JOIN member m ON m.id=a.uploader_member_id
                 WHERE m.account_id=? AND a.storage_key IS NOT NULL
                """, UUID.class, accountId);
        boolean audioRemoved = true;
        for (UUID id : audioIds) audioRemoved &= audio.removeOriginal(id);
        if (!audioRemoved) return false;

        return Boolean.TRUE.equals(transaction.execute(status -> {
            var locked = jdbc.queryForList("""
                    SELECT id FROM account WHERE id=? AND status='DISABLED' AND deactivated_at IS NOT NULL FOR UPDATE
                    """, UUID.class, accountId);
            if (locked.isEmpty()) return false;
            jdbc.update("UPDATE account SET display_name=? WHERE id=?", ANONYMOUS_NAME, accountId);
            jdbc.update("UPDATE member SET display_name=? WHERE account_id=?", ANONYMOUS_NAME, accountId);
            jdbc.update("DELETE FROM device_installation WHERE account_id=?", accountId);
            jdbc.update("""
                    DELETE FROM notification_delivery WHERE device_token_id IN
                      (SELECT d.id FROM device_token d JOIN member m ON m.id=d.member_id WHERE m.account_id=?)
                    """, accountId);
            jdbc.update("DELETE FROM device_token WHERE member_id IN (SELECT id FROM member WHERE account_id=?)", accountId);
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?", accountId);
            return true;
        }));
    }
}
