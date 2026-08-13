package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.media.domain.StorageDeleteReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import itda.media.storage.ObjectStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
        "app.storage-cleanup.enabled=false"
})
class StorageDeleteJobPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private StorageDeleteJobClaimService claims;
    @Autowired private StorageDeleteJobEnqueuer enqueuer;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearDeleteJobs() {
        jdbc.update("delete from storage_delete_jobs");
    }

    @Test
    void enqueueDeduplicatesSameReasonButAllowsLaterSetlogDeletion() {
        String key = "setlogs/dedupe/" + UUID.randomUUID() + ".mp4";
        Instant eligibleAt = Instant.now().minusSeconds(1);

        enqueuer.enqueue(key, "version-1", StorageDeleteReason.SETLOG_DELETED, eligibleAt);
        enqueuer.enqueue(key, "version-2", StorageDeleteReason.SETLOG_DELETED, eligibleAt);
        enqueuer.enqueue(key, "version-1", StorageDeleteReason.UPLOAD_SURPLUS_VERSIONS, eligibleAt);

        assertThat(jdbc.queryForObject(
                "select count(*) from storage_delete_jobs where object_key = ?",
                Long.class, key)).isEqualTo(2L);
    }

    @Test
    void concurrentClaimersClaimOneJobOnlyOnce() throws Exception {
        String key = "setlogs/claim/" + UUID.randomUUID() + ".mp4";
        enqueuer.enqueue(key, null, StorageDeleteReason.UPLOAD_EXPIRED,
                Instant.now().minusSeconds(1));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.function.Supplier<List<StorageDeleteJobClaimService.ClaimedDeleteJob>> task =
                    () -> {
                        try {
                            start.await(5, TimeUnit.SECONDS);
                            return claims.claim(1, Duration.ofMinutes(5));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(exception);
                        }
                    };
            CompletableFuture<List<StorageDeleteJobClaimService.ClaimedDeleteJob>> first =
                    CompletableFuture.supplyAsync(task, executor);
            CompletableFuture<List<StorageDeleteJobClaimService.ClaimedDeleteJob>> second =
                    CompletableFuture.supplyAsync(task, executor);
            start.countDown();

            List<StorageDeleteJobClaimService.ClaimedDeleteJob> all =
                    java.util.stream.Stream.concat(
                            first.get(10, TimeUnit.SECONDS).stream(),
                            second.get(10, TimeUnit.SECONDS).stream())
                            .filter(job -> job.objectKey().equals(key))
                            .toList();

            assertThat(all).hasSize(1);
            assertThat(jdbc.queryForObject(
                    "select attempts from storage_delete_jobs where object_key = ?",
                    Integer.class, key)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void staleClaimTokenCannotFinalizeReclaimedJob() {
        String key = "setlogs/stale/" + UUID.randomUUID() + ".mp4";
        enqueuer.enqueue(key, null, StorageDeleteReason.UPLOAD_EXPIRED,
                Instant.now().minusSeconds(1));
        StorageDeleteJobClaimService.ClaimedDeleteJob original = claims.claim(
                        100, Duration.ofMinutes(5)).stream()
                .filter(job -> job.objectKey().equals(key))
                .findFirst().orElseThrow();
        UUID replacement = UUID.randomUUID();
        jdbc.update("update storage_delete_jobs set claim_token = ? where id = ?",
                replacement, original.id());

        claims.complete(original);

        assertThat(jdbc.queryForObject(
                "select status from storage_delete_jobs where id = ?",
                String.class, original.id())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject(
                "select claim_token from storage_delete_jobs where id = ?",
                UUID.class, original.id())).isEqualTo(replacement);
        assertThat(claims.renew(original)).isFalse();
    }

    @Test
    void firstDeleteRequiresQuietConfirmationBeforeCompletion() {
        String key = "setlogs/confirm/" + UUID.randomUUID() + ".mp4";
        enqueuer.enqueue(key, null, StorageDeleteReason.UPLOAD_EXPIRED,
                Instant.now().minusSeconds(1));
        var first = claims.claim(100, Duration.ofMinutes(5)).stream()
                .filter(job -> job.objectKey().equals(key)).findFirst().orElseThrow();
        Instant confirmAt = Instant.now().plusSeconds(120);

        assertThat(claims.markDeletedOnce(first, confirmAt)).isTrue();
        assertThat(claims.claim(100, Duration.ofMinutes(5)))
                .noneMatch(job -> job.objectKey().equals(key));

        jdbc.update("update storage_delete_jobs set next_retry_at = now() - interval '1 second'"
                + " where object_key = ?", key);
        var confirmation = claims.claim(100, Duration.ofMinutes(5)).stream()
                .filter(job -> job.objectKey().equals(key)).findFirst().orElseThrow();
        assertThat(confirmation.deletedOnce()).isTrue();
        assertThat(claims.complete(confirmation)).isTrue();
        assertThat(jdbc.queryForObject(
                "select status from storage_delete_jobs where object_key = ?",
                String.class, key)).isEqualTo("COMPLETED");
    }

    @Test
    void heartbeatPreventsSecondWorkerFromDeletingSameJobAfterShortLease() throws Exception {
        String key = "setlogs/heartbeat/" + UUID.randomUUID() + ".mp4";
        enqueuer.enqueue(key, null, StorageDeleteReason.UPLOAD_EXPIRED,
                Instant.now().minusSeconds(1));
        ObjectStorage storage = mock(ObjectStorage.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger deleteCalls = new AtomicInteger();
        doAnswer(invocation -> {
            deleteCalls.incrementAndGet();
            entered.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release blocking delete");
            }
            return null;
        }).when(storage).deleteAllVersions(key);
        StorageCleanupProperties properties = new StorageCleanupProperties(
                60_000, 100, Duration.ofSeconds(30), 10,
                Duration.ofMinutes(20), Duration.ofMinutes(30),
                Duration.ofSeconds(30), Duration.ofHours(6));
        StorageDeleteWorker first = new StorageDeleteWorker(
                claims, storage, Clock.systemUTC(), new SimpleMeterRegistry(), properties);
        StorageDeleteWorker second = new StorageDeleteWorker(
                claims, storage, Clock.systemUTC(), new SimpleMeterRegistry(), properties);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<StorageDeleteWorker.Result> firstRun = CompletableFuture.supplyAsync(
                    () -> first.runOnce(1, Duration.ofSeconds(2)), executor);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(2_500);
            StorageDeleteWorker.Result secondResult = second.runOnce(1, Duration.ofSeconds(2));

            assertThat(secondResult).isEqualTo(new StorageDeleteWorker.Result(0, 0, 0));
            assertThat(deleteCalls).hasValue(1);
            release.countDown();
            assertThat(firstRun.get(5, TimeUnit.SECONDS))
                    .isEqualTo(new StorageDeleteWorker.Result(0, 1, 0));
        } finally {
            release.countDown();
            first.shutdownHeartbeatExecutor();
            second.shutdownHeartbeatExecutor();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
