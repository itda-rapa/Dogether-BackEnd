package itda.media.service;

import itda.media.service.StorageDeleteJobClaimService.ClaimedDeleteJob;
import itda.media.storage.ObjectStorage;
import itda.media.storage.StorageProviderRejectedException;
import itda.media.storage.StorageProviderUnavailableException;
import itda.media.domain.StorageDeleteReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageDeleteWorker {
    private final StorageDeleteJobClaimService claims;
    private final ObjectStorage objectStorage;
    private final Clock clock;
    private final MeterRegistry meters;
    private final StorageCleanupProperties properties;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "storage-delete-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public Result runOnce(int batchSize, Duration lease) {
        int succeeded = 0, retried = 0, failed = 0;
        for (int processed = 0; processed < batchSize; processed++) {
            List<ClaimedDeleteJob> claimed = claims.claim(1, lease);
            if (claimed.isEmpty()) {
                break;
            }
            ClaimedDeleteJob job = claimed.getFirst();
            AtomicBoolean ownership = new AtomicBoolean(true);
            long heartbeatMillis = Math.max(1_000L, lease.toMillis() / 3L);
            ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                    () -> {
                        try {
                            if (!claims.renew(job)) {
                                ownership.set(false);
                            }
                        } catch (RuntimeException exception) {
                            ownership.set(false);
                            log.warn("Storage deletion lease renewal failed: jobId={}", job.id());
                        }
                    }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
            try {
                if (job.reason() == StorageDeleteReason.UPLOAD_SURPLUS_VERSIONS) {
                    objectStorage.deleteAllVersionsExcept(
                            job.objectKey(), job.objectVersionId());
                } else {
                    objectStorage.deleteAllVersions(job.objectKey());
                }
                heartbeat.cancel(false);
                if (!ownership.get()) {
                    continue;
                }
                if (!job.deletedOnce()) {
                    if (claims.markDeletedOnce(job,
                            clock.instant().plus(properties.uploadSettleGrace()))) {
                        counter(job, "confirmation_scheduled");
                        retried++;
                    }
                } else if (claims.complete(job)) {
                    counter(job, "succeeded");
                    succeeded++;
                }
            } catch (StorageProviderUnavailableException exception) {
                heartbeat.cancel(false);
                if (!ownership.get()) continue;
                if (job.attempts() >= properties.maxAttempts()) {
                    claims.fail(job, "retry limit exceeded");
                    counter(job, "failed");
                    failed++;
                } else {
                    claims.retry(job, clock.instant().plus(backoff(job)), "storage temporarily unavailable");
                    counter(job, "retried");
                    retried++;
                }
            } catch (StorageProviderRejectedException exception) {
                heartbeat.cancel(false);
                if (!ownership.get()) continue;
                claims.fail(job, "storage rejected deletion");
                counter(job, "failed");
                failed++;
            } catch (RuntimeException exception) {
                heartbeat.cancel(false);
                if (!ownership.get()) continue;
                // Unknown failures are terminal to avoid an unbounded retry storm.
                claims.fail(job, "unexpected deletion failure");
                counter(job, "failed");
                failed++;
                log.warn("Storage deletion job failed unexpectedly: jobId={}", job.id());
            } finally {
                heartbeat.cancel(false);
            }
        }
        return new Result(succeeded, retried, failed);
    }

    @PreDestroy
    void shutdownHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    private void counter(ClaimedDeleteJob job, String result) {
        meters.counter("storage.delete.jobs",
                "result", result,
                "reason", job.reason().name().toLowerCase(java.util.Locale.ROOT))
                .increment();
    }

    private Duration backoff(ClaimedDeleteJob job) {
        long baseMillis = properties.baseBackoff().toMillis();
        long maxMillis = properties.maxBackoff().toMillis();
        long multiplier = 1L << Math.min(job.attempts() - 1, 20);
        long exponential = baseMillis > maxMillis / multiplier
                ? maxMillis : baseMillis * multiplier;
        // Stable 0-20% jitter avoids synchronized retry waves without shared RNG state.
        long jitterRange = Math.max(1L, exponential / 5L);
        long jitter = Math.floorMod(Long.hashCode(job.id()), jitterRange);
        return Duration.ofMillis(Math.min(maxMillis, exponential + jitter));
    }

    public record Result(int succeeded, int retried, int failed) {}
}
