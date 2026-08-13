package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import itda.media.domain.StorageDeleteReason;
import itda.media.service.StorageDeleteJobEnqueuer;
import itda.media.service.StorageCleanupProperties;
import itda.setlog.domain.SetlogUpload;
import itda.setlog.domain.SetlogUploadStatus;
import itda.setlog.repository.SetlogUploadRepository;
import itda.media.domain.Media;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SetlogUploadCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private final SetlogUploadRepository uploads = mock(SetlogUploadRepository.class);
    private final StorageDeleteJobEnqueuer jobs = mock(StorageDeleteJobEnqueuer.class);
    private final SetlogUploadCleanupService service = new SetlogUploadCleanupService(
            uploads, jobs, Clock.fixed(NOW, ZoneOffset.UTC), properties());

    @Test
    void exactExpiryTransitionsPresignedAndEnqueuesDelete() {
        SetlogUpload upload = upload(SetlogUploadStatus.PRESIGNED, "setlogs/presigned.mp4");
        when(uploads.findCleanupCandidatesForUpdate(NOW, 100)).thenReturn(List.of(upload));

        assertThat(service.enqueueExpired(100)).isEqualTo(1);

        verify(upload).expire();
        verify(jobs).enqueue("setlogs/presigned.mp4", null,
                StorageDeleteReason.UPLOAD_EXPIRED, NOW.plusSeconds(1800));
    }

    @Test
    void rejectedCandidateKeepsStateAndUsesRejectedReason() {
        SetlogUpload upload = upload(SetlogUploadStatus.REJECTED, "setlogs/rejected.mp4");
        when(uploads.findCleanupCandidatesForUpdate(NOW, 100)).thenReturn(List.of(upload));

        service.enqueueExpired(100);

        verify(upload, never()).expire();
        verify(jobs).enqueue("setlogs/rejected.mp4", null,
                StorageDeleteReason.UPLOAD_REJECTED, NOW.plusSeconds(1800));
    }

    @Test
    void repositoryBoundaryControlsEligibilityAndNoCandidateCreatesNoJob() {
        when(uploads.findCleanupCandidatesForUpdate(NOW, 50)).thenReturn(List.of());

        assertThat(service.enqueueExpired(50)).isZero();

        verify(jobs, never()).enqueue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completedUploadEnqueuesSurplusCleanupRetainingVerifiedVersion() {
        SetlogUpload upload = upload(SetlogUploadStatus.COMPLETED, "setlogs/completed.mp4");
        Media media = mock(Media.class);
        when(media.getObjectVersionId()).thenReturn("verified-v7");
        when(upload.getMedia()).thenReturn(media);
        when(uploads.findCleanupCandidatesForUpdate(NOW, 100)).thenReturn(List.of(upload));

        service.enqueueExpired(100);

        verify(jobs).enqueue("setlogs/completed.mp4", "verified-v7",
                StorageDeleteReason.UPLOAD_SURPLUS_VERSIONS, NOW.plusSeconds(1800));
    }

    @Test
    void versionlessCompletedIsSkippedWithoutBlockingExpiredOrRejectedJobs() {
        SetlogUpload completed = upload(SetlogUploadStatus.COMPLETED, "setlogs/local.mp4");
        Media media = mock(Media.class);
        when(media.getObjectVersionId()).thenReturn(null);
        when(completed.getMedia()).thenReturn(media);
        SetlogUpload expired = upload(SetlogUploadStatus.EXPIRED, "setlogs/expired.mp4");
        SetlogUpload rejected = upload(SetlogUploadStatus.REJECTED, "setlogs/rejected-2.mp4");
        when(uploads.findCleanupCandidatesForUpdate(NOW, 100))
                .thenReturn(List.of(completed, expired, rejected));

        assertThat(service.enqueueExpired(100)).isEqualTo(3);

        verify(jobs).enqueue("setlogs/expired.mp4", null,
                StorageDeleteReason.UPLOAD_EXPIRED, NOW.plusSeconds(1800));
        verify(jobs).enqueue("setlogs/rejected-2.mp4", null,
                StorageDeleteReason.UPLOAD_REJECTED, NOW.plusSeconds(1800));
        verify(jobs, times(2)).enqueue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completedUploadWithoutVerifiedVersionIsSkippedWithoutEnqueue() {
        SetlogUpload upload = upload(SetlogUploadStatus.COMPLETED, "setlogs/invalid.mp4");
        Media media = mock(Media.class);
        when(media.getObjectVersionId()).thenReturn(" ");
        when(upload.getMedia()).thenReturn(media);
        when(uploads.findCleanupCandidatesForUpdate(NOW, 100)).thenReturn(List.of(upload));

        assertThat(service.enqueueExpired(100)).isEqualTo(1);

        verify(jobs, never()).enqueue(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static SetlogUpload upload(SetlogUploadStatus status, String key) {
        SetlogUpload upload = mock(SetlogUpload.class);
        when(upload.getStatus()).thenReturn(status);
        when(upload.getObjectKey()).thenReturn(key);
        when(upload.getExpiresAt()).thenReturn(NOW);
        return upload;
    }

    private static StorageCleanupProperties properties() {
        return new StorageCleanupProperties(60_000, 100, java.time.Duration.ofMinutes(10), 10,
                java.time.Duration.ofMinutes(20), java.time.Duration.ofMinutes(30),
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofHours(6));
    }
}
