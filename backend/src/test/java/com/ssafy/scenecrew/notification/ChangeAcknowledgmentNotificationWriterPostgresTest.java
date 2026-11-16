package com.ssafy.scenecrew.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.outbox.OutboxRelay;
import com.ssafy.scenecrew.global.outbox.repository.OutboxEventRepository;
import com.ssafy.scenecrew.notification.dto.NotificationView;
import com.ssafy.scenecrew.notification.service.ChangeAcknowledgmentNotificationWriter.Input;
import com.ssafy.scenecrew.notification.service.ChangeAcknowledgmentNotificationWriter.Result;
import com.ssafy.scenecrew.notification.service.ChangeAcknowledgmentNotificationWriter;
import com.ssafy.scenecrew.notification.service.ChangePushDelivery;
import com.ssafy.scenecrew.notification.service.NotificationCursor;
import com.ssafy.scenecrew.notification.service.NotificationService;
import com.ssafy.scenecrew.notification.service.PushSender;
import com.ssafy.scenecrew.notification.service.PushSessionEligibility;
import com.ssafy.scenecrew.support.TestRows;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NotificationPostgresTest의 Account/Member/ack SQL fixture 형식을 사용한다. apply를 호출하거나 모방하지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChangeAcknowledgmentNotificationWriterPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ChangeAcknowledgmentNotificationWriter writer;
    @Autowired NotificationService inbox;
    @Autowired OutboxRelay relay;
    @Autowired OutboxEventRepository outboxEvents;
    @Autowired ChangePushDelivery pushDelivery;
    @PersistenceContext EntityManager entityManager;
    @MockitoBean PushSender push;
    @MockitoBean PushSessionEligibility sessions;
    @Autowired PlatformTransactionManager manager;
    TransactionTemplate tx;
    UUID account, otherAccount, event, otherEvent, member, otherMember, change, otherChange, ack;
    final Instant time = Instant.parse("2026-11-06T03:04:05.123456Z");

    @BeforeEach void fixture() {
        when(sessions.isCurrent(any(), any(), anyLong())).thenReturn(true);
        tx = new TransactionTemplate(manager);
        var rows=new TestRows(jdbc);
        account=rows.account(); otherAccount=rows.account(); event=rows.event("LIVE"); otherEvent=rows.event("LIVE");
        member=rows.member(event,account,"WORKER",true); otherMember=rows.member(event,otherAccount,"WORKER",true);
        change=UUID.randomUUID(); otherChange=UUID.randomUUID();
        ack=UUID.randomUUID();
        for (UUID id : List.of(account, otherAccount))
            jdbc.update("UPDATE account SET token_version=3 WHERE id=?",id);
        for (UUID id : List.of(event, otherEvent))
            jdbc.update("UPDATE event SET revision=7 WHERE id=?",id);
        for (UUID id : List.of(change, otherChange))
            jdbc.update("""
                    INSERT INTO change_set (id,event_id,mode,status,revision,base_revision,operations,rationale,applied_at)
                    VALUES (?,?,'MANUAL','APPLIED',1,7,'[]','fixture',?)
                    """,id,event,Timestamp.from(time));
        // 기존 #239의 읽음/공식 확인 분리 fixture와 같은 최소 ack. 실제 Task나 적용 알고리즘은 불필요하다.
        jdbc.update("""
                INSERT INTO change_acknowledgment (id,event_id,change_set_id,member_id,participation_revision,targets,targets_hash)
                VALUES (?,?,?,?,1,'[]','fixture-hash')
                """,ack,event,change,member);
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM outbox_event WHERE event_id IN (?,?)",event,otherEvent);
        jdbc.update("""
                DELETE FROM notification_delivery WHERE notification_id IN
                    (SELECT id FROM notification WHERE event_id IN (?,?))
                """,event,otherEvent);
        jdbc.update("DELETE FROM device_token WHERE member_id IN (?,?)",member,otherMember);
        jdbc.update("DELETE FROM device_installation WHERE account_id IN (?,?)",account,otherAccount);
        jdbc.update("DELETE FROM notification WHERE event_id IN (?,?)",event,otherEvent);
        jdbc.update("DELETE FROM change_acknowledgment WHERE event_id IN (?,?)",event,otherEvent);
        jdbc.update("DELETE FROM change_set WHERE event_id IN (?,?)",event,otherEvent);
        jdbc.update("DELETE FROM member WHERE event_id IN (?,?)",event,otherEvent);
        jdbc.update("DELETE FROM event WHERE id IN (?,?)",event,otherEvent);
        jdbc.update("DELETE FROM account WHERE id IN (?,?)",account,otherAccount);
    }

    Input input() { return new Input(event,change,ack,member,1,time); }
    Result write(Input input) { return tx.execute(status -> writer.write(input)); }
    int count() { return jdbc.queryForObject("SELECT count(*) FROM notification WHERE event_id=?",Integer.class,event); }
    Map<String,Object> ackRow() { return jdbc.queryForMap("SELECT * FROM change_acknowledgment WHERE id=?",ack); }

    @Test void pushWorkCommitsWithNotificationAndDoesNotRepeatOnReplay() {
        enablePush();
        device("eligible", 1L);
        device("legacy", null);
        device("old-round", 0L);
        tx.executeWithoutResult(status -> {
            writer.write(input());
            entityManager.flush();
            assertThat(pushWorkCount()).isEqualTo(1);
            verifyNoInteractions(push);
            status.setRollbackOnly();
        });
        assertThat(count()).isZero();
        assertThat(pushWorkCount()).isZero();
        var first = write(input());
        when(push.send("eligible", first.notificationId(), event))
                .thenReturn(new PushSender.Outcome(PushSender.Status.SENT, "synthetic-message"));

        assertThat(write(input()).created()).isFalse();
        relay.relayOnce();
        relay.relayOnce();

        verify(push).send("eligible", first.notificationId(), event);
        verifyNoMoreInteractions(push);
        assertThat(delivery(first.notificationId())).containsEntry("state", "SENT").containsEntry("attempt", 1L);
    }

    @ParameterizedTest
    @ValueSource(strings={"consent","participation","inactive","account","owner"})
    void checksCurrentEligibilityAgainBeforeSending(String change) {
        enablePush();
        UUID installation = device("eligible", 1L);
        var first = write(input());
        switch (change) {
            case "consent" -> jdbc.update("UPDATE account SET consent='{}'::jsonb WHERE id=?", account);
            case "participation" -> jdbc.update("UPDATE member SET participation_revision=2 WHERE id=?", member);
            case "inactive" -> jdbc.update("UPDATE member SET active=false WHERE id=?", member);
            case "account" -> jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", account);
            case "owner" -> jdbc.update("UPDATE device_installation SET account_id=? WHERE device_id=?",
                    otherAccount, installation);
            default -> throw new AssertionError(change);
        }
        relay.relayOnce();

        verifyNoInteractions(push);
        assertThat(delivery(first.notificationId())).containsEntry("state", "CANCELED").containsEntry("attempt", 0L);
    }

    @Test void retriesOnlyThreeTimesAndKeepsNotification() {
        enablePush();
        device("eligible", 1L);
        var first = write(input());
        when(push.send("eligible", first.notificationId(), event))
                .thenReturn(PushSender.Outcome.of(PushSender.Status.RETRY));
        for (int i = 0; i < 4; i++) {
            jdbc.update("UPDATE outbox_event SET available_at=? WHERE event_id=?",
                    Timestamp.from(Instant.now().minusSeconds(60)), event);
            relay.relayOnce();
        }

        verify(push, times(3)).send("eligible", first.notificationId(), event);
        assertThat(delivery(first.notificationId())).containsEntry("state", "FAILED").containsEntry("attempt", 3L);
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void unregisteredOnlyDisablesTheRegistrationThatWasSent(boolean rotated) {
        enablePush();
        UUID deviceId = device("eligible", 1L);
        var first = write(input());
        when(push.send("eligible", first.notificationId(), event)).thenAnswer(invocation -> {
            if (rotated) jdbc.update("""
                    UPDATE device_token SET fcm_token='replacement', updated_at=statement_timestamp() WHERE id=?
                    """, deviceId);
            return PushSender.Outcome.of(PushSender.Status.UNREGISTERED);
        });
        relay.relayOnce();

        assertThat(jdbc.queryForObject("SELECT active FROM device_token WHERE id=?", Boolean.class, deviceId))
                .isEqualTo(rotated);
        assertThat(delivery(first.notificationId())).containsEntry("state", "FAILED");
    }

    @Test void expiredWorkerCannotOverwriteTheReplacementWorkersResult() throws Exception {
        enablePush();
        UUID deviceId = device("eligible", 1L);
        var first = write(input());
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(push.send("eligible", first.notificationId(), event))
                .thenAnswer(invocation -> {
                    sending.countDown();
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                    return PushSender.Outcome.of(PushSender.Status.UNREGISTERED);
                })
                .thenReturn(new PushSender.Outcome(PushSender.Status.SENT, "new-worker-result"));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var oldWorker = workers.submit(relay::relayOnce);
            try {
                assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE outbox_event SET lease_until=now()-interval '1 second' WHERE event_id=?", event);
                jdbc.update("""
                        UPDATE notification_delivery SET lease_until=now()-interval '1 second' WHERE notification_id=?
                        """, first.notificationId());
                relay.relayOnce();
            } finally {
                release.countDown();
            }
            oldWorker.get(10, TimeUnit.SECONDS);
        }

        verify(push, times(2)).send("eligible", first.notificationId(), event);
        assertThat(delivery(first.notificationId())).containsEntry("state", "SENT").containsEntry("attempt", 2L);
        assertThat(jdbc.queryForObject("SELECT active FROM device_token WHERE id=?", Boolean.class, deviceId)).isTrue();
    }

    @Test void workWhoseBatchLeaseAlreadyExpiredRemainsRetryable() {
        enablePush();
        device("eligible", 1L);
        var first = write(input());
        UUID workId = jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_id=?", UUID.class, event);
        jdbc.update("UPDATE outbox_event SET lease_until=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(60)), workId);

        assertThatThrownBy(() -> pushDelivery.handle(outboxEvents.findById(workId).orElseThrow()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("담당권");
        verifyNoInteractions(push);

        when(push.send("eligible", first.notificationId(), event))
                .thenReturn(new PushSender.Outcome(PushSender.Status.SENT, "replacement-worker"));
        relay.relayOnce();
        verify(push).send("eligible", first.notificationId(), event);
        assertThat(delivery(first.notificationId())).containsEntry("state", "SENT").containsEntry("attempt", 1L);
    }

    private void enablePush() {
        jdbc.update("UPDATE account SET consent='{\"push\":true}'::jsonb WHERE id=?", account);
    }

    private UUID device(String token, Long revision) {
        UUID id = UUID.randomUUID();
        if (revision != null) jdbc.update("""
                INSERT INTO device_installation(device_id,proof_hash,account_id,push_session_id) VALUES (?,?,?,?)
                """, id, new byte[32], account, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO device_token(id,member_id,device_id,fcm_token,active,updated_at,participation_revision)
                VALUES (?,?,?,?,true,now(),?)
                """, id, member, id.toString(), token, revision);
        return id;
    }

    private int pushWorkCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_id=? AND type='CHANGE_ACK_PUSH'",
                Integer.class, event);
    }

    private Map<String,Object> delivery(UUID notificationId) {
        return jdbc.queryForMap("SELECT state,attempt FROM notification_delivery WHERE notification_id=?", notificationId);
    }

    @Test void storesExactReferencesAndMinimalPayloadOnce() {
        var before=ackRow();
        var result=write(input());
        assertThat(result.created()).isTrue();
        assertThat(count()).isEqualTo(1);
        var row=jdbc.queryForMap("SELECT * FROM notification WHERE id=?",result.notificationId());
        assertThat(row).containsEntry("event_id",event).containsEntry("change_set_id",change)
                .containsEntry("acknowledgment_id",ack).containsEntry("member_id",member)
                .containsEntry("participation_revision",1L).containsEntry("type","CHANGE_ACK")
                .containsEntry("dedupe_key","CHANGE_ACK:"+ack).containsEntry("task_id",null).containsEntry("read_at",null);
        assertThat(row.get("payload").toString()).isEqualTo("{}");
        assertThat(((Timestamp)row.get("created_at")).toInstant()).isEqualTo(time);
        assertThat(ackRow()).isEqualTo(before);
    }

    @Test void sameInputReturnsOriginalNotificationWithoutOverwritingReadOrAck() {
        var first=write(input());
        jdbc.update("UPDATE notification SET read_at=? WHERE id=?",Timestamp.from(time.plusSeconds(1)),first.notificationId());
        jdbc.update("UPDATE change_acknowledgment SET acknowledged_at=?, acknowledged_targets=targets WHERE id=?",
                Timestamp.from(time.plusSeconds(2)),ack);
        var before=ackRow();
        var second=write(input());
        assertThat(second).isEqualTo(new Result(first.notificationId(),false));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?",Timestamp.class,first.notificationId())
                .toInstant()).isEqualTo(time.plusSeconds(1));
        assertThat(ackRow()).isEqualTo(before);
    }

    @Test void differentAcknowledgmentsForSameMemberEachGetOneNotification() {
        UUID nextAck=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO change_acknowledgment (id,event_id,change_set_id,member_id,participation_revision,targets,targets_hash)
                VALUES (?,?,?,?,1,'[]','fixture-hash')
                """,nextAck,event,otherChange,member);
        var first=write(input());
        var second=write(new Input(event,otherChange,nextAck,member,1,time));
        assertThat(second.created()).isTrue();
        assertThat(second.notificationId()).isNotEqualTo(first.notificationId());
        assertThat(count()).isEqualTo(2);
    }

    @Test void subMicrosecondInputUsesStablePostgresPrecision() {
        var first=write(new Input(event,change,ack,member,1,time.plusNanos(321)));
        assertThat(write(input())).isEqualTo(new Result(first.notificationId(),false));
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings={"member_id","acknowledgment_id","change_set_id","event_id","participation_revision","type","payload","created_at"})
    void sameDedupeKeyWithDifferentStoredContentIsNotReplay(String field) {
        var first=write(input());
        switch(field) {
            case "member_id" -> jdbc.update("UPDATE notification SET member_id=? WHERE id=?",otherMember,first.notificationId());
            case "acknowledgment_id" -> {
                UUID otherAck=UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO change_acknowledgment (id,event_id,change_set_id,member_id,participation_revision,targets,targets_hash)
                        VALUES (?,?,?,?,1,'[]','fixture-hash')
                        """,otherAck,event,otherChange,member);
                jdbc.update("UPDATE notification SET acknowledgment_id=? WHERE id=?",otherAck,first.notificationId());
            }
            case "change_set_id" -> jdbc.update("UPDATE notification SET change_set_id=? WHERE id=?",otherChange,first.notificationId());
            case "event_id" -> jdbc.update("UPDATE notification SET event_id=? WHERE id=?",otherEvent,first.notificationId());
            case "participation_revision" -> jdbc.update("UPDATE notification SET participation_revision=2 WHERE id=?",first.notificationId());
            case "type" -> jdbc.update("UPDATE notification SET type='TASK' WHERE id=?",first.notificationId());
            case "payload" -> jdbc.update("UPDATE notification SET payload='{\"unexpected\":true}' WHERE id=?",first.notificationId());
            case "created_at" -> jdbc.update("UPDATE notification SET created_at=created_at+interval '1 second' WHERE id=?",first.notificationId());
            default -> throw new AssertionError(field);
        }
        var before=jdbc.queryForMap("SELECT * FROM notification WHERE id=?",first.notificationId());
        assertThatThrownBy(()->write(input())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM notification WHERE id=?",first.notificationId())).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings={"missingEvent","missingChange","missingAck","missingMember","wrongMember","wrongEvent","wrongChange","wrongRevision","inactiveMember","oldRound","crossEventAck"})
    void rejectsInvalidReferencesAndCurrentParticipation(String scenario) {
        Input in=switch(scenario) {
            case "missingEvent" -> new Input(UUID.randomUUID(),change,ack,member,1,time);
            case "missingChange" -> new Input(event,UUID.randomUUID(),ack,member,1,time);
            case "missingAck" -> new Input(event,change,UUID.randomUUID(),member,1,time);
            case "missingMember" -> new Input(event,change,ack,UUID.randomUUID(),1,time);
            case "wrongMember" -> new Input(event,change,ack,otherMember,1,time);
            case "wrongEvent" -> new Input(otherEvent,change,ack,member,1,time);
            case "wrongChange" -> new Input(event,otherChange,ack,member,1,time);
            case "wrongRevision" -> new Input(event,change,ack,member,2,time);
            default -> input();
        };
        switch(scenario) {
            case "inactiveMember" -> jdbc.update("UPDATE member SET active=false WHERE id=?",member);
            case "oldRound" -> jdbc.update("UPDATE member SET participation_revision=2 WHERE id=?",member);
            case "crossEventAck" -> jdbc.update("UPDATE change_acknowledgment SET event_id=? WHERE id=?",otherEvent,ack);
            default -> { }
        }
        assertThatThrownBy(()->write(in)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"INACTIVE","DISABLED","NOT_SIGNED_UP"})
    void ineligibleRecipientAccountRejectsWithInvalidState(String state) {
        if ("NOT_SIGNED_UP".equals(state)) jdbc.update("UPDATE account SET signup_completed=false WHERE id=?",account);
        else jdbc.update("UPDATE account SET status=? WHERE id=?",state,account);
        var before=ackRow();
        assertThatThrownBy(()->write(input())).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        assertThat(count()).isZero();
        assertThat(ackRow()).isEqualTo(before);
    }

    @Test void ineligibleRecipientMarksCallerRollbackOnlyEvenWhenCaught() {
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",account);
        var before=ackRow();
        assertThatThrownBy(()->tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE event SET revision=8 WHERE id=?",event);
            assertThatThrownBy(()->writer.write(input())).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(7L);
        assertThat(count()).isZero();
        assertThat(ackRow()).isEqualTo(before);
    }

    @Test void alreadyIneligibleAccountDoesNotWaitForAccountRowLock() throws Exception {
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",account);
        try (var owner=jdbc.getDataSource().getConnection()) {
            owner.setAutoCommit(false);
            try (var lock=owner.prepareStatement("SELECT id FROM account WHERE id=? FOR UPDATE")) {
                lock.setObject(1,account); lock.executeQuery().close();
            }
            try {
                assertThatThrownBy(()->tx.execute(status -> {
                    jdbc.execute("SET LOCAL lock_timeout='500ms'");
                    return writer.write(input());
                })).isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
                assertThat(count()).isZero();
            } finally { owner.rollback(); }
        }
    }

    @Test void requiresExistingTransaction() {
        assertThatThrownBy(()->writer.write(input())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count()).isZero();
    }

    @Test void callerRollbackAlsoRemovesNotification() {
        tx.executeWithoutResult(status -> { writer.write(input()); status.setRollbackOnly(); });
        assertThat(count()).isZero();
    }

    @Test void actualDatabaseInsertFailureRollsBackCallerEvenWhenCaught() {
        // 격리된 Testcontainers DB에서만 INSERT 직전 type을 NULL로 바꿔 실제 NOT NULL 실패를 만든다.
        String function="reject_345_"+UUID.randomUUID().toString().replace("-","");
        try {
            jdbc.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_id='"+event
                    +"'::uuid THEN NEW.type=NULL; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER "+function+" BEFORE INSERT ON notification FOR EACH ROW EXECUTE FUNCTION "+function+"()");
            assertThatThrownBy(()->tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE event SET revision=8 WHERE id=?",event);
                // 저장 예외를 상위에서 잡아도 MANDATORY 경계를 통해 rollback-only가 전파된다.
                assertThatThrownBy(()->writer.write(input())).isInstanceOf(DataIntegrityViolationException.class);
            })).isInstanceOf(UnexpectedRollbackException.class);
            assertThat(count()).isZero();
            assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(7L);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS "+function+" ON notification");
            jdbc.execute("DROP FUNCTION IF EXISTS "+function+"()");
        }
    }

    @Test void writerNotificationIsVisibleInExistingInboxAndReadDoesNotAck() {
        var before=ackRow();
        var result=write(input());
        var principal=new SessionAuthenticator.Principal(account,UUID.randomUUID(),UUID.randomUUID(),3,"WEB");
        var page=inbox.list(principal,null,30,new NotificationCursor(new byte[32]));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().id()).isEqualTo(result.notificationId());
        assertThat(page.items().getFirst().target()).isEqualTo(new NotificationView.Target("CHANGE_ACK",ack));
        assertThat(page.items().getFirst().title()).isEqualTo("변경 확인 알림");
        assertThat(page.items().getFirst().body()).isEmpty();
        assertThat(inbox.read(principal,result.notificationId()).readAt()).isNotNull();
        assertThat(ackRow()).isEqualTo(before);
    }

    @Test void concurrentDuplicateWaitsForUniqueWinnerAndReturnsItsId() throws Exception {
        var stored=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->tx.execute(status -> {
                var result=writer.write(input()); stored.countDown(); await(release); return result;
            }));
            assertThat(stored.await(10,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(()->tx.execute(status -> {
                jdbc.execute("SET LOCAL application_name='345-notification-contender'");
                return writer.write(input());
            }));
            try {
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='345-notification-contender' AND wait_event_type='Lock'",Integer.class)==0) {
                    if(System.nanoTime()>until) throw new AssertionError("경쟁 INSERT의 DB 잠금 대기를 관찰하지 못했습니다.");
                    Thread.sleep(20);
                }
            } finally { release.countDown(); }
            var winner=first.get(15,TimeUnit.SECONDS);
            assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(new Result(winner.notificationId(),false));
            assertThat(count()).isEqualTo(1);
        } finally { release.countDown(); }
    }

    private static void await(CountDownLatch latch) {
        try { if(!latch.await(15,TimeUnit.SECONDS)) throw new AssertionError("대기 시간 초과"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
