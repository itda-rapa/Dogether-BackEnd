package itda.media.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import itda.common.properties.MediaProperties;
import itda.media.domain.MediaStatus;
import itda.media.repository.MediaAssetRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MediaMaintenanceServiceTest {

    @Mock
    private MediaAssetRepository mediaAssetRepository;
    @Mock
    private S3StorageService storageService;

    @Test
    void deletionWaitsUntilUploadUrlExpiryAndGraceHavePassed() {
        Instant now = Instant.parse("2026-07-24T12:00:00Z");
        Duration deleteGrace = Duration.ofMinutes(5);
        given(mediaAssetRepository
                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                        MediaStatus.PENDING,
                        now
                ))
                .willReturn(List.of());
        given(mediaAssetRepository
                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                        MediaStatus.DELETE_REQUESTED,
                        now.minus(deleteGrace)
                ))
                .willReturn(List.of());

        MediaMaintenanceService service = new MediaMaintenanceService(
                mediaAssetRepository,
                storageService,
                new MediaProperties(
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(10),
                        deleteGrace,
                        10 * 1024 * 1024
                ),
                Clock.fixed(now, ZoneOffset.UTC)
        );

        service.maintain();

        verify(mediaAssetRepository)
                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                        MediaStatus.DELETE_REQUESTED,
                        now.minus(deleteGrace)
                );
        verifyNoInteractions(storageService);
    }
}
