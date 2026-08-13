package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import itda.media.service.StorageDeleteJobClaimService.ClaimedDeleteJob;
import itda.media.storage.ObjectStorage;
import itda.media.storage.StorageProviderRejectedException;
import itda.media.storage.StorageProviderUnavailableException;
import itda.media.domain.StorageDeleteReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorageDeleteWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private final StorageDeleteJobClaimService claims = mock(StorageDeleteJobClaimService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final StorageDeleteWorker worker = new StorageDeleteWorker(
            claims, storage, Clock.fixed(NOW, ZoneOffset.UTC),
            new SimpleMeterRegistry(),
            properties(5));

    @Test
    void firstSuccessfulDeleteSchedulesQuietConfirmation() {
        ClaimedDeleteJob job = job(1, "version-7", false);
        when(claims.claim(1, Duration.ofMinutes(5)))
                .thenReturn(List.of(job), List.of());
        when(claims.markDeletedOnce(job, NOW.plusSeconds(1800))).thenReturn(true);

        assertThat(worker.runOnce(10, Duration.ofMinutes(5)))
                .isEqualTo(new StorageDeleteWorker.Result(0, 1, 0));

        verify(storage).deleteAllVersions("setlogs/1/video.mp4");
        verify(claims).markDeletedOnce(job, NOW.plusSeconds(1800));
        verify(claims, never()).complete(job);
    }

    @Test
    void secondSuccessfulDeleteCompletesAfterQuietConfirmation() {
        ClaimedDeleteJob job = job(2, "version-7", true);
        when(claims.claim(1, Duration.ofMinutes(5)))
                .thenReturn(List.of(job), List.of());
        when(claims.complete(job)).thenReturn(true);

        assertThat(worker.runOnce(10, Duration.ofMinutes(5)))
                .isEqualTo(new StorageDeleteWorker.Result(1, 0, 0));

        verify(storage).deleteAllVersions(job.objectKey());
        verify(claims).complete(job);
        verify(claims, never()).markDeletedOnce(any(), any());
    }

    @Test
    void unavailableStorageSchedulesExponentialRetry() {
        ClaimedDeleteJob firstAttempt = job(1, null, false);
        when(claims.claim(1, Duration.ofMinutes(5)))
                .thenReturn(List.of(firstAttempt), List.of());
        doThrow(new StorageProviderUnavailableException("delete", new RuntimeException()))
                .when(storage).deleteAllVersions(firstAttempt.objectKey());

        assertThat(worker.runOnce(10, Duration.ofMinutes(5)))
                .isEqualTo(new StorageDeleteWorker.Result(0, 1, 0));

        org.mockito.ArgumentCaptor<Instant> retryAt =
                org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(claims).retry(org.mockito.ArgumentMatchers.eq(firstAttempt), retryAt.capture(),
                org.mockito.ArgumentMatchers.eq("storage temporarily unavailable"));
        assertThat(retryAt.getValue()).isBetween(NOW.plusSeconds(30), NOW.plusSeconds(36));
        verify(claims, never()).complete(firstAttempt);
    }

    @Test
    void rejectedDeleteIsPermanentFailureWithoutRawKeyInStoredError() {
        ClaimedDeleteJob job = job(3, null, false);
        when(claims.claim(1, Duration.ofMinutes(5)))
                .thenReturn(List.of(job), List.of());
        doThrow(new StorageProviderRejectedException("delete", 403, new RuntimeException()))
                .when(storage).deleteAllVersions(job.objectKey());

        assertThat(worker.runOnce(10, Duration.ofMinutes(5)))
                .isEqualTo(new StorageDeleteWorker.Result(0, 0, 1));

        verify(claims).fail(job, "storage rejected deletion");
        verify(claims, never()).retry(any(), any(), any());
    }

    @Test
    void unavailableAtRetryLimitBecomesObservablePermanentFailure() {
        ClaimedDeleteJob job = job(5, null, false);
        when(claims.claim(1, Duration.ofMinutes(5)))
                .thenReturn(List.of(job), List.of());
        doThrow(new StorageProviderUnavailableException("delete", new RuntimeException()))
                .when(storage).deleteAllVersions(job.objectKey());

        assertThat(worker.runOnce(10, Duration.ofMinutes(5)))
                .isEqualTo(new StorageDeleteWorker.Result(0, 0, 1));

        verify(claims).fail(job, "retry limit exceeded");
        verify(claims, never()).retry(any(), any(), any());
    }

    @Test
    void claimsOneJobAtATimeInsteadOfUpfrontBatchClaim() {
        when(claims.claim(1, Duration.ofMinutes(5))).thenReturn(List.of());

        worker.runOnce(100, Duration.ofMinutes(5));

        verify(claims, times(1)).claim(1, Duration.ofMinutes(5));
        verify(claims, never()).claim(100, Duration.ofMinutes(5));
    }

    @Test
    void versionlessCleanupDeletesEveryVersionInsteadOfCreatingDeleteMarker() {
        ClaimedDeleteJob job = job(1, null, false);
        when(claims.claim(1, Duration.ofMinutes(5)))
                .thenReturn(List.of(job), List.of());
        when(claims.markDeletedOnce(job, NOW.plusSeconds(1800))).thenReturn(true);

        worker.runOnce(10, Duration.ofMinutes(5));

        verify(storage).deleteAllVersions(job.objectKey());
        verify(storage, never()).delete(job.objectKey(), null);
    }

    @Test
    void blankVersionAlsoDeletesEveryVersionAndSchedulesConfirmation() {
        ClaimedDeleteJob job = job(1, " ", false);
        when(claims.claim(1, Duration.ofMinutes(5))).thenReturn(List.of(job), List.of());
        when(claims.markDeletedOnce(job, NOW.plusSeconds(1800))).thenReturn(true);

        assertThat(worker.runOnce(10, Duration.ofMinutes(5)))
                .isEqualTo(new StorageDeleteWorker.Result(0, 1, 0));

        verify(storage).deleteAllVersions(job.objectKey());
        verify(storage, never()).delete(job.objectKey(), " ");
    }

    @Test
    void completedUploadCleanupRetainsOnlyVerifiedVersion() {
        ClaimedDeleteJob job = new ClaimedDeleteJob(
                7L, "setlogs/1/video.mp4", "version-7",
                StorageDeleteReason.UPLOAD_SURPLUS_VERSIONS,
                1, UUID.randomUUID(), false);
        when(claims.claim(1, Duration.ofMinutes(5))).thenReturn(List.of(job), List.of());
        when(claims.markDeletedOnce(job, NOW.plusSeconds(1800))).thenReturn(true);

        worker.runOnce(10, Duration.ofMinutes(5));

        verify(storage).deleteAllVersionsExcept(job.objectKey(), "version-7");
        verify(storage, never()).deleteAllVersions(job.objectKey());
    }

    private static ClaimedDeleteJob job(int attempts, String versionId, boolean deletedOnce) {
        return new ClaimedDeleteJob(7L, "setlogs/1/video.mp4", versionId,
                StorageDeleteReason.SETLOG_DELETED,
                attempts, UUID.randomUUID(), deletedOnce);
    }

    private static StorageCleanupProperties properties(int maxAttempts) {
        return new StorageCleanupProperties(60_000, 100, Duration.ofMinutes(10), maxAttempts,
                Duration.ofMinutes(20), Duration.ofMinutes(30),
                Duration.ofSeconds(30), Duration.ofHours(6));
    }
}
