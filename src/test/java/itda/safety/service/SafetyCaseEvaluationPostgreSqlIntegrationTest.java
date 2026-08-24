package itda.safety.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import itda.risk.service.RiskSignalIngestionService;
import itda.safety.domain.SafetyEvaluationJobStatus;
import itda.safety.repository.SafetyEvaluationJobJdbcRepository;
import java.time.Duration;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration",
        "app.safety.evaluator.enabled=false",
        "app.safety.evaluator.threshold=60",
        "app.safety.evaluator.window=30d",
        "app.safety.evaluator.policy-version=7",
        "app.safety.evaluator.batch-size=10",
        "app.safety.evaluator.delay-ms=5000",
        "app.safety.evaluator.lease=1m",
        "app.safety.evaluator.max-attempts=3",
        "app.safety.evaluator.base-backoff=5s",
        "app.safety.evaluator.max-backoff=1m"
})
class SafetyCaseEvaluationPostgreSqlIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private RiskSignalIngestionService ingestion;
    @Autowired private SafetyCaseEvaluationWorker worker;
    @Autowired private SafetyEvaluationJobJdbcRepository jobs;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearSafetyData() {
        jdbc.update("delete from evidence_access_audits");
        jdbc.update("delete from safety_case_actions");
        jdbc.update("delete from safety_review_cases");
        jdbc.update("delete from safety_case_evaluation_jobs");
        jdbc.update("delete from risk_signal_events");
    }

    @Test
    void ingestionAtomicallyEnqueuesAndWorkerUsesInclusiveThreshold() {
        Instant now = Instant.now().minusSeconds(2);
        RiskSignalEventV1 first = event(UUID.randomUUID(), 501, now.minusSeconds(1));
        assertThat(ingestion.ingest(first)).isEqualTo(RiskSignalIngestionService.Result.INSERTED);
        assertThat(ingestion.ingest(first)).isEqualTo(RiskSignalIngestionService.Result.DUPLICATE);
        assertThat(count("risk_signal_events")).isEqualTo(1);
        assertThat(count("safety_case_evaluation_jobs")).isEqualTo(1);

        assertThat(worker.runOnce().completed()).isEqualTo(1);
        assertThat(count("safety_review_cases")).isZero();

        ingestion.ingest(event(UUID.randomUUID(), 502, now));
        assertThat(worker.runOnce().cases()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select total_score from safety_review_cases", Long.class)).isEqualTo(60);

        ingestion.ingest(event(UUID.randomUUID(), 503, now.plusSeconds(1)));
        assertThat(worker.runOnce().cases()).isEqualTo(1);
        assertThat(count("safety_review_cases")).isEqualTo(1);
        assertThat(jdbc.queryForMap("select total_score, signal_count, evaluation_policy_version, primary_signal_type from safety_review_cases"))
                .containsEntry("total_score", 90L)
                .containsEntry("signal_count", 3L)
                .containsEntry("evaluation_policy_version", 7)
                .containsEntry("primary_signal_type", "USER_BLOCKED");
    }

    @Test
    void openCaseSnapshotRefreshesWhenRollingScoreFallsBelowThreshold() {
        Instant anchor = Instant.now().minusSeconds(3);
        ingestion.ingest(event(UUID.randomUUID(), 511, anchor.minus(Duration.ofDays(31))));
        assertThat(worker.runOnce().cases()).isZero();

        ingestion.ingest(event(UUID.randomUUID(), 512, anchor.minus(Duration.ofDays(29))));
        assertThat(worker.runOnce().cases()).isEqualTo(1);
        long caseId = jdbc.queryForObject(
                "select id from safety_review_cases", Long.class);
        assertThat(jdbc.queryForObject(
                "select total_score from safety_review_cases", Long.class)).isEqualTo(60L);

        ingestion.ingest(greetingEvent(UUID.randomUUID(), 513, anchor));
        assertThat(worker.runOnce().cases()).isEqualTo(1);

        assertThat(jdbc.queryForMap("""
                select id, total_score, signal_count, primary_signal_type
                  from safety_review_cases
                """))
                .containsEntry("id", caseId)
                .containsEntry("total_score", 40L)
                .containsEntry("signal_count", 2L)
                .containsEntry("primary_signal_type", "USER_BLOCKED");
    }

    @Test
    void reconcileBackfillsExistingEventWithoutDuplicateJobs() {
        ingestion.ingest(event(UUID.randomUUID(), 601, Instant.now().minusSeconds(1)));
        jdbc.update("delete from safety_case_evaluation_jobs");

        assertThat(jobs.reconcileMissing(10)).isEqualTo(1);
        assertThat(jobs.reconcileMissing(10)).isZero();
        assertThat(count("safety_case_evaluation_jobs")).isEqualTo(1);
    }

    @Test
    void futureEventCannotBeClaimedBeforeOccurredAt() {
        Instant future = Instant.now().plusSeconds(30);
        ingestion.ingest(event(UUID.randomUUID(), 701, future));

        assertThat(jobs.claimOne("worker-a", future.minusSeconds(1), Duration.ofMinutes(1), 3))
                .isEmpty();
        var claimed = jobs.claimOne("worker-a", future.plusMillis(1), Duration.ofMinutes(1), 3);
        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().eventOccurredAt())
                .isCloseTo(future, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void staleClaimIsFencedAndFailedJobCanBeRequeued() {
        ingestion.ingest(event(UUID.randomUUID(), 801, Instant.now().minusSeconds(2)));
        Instant firstClaimAt = Instant.now();
        var oldClaim = jobs.claimOne("worker-a", firstClaimAt, Duration.ofSeconds(1), 3)
                .orElseThrow();
        var recovered = jobs.claimOne("worker-b", firstClaimAt.plusSeconds(2),
                Duration.ofSeconds(1), 3).orElseThrow();

        assertThat(jobs.complete(oldClaim)).isFalse();
        assertThat(jobs.failTerminal(recovered, "EVALUATION_RETRY_EXHAUSTED")).isTrue();
        assertThat(jobs.findByRiskSignalEventId(recovered.job().riskSignalEventId()))
                .get().extracting(job -> job.status()).isEqualTo(SafetyEvaluationJobStatus.FAILED);
        assertThat(jobs.requeueFailed(recovered.job().id(), firstClaimAt.plusSeconds(3))).isTrue();
        assertThat(jobs.findByRiskSignalEventId(recovered.job().riskSignalEventId()))
                .get().extracting(job -> job.status()).isEqualTo(SafetyEvaluationJobStatus.PENDING);
    }

    @Test
    void finalAttemptCrashCanBeRecoveredAndManualRequeueRestartsRetryBudget() {
        ingestion.ingest(event(UUID.randomUUID(), 802, Instant.now().minusSeconds(2)));
        Instant firstClaimAt = Instant.now();
        var first = jobs.claimOne("worker-a", firstClaimAt, Duration.ofSeconds(1), 3)
                .orElseThrow();
        var second = jobs.claimOne("worker-b", firstClaimAt.plusSeconds(2),
                Duration.ofSeconds(1), 3).orElseThrow();
        var finalAttempt = jobs.claimOne("worker-c", firstClaimAt.plusSeconds(4),
                Duration.ofSeconds(1), 3).orElseThrow();
        assertThat(finalAttempt.job().attempts()).isEqualTo(3);

        var recoveredFinalAttempt = jobs.claimOne("worker-d", firstClaimAt.plusSeconds(6),
                Duration.ofSeconds(1), 3).orElseThrow();
        assertThat(recoveredFinalAttempt.job().attempts()).isEqualTo(3);
        assertThat(jobs.complete(first)).isFalse();
        assertThat(jobs.complete(second)).isFalse();
        assertThat(jobs.complete(finalAttempt)).isFalse();

        assertThat(jobs.failTerminal(
                recoveredFinalAttempt, "EVALUATION_RETRY_EXHAUSTED")).isTrue();
        assertThat(jobs.requeueFailed(
                recoveredFinalAttempt.job().id(), firstClaimAt.plusSeconds(7))).isTrue();
        var requeued = jobs.findByRiskSignalEventId(
                recoveredFinalAttempt.job().riskSignalEventId()).orElseThrow();
        assertThat(requeued.status()).isEqualTo(SafetyEvaluationJobStatus.PENDING);
        assertThat(requeued.attempts()).isZero();

        var restarted = jobs.claimOne("worker-e", firstClaimAt.plusSeconds(8),
                Duration.ofSeconds(1), 3).orElseThrow();
        assertThat(restarted.job().attempts()).isEqualTo(1);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private static RiskSignalEventV1 event(UUID eventId, long sourceId, Instant occurredAt) {
        return new RiskSignalEventV1(
                1, eventId, RiskSourceType.USER_BLOCK, sourceId,
                RiskSignalType.USER_BLOCKED, 41, 42, occurredAt,
                Map.of("reasonCode", "USER_REQUEST"));
    }

    private static RiskSignalEventV1 greetingEvent(
            UUID eventId, long sourceId, Instant occurredAt
    ) {
        return new RiskSignalEventV1(
                1, eventId, RiskSourceType.GREETING, sourceId,
                RiskSignalType.GREETING_EXPIRED, 41, 42, occurredAt,
                Map.of("ttlHours", "24"));
    }
}
