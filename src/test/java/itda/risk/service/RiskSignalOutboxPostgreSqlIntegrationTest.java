package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceEventCommand;
import itda.risk.contract.RiskSourceEventPublisher;
import itda.risk.contract.RiskSourceType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
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

    private void enqueueInTransaction(RiskSourceEventCommand command) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> publisher.enqueue(command));
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from risk_signal_outbox", Long.class);
    }

    private static RiskSourceEventCommand command(long sourceId) {
        return new RiskSourceEventCommand(
                RiskSourceType.USER_BLOCK, sourceId, RiskSignalType.USER_BLOCKED,
                41L, 42L, Instant.parse("2026-08-24T09:30:00Z"),
                Map.of("reasonCode", "USER_REQUEST"));
    }
}
