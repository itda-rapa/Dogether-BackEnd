package itda.safety.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.safety.domain.EvidenceAccessResult;
import itda.safety.domain.SafetyActionType;
import itda.safety.domain.SafetyCaseSnapshot;
import itda.safety.domain.SafetyCaseStatus;
import itda.safety.domain.SafetyEvaluationJob;
import itda.safety.domain.SafetyReviewCase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
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
        "spring.flyway.locations=classpath:db/migration",
        "app.safety.evaluator.enabled=false",
        "app.safety.evaluator.batch-size=50",
        "app.safety.evaluator.delay-ms=5000",
        "app.safety.evaluator.lease=1m",
        "app.safety.evaluator.max-attempts=10",
        "app.safety.evaluator.base-backoff=5s",
        "app.safety.evaluator.max-backoff=10m"
})
class SafetyRepositoryPostgreSqlIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private SafetyReviewCaseJdbcRepository caseRepository;
    @Autowired private SafetyCaseActionJdbcRepository actionRepository;
    @Autowired private EvidenceAccessAuditJdbcRepository auditRepository;
    @Autowired private SafetyEvaluationJobJdbcRepository jobRepository;
    @Autowired private SafetyAdminQueryJdbcRepository adminQueryRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void upsertsOneOpenCaseIncludingNullTargetAndDoesNotRegressSnapshot() {
        long subjectId = uniqueId();
        Instant detectedAt = Instant.parse("2026-08-24T01:00:00Z");
        SafetyCaseSnapshot first = snapshot(30, 1, detectedAt, detectedAt);

        SafetyReviewCase created = caseRepository.upsertOpenCase(subjectId, null, first).orElseThrow();
        SafetyReviewCase refreshed = caseRepository.upsertOpenCase(
                subjectId, null, snapshot(40, 2, detectedAt, detectedAt.plusSeconds(30)))
                .orElseThrow();
        SafetyReviewCase stale = caseRepository.upsertOpenCase(
                subjectId, null, snapshot(10, 1, detectedAt, detectedAt))
                .orElseThrow();

        assertThat(refreshed.id()).isEqualTo(created.id());
        assertThat(refreshed.totalScore()).isEqualTo(40);
        assertThat(refreshed.signalCount()).isEqualTo(2);
        assertThat(refreshed.version()).isEqualTo(created.version() + 1);
        assertThat(stale).isEqualTo(refreshed);
        assertThat(jdbc.queryForObject("""
                select count(*) from safety_review_cases
                 where subject_user_id = ? and target_user_id is null
                   and status in ('OPEN', 'REVIEWING')
                """, Long.class, subjectId)).isOne();
    }

    @Test
    void terminalCaseBlocksSameSnapshotButNewSignalCanOpenNextCase() {
        long subjectId = uniqueId();
        long targetId = uniqueId();
        Instant detectedAt = Instant.parse("2026-08-24T02:00:00Z");
        SafetyReviewCase open = caseRepository.upsertOpenCase(
                subjectId, targetId, snapshot(30, 1, detectedAt, detectedAt)).orElseThrow();
        SafetyReviewCase closed = caseRepository.transition(
                open.id(), open.version(), SafetyCaseStatus.OPEN, SafetyCaseStatus.DISMISSED)
                .orElseThrow();

        assertThat(caseRepository.upsertOpenCase(
                subjectId, targetId, snapshot(30, 1, detectedAt, detectedAt))).isEmpty();

        SafetyReviewCase next = caseRepository.upsertOpenCase(
                subjectId, targetId, snapshot(40, 2, detectedAt, detectedAt.plusSeconds(1)))
                .orElseThrow();
        assertThat(next.id()).isNotEqualTo(closed.id());
        assertThat(next.status()).isEqualTo(SafetyCaseStatus.OPEN);
    }

    @Test
    void sameTimestampSignalWithNewEventWatermarkRefreshesSnapshot() {
        long subjectId = uniqueId();
        Instant detectedAt = Instant.parse("2026-08-24T01:30:00Z");

        SafetyReviewCase first = caseRepository.upsertOpenCase(
                subjectId, null, snapshot(30, 1, detectedAt, detectedAt, 101)).orElseThrow();
        SafetyReviewCase refreshed = caseRepository.upsertOpenCase(
                subjectId, null, snapshot(60, 2, detectedAt, detectedAt, 102)).orElseThrow();

        assertThat(refreshed.id()).isEqualTo(first.id());
        assertThat(refreshed.signalCount()).isEqualTo(2);
        assertThat(refreshed.totalScore()).isEqualTo(60);
        assertThat(refreshed.lastEvaluatedEventId()).isEqualTo(102);
    }

    @Test
    void versionAndExpectedStatusFenceConcurrentTransitions() {
        long subjectId = uniqueId();
        Instant detectedAt = Instant.parse("2026-08-24T03:00:00Z");
        SafetyReviewCase open = caseRepository.upsertOpenCase(
                subjectId, uniqueId(), snapshot(30, 1, detectedAt, detectedAt)).orElseThrow();

        assertThat(caseRepository.transition(
                open.id(), open.version(), SafetyCaseStatus.OPEN, SafetyCaseStatus.WARNING_RECORDED))
                .isPresent();
        assertThat(caseRepository.transition(
                open.id(), open.version(), SafetyCaseStatus.OPEN, SafetyCaseStatus.DISMISSED))
                .isEmpty();
        assertThatThrownBy(() -> caseRepository.transition(
                open.id(), open.version(), SafetyCaseStatus.DISMISSED, SafetyCaseStatus.OPEN))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transitionWaitsForTheSamePairAdvisoryLockAsEvaluatorUpsert() throws Exception {
        long subjectId = uniqueId();
        long targetId = uniqueId();
        Instant detectedAt = Instant.parse("2026-08-24T03:30:00Z");
        SafetyReviewCase open = caseRepository.upsertOpenCase(
                subjectId, targetId, snapshot(30, 1, detectedAt, detectedAt)).orElseThrow();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch transitionStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.queryForObject("""
                                select pg_advisory_xact_lock(hashtextextended(
                                    concat(cast(? as text), ':', cast(? as text)), 0))
                                """, (resultSet, rowNumber) -> true, subjectId, targetId);
                        lockHeld.countDown();
                        awaitLatch(releaseLock);
                    }));
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            var transition = executor.submit(() -> {
                transitionStarted.countDown();
                return caseRepository.transition(
                        open.id(), open.version(), SafetyCaseStatus.OPEN,
                        SafetyCaseStatus.DISMISSED);
            });
            assertThat(transitionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(transition.isDone()).isFalse();

            releaseLock.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(transition.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void queueCursorRemainsStableWhenCaseDetectionSnapshotIsRefreshed() {
        long subjectId = uniqueId();
        Instant detectedAt = Instant.parse("2026-08-24T03:40:00Z");
        caseRepository.upsertOpenCase(
                subjectId, uniqueId(), snapshot(30, 1, detectedAt, detectedAt)).orElseThrow();
        caseRepository.upsertOpenCase(
                subjectId, uniqueId(), snapshot(30, 1, detectedAt, detectedAt)).orElseThrow();

        var initial = adminQueryRepository.findCases(
                SafetyCaseStatus.OPEN, null, subjectId, null,
                null, null, null, null, 2);
        SafetyReviewCase firstPageLast = initial.getFirst();
        SafetyReviewCase nextCase = initial.get(1);
        Instant refreshedAt = detectedAt.plusSeconds(60);
        jdbc.update("""
                update safety_review_cases
                   set last_detected_at = ?, evaluated_at = ?, updated_at = current_timestamp
                 where id = ?
                """, Timestamp.from(refreshedAt), Timestamp.from(refreshedAt), nextCase.id());

        var nextPage = adminQueryRepository.findCases(
                SafetyCaseStatus.OPEN, null, subjectId, null,
                null, null, firstPageLast.createdAt(), firstPageLast.id(), 2);

        assertThat(nextPage).extracting(SafetyReviewCase::id).containsExactly(nextCase.id());
    }

    @Test
    void actionAndEvidenceAuditAreAppendOnlyAndStoreOnlySanitizedFailureCode() {
        long subjectId = uniqueId();
        Instant detectedAt = Instant.parse("2026-08-24T04:00:00Z");
        SafetyReviewCase reviewCase = caseRepository.upsertOpenCase(
                subjectId, uniqueId(), snapshot(30, 1, detectedAt, detectedAt)).orElseThrow();
        long adminId = uniqueId();

        var action = actionRepository.append(
                reviewCase.id(), adminId, SafetyActionType.DISMISSED, "false positive",
                Map.of("status", "OPEN", "version", reviewCase.version()),
                Map.of("status", "DISMISSED", "version", reviewCase.version() + 1));
        var audit = auditRepository.append(
                reviewCase.id(), adminId, "CHAT_MESSAGE", null, "fact check",
                EvidenceAccessResult.FAILED, "SOURCE_NOT_FOUND");

        assertThat(actionRepository.findByCaseId(reviewCase.id())).containsExactly(action);
        assertThat(auditRepository.findByCaseId(reviewCase.id())).containsExactly(audit);
        assertThatThrownBy(() -> jdbc.update(
                "update safety_case_actions set reason = 'changed' where id = ?", action.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "delete from evidence_access_audits where id = ?", audit.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> auditRepository.append(
                reviewCase.id(), adminId, "CHAT_MESSAGE", null, "fact check",
                EvidenceAccessResult.FAILED, "token=secret"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evaluationJobIsIdempotentAndLeaseClaimUsesFencingToken() {
        UUID eventId = insertRiskSignalEvent();
        long riskSignalEventId = jdbc.queryForObject(
                "select id from risk_signal_events where event_id = ?", Long.class, eventId);
        Instant now = Instant.now().plusSeconds(1);

        assertThat(jobRepository.enqueueByEventId(eventId)).isTrue();
        assertThat(jobRepository.enqueueByEventId(eventId)).isFalse();

        var first = jobRepository.claimOne("worker-a", now, Duration.ofMinutes(1), 5).orElseThrow();
        SafetyEvaluationJob firstClaim = first.job();
        assertThat(firstClaim.riskSignalEventId()).isEqualTo(riskSignalEventId);
        assertThat(firstClaim.attempts()).isEqualTo(1);

        var second = jobRepository.claimOne(
                "worker-b", now.plusSeconds(61), Duration.ofMinutes(1), 5).orElseThrow();
        SafetyEvaluationJob reclaimed = second.job();
        assertThat(reclaimed.id()).isEqualTo(firstClaim.id());
        assertThat(reclaimed.attempts()).isEqualTo(2);
        assertThat(jobRepository.complete(first)).isFalse();
        assertThat(jobRepository.retry(second, now.plusSeconds(120), "CASE_EVALUATION_FAILED"))
                .isTrue();
        assertThat(jobRepository.claimOne(
                "worker-c", now.plusSeconds(119), Duration.ofMinutes(1), 5)).isEmpty();

        var finalClaim = jobRepository.claimOne(
                "worker-c", now.plusSeconds(120), Duration.ofMinutes(1), 5).orElseThrow();
        assertThat(jobRepository.complete(finalClaim)).isTrue();
        assertThat(jobRepository.findByRiskSignalEventId(riskSignalEventId).orElseThrow().claimedAt())
                .isNull();
    }

    @Test
    void v39HasOpenCaseUniquenessQueueIndexesAndEvaluationJobConstraint() {
        assertThat(jdbc.queryForList("""
                select indexname from pg_indexes
                 where schemaname = current_schema()
                   and tablename in ('safety_review_cases', 'safety_case_evaluation_jobs')
                """, String.class)).contains(
                "uk_safety_review_cases_open_subject_target",
                "ix_safety_review_cases_queue",
                "ix_safety_case_evaluation_jobs_due",
                "ix_safety_case_evaluation_jobs_stale_claim");
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_constraint
                 where conname = 'uk_safety_case_evaluation_jobs_event'
                """, Long.class)).isOne();
    }

    private SafetyCaseSnapshot snapshot(
            long totalScore, long count, Instant firstDetectedAt, Instant lastDetectedAt
    ) {
        return snapshot(totalScore, count, firstDetectedAt, lastDetectedAt, totalScore);
    }

    private SafetyCaseSnapshot snapshot(
            long totalScore, long count, Instant firstDetectedAt, Instant lastDetectedAt,
            long lastEvaluatedEventId
    ) {
        return new SafetyCaseSnapshot(
                totalScore, count, firstDetectedAt, lastDetectedAt,
                lastEvaluatedEventId, "USER_BLOCKED", 1, lastDetectedAt.plusSeconds(1));
    }

    private UUID insertRiskSignalEvent() {
        UUID eventId = UUID.randomUUID();
        long sourceId = uniqueId();
        jdbc.update("""
                insert into risk_signal_events (
                    event_id, schema_version, source_type, source_id, signal_type,
                    actor_user_id, target_user_id, score, score_policy_version,
                    occurred_at, metadata
                ) values (?, 1, 'USER_BLOCK', ?, 'USER_BLOCKED', ?, ?, 30, 1, ?, '{}'::jsonb)
                """, eventId, sourceId, uniqueId(), uniqueId(),
                Timestamp.from(Instant.parse("2026-08-24T05:00:00Z")));
        return eventId;
    }

    private static long uniqueId() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", exception);
        }
    }
}
