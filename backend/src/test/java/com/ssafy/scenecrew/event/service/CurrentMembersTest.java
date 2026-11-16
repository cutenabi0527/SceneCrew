package com.ssafy.scenecrew.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.support.FileTestBeans;
import com.ssafy.scenecrew.support.TestRows;

/** S15P31A202-341: 로그인한 계정이 이 행사에서 누구인지 확인 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(FileTestBeans.class)
class CurrentMembersTest {
    @Autowired
    private CurrentMembers currentMembers;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    private TestRows rows;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        rows = new TestRows(jdbc);
        eventId = rows.event("DRAFT");
    }

    @Test
    void chiefPassesBothChecks() {
        UUID account = rows.account();
        UUID memberId = rows.member(eventId, account, "CHIEF", true);

        assertThat(inTx(() -> currentMembers.require(eventId, account)).getId()).isEqualTo(memberId);
        assertThat(inTx(() -> currentMembers.requireChief(eventId, account)).getEventRole()).isEqualTo(EventRole.CHIEF);
    }

    @Test
    void workerIsMemberButNotChief() {
        UUID account = rows.account();
        UUID memberId = rows.member(eventId, account, "WORKER", true);

        assertThat(inTx(() -> currentMembers.require(eventId, account)).getId()).isEqualTo(memberId);
        assertCode(() -> inTx(() -> currentMembers.requireChief(eventId, account)), ErrorCode.FORBIDDEN);
    }

    @Test
    void nonMemberIsNotFound() {
        UUID account = rows.account();

        assertCode(() -> inTx(() -> currentMembers.require(eventId, account)), ErrorCode.NOT_FOUND);
        assertCode(() -> inTx(() -> currentMembers.requireChief(eventId, account)), ErrorCode.NOT_FOUND);
    }

    @Test
    void inactiveMemberIsNotFound() {
        UUID account = rows.account();
        rows.member(eventId, account, "CHIEF", false);

        assertCode(() -> inTx(() -> currentMembers.require(eventId, account)), ErrorCode.NOT_FOUND);
        assertCode(() -> inTx(() -> currentMembers.requireChief(eventId, account)), ErrorCode.NOT_FOUND);
    }

    @Test
    void memberOfAnotherEventIsNotFoundHere() {
        UUID account = rows.account();
        rows.member(rows.event("DRAFT"), account, "CHIEF", true);

        assertCode(() -> inTx(() -> currentMembers.require(eventId, account)), ErrorCode.NOT_FOUND);
    }

    /** 확인과 업무 반영을 같은 트랜잭션에 두도록 트랜잭션 밖에서는 부를 수 없다. */
    @Test
    void mustBeCalledInsideTransaction() {
        UUID account = rows.account();
        rows.member(eventId, account, "CHIEF", true);

        assertThatThrownBy(() -> currentMembers.require(eventId, account))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Member inTx(Supplier<Member> call) {
        return transaction.execute(status -> call.get());
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
