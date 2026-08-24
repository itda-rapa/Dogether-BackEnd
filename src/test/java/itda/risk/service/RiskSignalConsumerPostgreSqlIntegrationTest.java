package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;
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
class RiskSignalConsumerPostgreSqlIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private RiskSignalIngestionService ingestionService;
    @Autowired private RiskSignalAggregateService aggregateService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearEvents() {
        jdbc.update("delete from safety_case_evaluation_jobs");
        jdbc.update("delete from risk_signal_events");
    }

    @Test
    void storesEventOnceAndAggregatesPersistedPolicyScore() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-24T10:00:00Z");
        RiskSignalEventV1 event = new RiskSignalEventV1(
                1, eventId, RiskSourceType.USER_BLOCK, 501L,
                RiskSignalType.USER_BLOCKED, 41L, 42L, occurredAt,
                Map.of("reasonCode", "USER_REQUEST"));

        assertThat(ingestionService.ingest(event))
                .isEqualTo(RiskSignalIngestionService.Result.INSERTED);
        assertThat(ingestionService.ingest(event))
                .isEqualTo(RiskSignalIngestionService.Result.DUPLICATE);

        RiskSignalAggregate aggregate = aggregateService.forActorAndTarget(
                41L, 42L, occurredAt.minusSeconds(1), occurredAt.plusSeconds(1));
        assertThat(aggregate).isEqualTo(new RiskSignalAggregate(1, 30, occurredAt, occurredAt));
        assertThat(aggregateService.latestOccurredAtForActorAndTarget(41L, 42L, occurredAt))
                .contains(occurredAt);
        RiskSignalEvaluationAggregate evaluation = aggregateService.evaluationForActorAndTarget(
                41L, 42L, occurredAt.minusSeconds(1), occurredAt);
        assertThat(evaluation.signalCount()).isEqualTo(1);
        assertThat(evaluation.totalScore()).isEqualTo(30);
        assertThat(evaluation.firstDetectedAt()).isEqualTo(occurredAt);
        assertThat(evaluation.lastDetectedAt()).isEqualTo(occurredAt);
        assertThat(evaluation.lastEvaluatedEventId()).isPositive();
        assertThat(evaluation.primarySignalType()).isEqualTo("USER_BLOCKED");
        assertThat(jdbc.queryForObject(
                "select score_policy_version from risk_signal_events where event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void v38AddsRequiredUniqueConstraintAndIndexes() {
        assertThat(jdbc.queryForList("""
                select indexname from pg_indexes
                 where schemaname = current_schema()
                   and tablename = 'risk_signal_events'
                """, String.class)).contains(
                        "uk_risk_signal_events_event_id",
                        "ix_risk_signal_events_actor_occurred",
                        "ix_risk_signal_events_target_occurred",
                        "ix_risk_signal_events_actor_target_occurred");
    }

    @Test
    void concurrentRedeliveryStoresExactlyOneEvent() throws Exception {
        RiskSignalEventV1 event = blockEvent(UUID.randomUUID(), 701L, 41L, 42L,
                Instant.parse("2026-08-24T10:00:00Z"));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RiskSignalIngestionService.Result> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return ingestionService.ingest(event);
            });
            Future<RiskSignalIngestionService.Result> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return ingestionService.ingest(event);
            });
            start.countDown();

            assertThat(java.util.List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            RiskSignalIngestionService.Result.INSERTED,
                            RiskSignalIngestionService.Result.DUPLICATE);
        }
        assertThat(count()).isEqualTo(1L);
    }

    @Test
    void rollbackDoesNotConsumeIdempotencyKey() {
        RiskSignalEventV1 event = blockEvent(UUID.randomUUID(), 702L, 41L, 42L,
                Instant.parse("2026-08-24T10:00:00Z"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(ingestionService.ingest(event))
                    .isEqualTo(RiskSignalIngestionService.Result.INSERTED);
            status.setRollbackOnly();
        });

        assertThat(count()).isZero();
        assertThat(ingestionService.ingest(event))
                .isEqualTo(RiskSignalIngestionService.Result.INSERTED);
        assertThat(count()).isEqualTo(1L);
    }

    @Test
    void aggregatesActorAndTargetWithInclusiveExclusiveTimeRange() {
        Instant from = Instant.parse("2026-08-24T10:00:00Z");
        ingestionService.ingest(blockEvent(UUID.randomUUID(), 703L, 41L, 42L, from));
        ingestionService.ingest(greetingEvent(UUID.randomUUID(), 704L, 41L, 43L,
                from.plusSeconds(30)));
        ingestionService.ingest(blockEvent(UUID.randomUUID(), 705L, 99L, 42L,
                from.plusSeconds(60)));
        ingestionService.ingest(blockEvent(UUID.randomUUID(), 706L, 41L, 42L,
                from.plusSeconds(120)));

        assertThat(aggregateService.forActor(41L, from, from.plusSeconds(120)))
                .isEqualTo(new RiskSignalAggregate(2, 40, from, from.plusSeconds(30)));
        assertThat(aggregateService.forTarget(42L, from, from.plusSeconds(120)))
                .isEqualTo(new RiskSignalAggregate(2, 60, from, from.plusSeconds(60)));
        assertThat(aggregateService.forActorAndTarget(41L, 42L, from, from.plusSeconds(120)))
                .isEqualTo(new RiskSignalAggregate(1, 30, from, from));
        assertThat(aggregateService.forActor(777L, from, from.plusSeconds(120)))
                .isEqualTo(new RiskSignalAggregate(0, 0, null, null));
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from risk_signal_events", Long.class);
    }

    private static RiskSignalEventV1 blockEvent(
            UUID eventId, long sourceId, long actor, long target, Instant occurredAt
    ) {
        return new RiskSignalEventV1(
                1, eventId, RiskSourceType.USER_BLOCK, sourceId,
                RiskSignalType.USER_BLOCKED, actor, target, occurredAt,
                Map.of("reasonCode", "USER_REQUEST"));
    }

    private static RiskSignalEventV1 greetingEvent(
            UUID eventId, long sourceId, long actor, long target, Instant occurredAt
    ) {
        return new RiskSignalEventV1(
                1, eventId, RiskSourceType.GREETING, sourceId,
                RiskSignalType.GREETING_EXPIRED, actor, target, occurredAt,
                Map.of("ttlHours", "24"));
    }
}
