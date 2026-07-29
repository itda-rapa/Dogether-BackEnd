package itda.media.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import itda.media.old.domain.MediaAsset;
import itda.media.domain.MediaStatus;
import itda.media.old.repository.MediaAssetRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class MediaMaintenanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

    @Mock
    private MediaAssetRepository mediaAssetRepository;
    @Mock
    private S3StorageService storageService;
    @Mock
    private MediaAsset mediaAsset;

    private MediaMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new MediaMaintenanceService(
                mediaAssetRepository,
                storageService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void expiredPendingUploadIsMarkedExpiredAfterObjectDeletion() {
        given(mediaAsset.getObjectKey()).willReturn("media/1/profile/object");
        given(mediaAssetRepository
                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                        MediaStatus.PENDING,
                        NOW
                ))
                .willReturn(List.of(mediaAsset));
        given(mediaAssetRepository.findTop100ByStatusOrderById(
                MediaStatus.DELETE_REQUESTED
        )).willReturn(List.of());

        service.maintain();

        verify(storageService).delete("media/1/profile/object");
        verify(mediaAsset).markExpired();
    }

    @Test
    void expiredPendingUploadRemainsPendingWhenObjectDeletionFails() {
        given(mediaAsset.getId()).willReturn(1L);
        given(mediaAsset.getObjectKey()).willReturn("media/1/profile/object");
        given(mediaAssetRepository
                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                        MediaStatus.PENDING,
                        NOW
                ))
                .willReturn(List.of(mediaAsset));
        given(mediaAssetRepository.findTop100ByStatusOrderById(
                MediaStatus.DELETE_REQUESTED
        )).willReturn(List.of());
        doThrow(s3Failure()).when(storageService)
                .delete("media/1/profile/object");

        service.maintain();

        verify(mediaAsset, never()).markExpired();
    }

    @Test
    void deleteRequestedAssetIsDeletedOnNextMaintenanceRun() {
        given(mediaAsset.getObjectKey()).willReturn("media/1/profile/object");
        given(mediaAssetRepository
                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                        MediaStatus.PENDING,
                        NOW
                ))
                .willReturn(List.of());
        given(mediaAssetRepository.findTop100ByStatusOrderById(
                MediaStatus.DELETE_REQUESTED
        )).willReturn(List.of(mediaAsset));

        service.maintain();

        verify(storageService).delete("media/1/profile/object");
        verify(mediaAsset).markDeleted();
    }

    @Test
    void deleteRequestedAssetIsRetriedWhenObjectDeletionFails() {
        given(mediaAsset.getId()).willReturn(1L);
        given(mediaAsset.getObjectKey()).willReturn("media/1/profile/object");
        given(mediaAssetRepository
                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                        MediaStatus.PENDING,
                        NOW
                ))
                .willReturn(List.of());
        given(mediaAssetRepository.findTop100ByStatusOrderById(
                MediaStatus.DELETE_REQUESTED
        )).willReturn(List.of(mediaAsset));
        doThrow(s3Failure()).when(storageService)
                .delete("media/1/profile/object");

        service.maintain();

        verify(mediaAsset, never()).markDeleted();
    }

    private S3Exception s3Failure() {
        return (S3Exception) S3Exception.builder()
                .statusCode(503)
                .message("S3 unavailable")
                .build();
    }
}
