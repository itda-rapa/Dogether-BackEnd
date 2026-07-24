package itda.media.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.properties.MediaProperties;
import itda.media.domain.MediaPurpose;
import itda.media.domain.MediaAsset;
import itda.media.domain.MediaStatus;
import itda.media.dto.MediaUploadRequest;
import itda.media.repository.MediaAssetRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    private MediaAssetRepository mediaAssetRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private S3StorageService storageService;
    @Mock
    private MediaPolicy mediaPolicy;
    @Mock
    private MediaStatusService mediaStatusService;

    @Test
    void normalUserCannotUploadSetlogInM1() {
        User user = User.register(
                "user@example.com",
                "encoded",
                "사용자",
                "사용자#A7K2",
                "1168010100"
        );
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        MediaService service = new MediaService(
                mediaAssetRepository,
                userRepository,
                storageService,
                mediaPolicy,
                new MediaProperties(
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(10),
                        10 * 1024 * 1024
                ),
                mediaStatusService
        );

        assertThatThrownBy(() -> service.createUpload(
                1L,
                new MediaUploadRequest(
                        MediaPurpose.SETLOG,
                        "video/mp4",
                        1024
                )
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEDIA_PURPOSE_FORBIDDEN);
    }

    @Test
    void expiredUploadPersistsExpiredStatusBeforeReturningGone() {
        MediaAsset mediaAsset = org.mockito.Mockito.mock(MediaAsset.class);
        given(mediaAssetRepository.findById(7L))
                .willReturn(Optional.of(mediaAsset));
        given(mediaAsset.belongsTo(1L)).willReturn(true);
        given(mediaAsset.getStatus()).willReturn(MediaStatus.PENDING);
        given(mediaAsset.getExpiresAt()).willReturn(Instant.EPOCH);
        given(mediaAsset.getId()).willReturn(7L);

        MediaService service = new MediaService(
                mediaAssetRepository,
                userRepository,
                storageService,
                mediaPolicy,
                new MediaProperties(
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(10),
                        10 * 1024 * 1024
                ),
                mediaStatusService
        );

        assertThatThrownBy(() -> service.complete(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEDIA_EXPIRED);

        verify(mediaStatusService).expirePending(
                org.mockito.ArgumentMatchers.eq(7L),
                any(Instant.class)
        );
        verifyNoInteractions(storageService);
    }
}
