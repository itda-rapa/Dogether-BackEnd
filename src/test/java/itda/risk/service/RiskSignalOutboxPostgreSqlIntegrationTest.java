package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceEventCommand;
import itda.risk.contract.RiskSourceEventPublisher;
import itda.risk.contract.RiskSourceType;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
class RiskSignalOutboxPostgreSqlIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private RiskSourceEventPublisher publisher;
    @Autowired private RiskSignalOutboxClaimService claims;
    @Autowired private RiskSignalOutboxRelayWorker worker;
    @MockitoBean private RiskSignalKafkaPublisher kafkaPublisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("delete from risk_signal_outbox");
    }

    @Test
    void committedSourceTransactionStoresJsonOutboxEvent() {
        enqueueInTransaction(command(100L));

        assertThat(count()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select status from risk_signal_outbox where source_id = ?", String.class, 100L))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "select payload ->> 'signalType' from risk_signal_outbox where source_id = ?", String.class, 100L))
                .isEqualTo("USER_BLOCKED");
        assertThat(jdbc.queryForObject(
                "select payload -> 'metadata' ->> 'reasonCode' from risk_signal_outbox where source_id = ?", String.class, 100L))
                .isEqualTo("USER_REQUEST");
    }

    @Test
    void rolledBackSourceTransactionDoesNotLeaveOutboxEvent() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            publisher.enqueue(command(101L));
            status.setRollbackOnly();
        });

        assertThat(count()).isZero();
    }

    @Test
    void logicalSourceKeyIsIdempotentAcrossTransactions() {
        enqueueInTransaction(command(102L));
        UUID firstEventId = jdbc.queryForObject(
                "select event_id from risk_signal_outbox where source_id = ?", UUID.class, 102L);

        enqueueInTransaction(command(102L));

        assertThat(count()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select event_id from risk_signal_outbox where source_id = ?", UUID.class, 102L))
                .isEqualTo(firstEventId);
    }

    @Test
    void enqueueRequiresSourceTransaction() {
        assertThatThrownBy(() -> publisher.enqueue(command(103L)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

        assertThat(count()).isZero();
    }

    @Test
    void concurrentWorkersClaimEachRowOnlyOnce() throws Exception {
        enqueueInTransaction(dueCommand(104L));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<RiskSignalOutboxClaimService.ClaimedRiskSignal>> first =
                    executor.submit(() -> claimAfter(start));
            Future<List<RiskSignalOutboxClaimService.ClaimedRiskSignal>> second =
                    executor.submit(() -> claimAfter(start));
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).size()
                    + second.get(10, TimeUnit.SECONDS).size()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "select attempts from risk_signal_outbox where source_id = 104", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void staleClaimCanBeRecoveredAndOldWorkerIsFenced() {
        enqueueInTransaction(dueCommand(105L));
        RiskSignalOutboxClaimService.ClaimedRiskSignal first =
                claims.claim(1, Duration.ofMinutes(1)).getFirst();
        jdbc.update("update risk_signal_outbox set claimed_at = now() - interval '2 minutes' where id = ?",
                first.id());

        RiskSignalOutboxClaimService.ClaimedRiskSignal recovered =
                claims.claim(1, Duration.ofMinutes(1)).getFirst();

        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(recovered.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(claims.markSent(first)).isFalse();
        assertThat(claims.retry(first, Instant.now(), "old worker")).isFalse();
        assertThat(claims.fail(first, "old worker")).isFalse();
        assertThat(claims.markSent(recovered)).isTrue();
        assertThat(jdbc.queryForObject(
                "select status from risk_signal_outbox where id = ?", String.class, first.id()))
                .isEqualTo("SENT");
    }

    @Test
    void retryIsNotClaimedBeforeDueAndFailedIsTerminal() {
        enqueueInTransaction(dueCommand(106L));
        RiskSignalOutboxClaimService.ClaimedRiskSignal first =
                claims.claim(1, Duration.ofMinutes(1)).getFirst();
        Instant retryAt = Instant.now().plusSeconds(60);

        assertThat(claims.retry(first, retryAt, "TimeoutException")).isTrue();
        assertThat(claims.claim(1, Duration.ofMinutes(1))).isEmpty();
        jdbc.update("update risk_signal_outbox set next_retry_at = now() - interval '1 second' where id = ?",
                first.id());
        RiskSignalOutboxClaimService.ClaimedRiskSignal retry =
                claims.claim(1, Duration.ofMinutes(1)).getFirst();
        assertThat(claims.fail(retry, "retry limit exceeded")).isTrue();

        assertThat(claims.claim(1, Duration.ofMinutes(1))).isEmpty();
        assertThat(jdbc.queryForObject(
                "select status from risk_signal_outbox where id = ?", String.class, first.id()))
                .isEqualTo("FAILED");
    }

    @Test
    void v36CreatesSeparateDueAndStaleClaimIndexes() {
        List<String> indexes = jdbc.queryForList("""
                select indexname from pg_indexes
                 where schemaname = current_schema()
                   and tablename = 'risk_signal_outbox'
                """, String.class);

        assertThat(indexes).contains(
                "ix_risk_signal_outbox_due",
                "ix_risk_signal_outbox_stale_claim");
    }

    @Test
    void futureOccurredAtDoesNotDelayInitialPublishEligibility() {
        Instant occurredAt = Instant.now().plusSeconds(3_600);
        enqueueInTransaction(new RiskSourceEventCommand(
                RiskSourceType.USER_BLOCK, 107L, RiskSignalType.USER_BLOCKED,
                41L, 42L, occurredAt,
                Map.of("reasonCode", "USER_REQUEST")));

        assertThat(claims.claim(1, Duration.ofMinutes(1))).hasSize(1);
        assertThat(jdbc.queryForObject(
                "select payload ->> 'occurredAt' from risk_signal_outbox where source_id = 107",
                String.class)).isEqualTo(occurredAt.toString());
    }

    @Test
    void brokerFailureTransitionsToRetryThenSuccessfulRetryBecomesSent() {
        enqueueInTransaction(dueCommand(108L));
        doThrow(new RiskSignalKafkaPublisher.RiskSignalPublishException(
                "failed", new TimeoutException()))
                .doNothing()
                .when(kafkaPublisher).publish(any(), any());

        assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(0, 1, 0, 0));
        assertThat(jdbc.queryForObject(
                "select status from risk_signal_outbox where source_id = 108", String.class))
                .isEqualTo("RETRY");
        assertThat(jdbc.queryForObject(
                "select claim_token is null and claimed_at is null from risk_signal_outbox where source_id = 108",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "select last_error from risk_signal_outbox where source_id = 108", String.class))
                .isEqualTo("kafka publish failed: TimeoutException");

        jdbc.update("update risk_signal_outbox set next_retry_at = now() - interval '1 second' where source_id = 108");
        assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(1, 0, 0, 0));
        assertThat(jdbc.queryForObject(
                "select status = 'SENT' and attempts = 2 and published_at is not null "
                        + "and last_error is null from risk_signal_outbox where source_id = 108",
                Boolean.class)).isTrue();
    }

    private void enqueueInTransaction(RiskSourceEventCommand command) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> publisher.enqueue(command));
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from risk_signal_outbox", Long.class);
    }

    private List<RiskSignalOutboxClaimService.ClaimedRiskSignal> claimAfter(CountDownLatch start)
            throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent claim start timed out");
        }
        return claims.claim(1, Duration.ofMinutes(1));
    }

    private static RiskSourceEventCommand dueCommand(long sourceId) {
        return new RiskSourceEventCommand(
                RiskSourceType.USER_BLOCK, sourceId, RiskSignalType.USER_BLOCKED,
                41L, 42L, Instant.now().minusSeconds(60),
                Map.of("reasonCode", "USER_REQUEST"));
    }


    private static RiskSourceEventCommand command(long sourceId) {
        return new RiskSourceEventCommand(
                RiskSourceType.USER_BLOCK, sourceId, RiskSignalType.USER_BLOCKED,
                41L, 42L, Instant.parse("2026-08-24T09:30:00Z"),
                Map.of("reasonCode", "USER_REQUEST"));
    }
}
