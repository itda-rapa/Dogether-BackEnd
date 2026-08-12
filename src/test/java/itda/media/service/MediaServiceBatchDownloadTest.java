package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import itda.common.properties.S3Properties;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.repository.MediaRepository;
import itda.user.repository.UserRepository;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@ExtendWith(MockitoExtension.class)
class MediaServiceBatchDownloadTest {

    @Mock private MediaRepository mediaRepository;
    @Mock private MultipartService multipartService;
    @Mock private S3Presigner s3Presigner;
    @Mock private UserRepository userRepository;

    private MediaService mediaService;

    @BeforeEach
    void setUp() {
        mediaService = new MediaService(
                mediaRepository,
                multipartService,
                s3Presigner,
                new S3Properties("access", "secret", "bucket", "region", 600L),
                userRepository
        );
    }

    @Test
    void loadedDownloadableMediaAreSignedWithoutRepositoryLookup()
            throws Exception {
        Media uploaded = downloadableMedia(1L, MediaStatus.UPLOADED, "one.mp4");
        Media completed = downloadableMedia(2L, MediaStatus.COMPLETED, "two.mp4");
        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        given(signed.url()).willReturn(URI.create("https://example.com/video.mp4").toURL());
        given(signed.expiration()).willReturn(Instant.parse("2026-08-11T05:00:00Z"));
        given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .willReturn(signed);

        Map<Long, MediaService.PresignedDownloadUrl> result =
                mediaService.getPresignedDownloadUrls(
                        List.of(uploaded, completed)
                );

        assertThat(result).containsOnlyKeys(1L, 2L);
        then(s3Presigner).should(org.mockito.Mockito.times(2))
                .presignGetObject(any(GetObjectPresignRequest.class));
        then(mediaRepository).shouldHaveNoInteractions();
    }

    @Test
    void rejectsDeletedNotDownloadableAndUnpersistedMedia() {
        Media deleted = mock(Media.class);
        given(deleted.getDeletedAt())
                .willReturn(Instant.parse("2026-08-11T01:00:00Z"));
        Media init = mock(Media.class);
        given(init.getStatus()).willReturn(MediaStatus.INIT);
        Media failed = mock(Media.class);
        given(failed.getStatus()).willReturn(MediaStatus.FAILED);
        Media unpersisted = mock(Media.class);
        given(unpersisted.getStatus()).willReturn(MediaStatus.UPLOADED);
        given(unpersisted.getId()).willReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> mediaService.getPresignedDownloadUrls(List.of(deleted)));
        assertThrows(IllegalArgumentException.class,
                () -> mediaService.getPresignedDownloadUrls(List.of(init)));
        assertThrows(IllegalArgumentException.class,
                () -> mediaService.getPresignedDownloadUrls(List.of(failed)));
        assertThrows(IllegalArgumentException.class,
                () -> mediaService.getPresignedDownloadUrls(List.of(unpersisted)));
        assertThrows(IllegalArgumentException.class,
                () -> mediaService.getPresignedDownloadUrls(null));

        then(s3Presigner).shouldHaveNoInteractions();
        then(mediaRepository).shouldHaveNoInteractions();
    }

    @Test
    void ownedDownloadPinsTheVerifiedObjectVersion() throws Exception {
        Media media = mock(Media.class);
        given(mediaRepository.findByIdAndDeletedAtIsNull(7L))
                .willReturn(Optional.of(media));
        given(media.getUserId()).willReturn(1L);
        given(media.getStatus()).willReturn(MediaStatus.COMPLETED);
        given(media.getPath()).willReturn("setlogs/1/12/video.mp4");
        given(media.getObjectVersionId()).willReturn("version-7");
        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        given(signed.url()).willReturn(URI.create("https://example.com/video.mp4").toURL());
        given(signed.expiration()).willReturn(Instant.parse("2026-08-12T05:00:00Z"));
        given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .willReturn(signed);

        MediaService.OwnedPresignedDownload result =
                mediaService.getOwnedPresignedDownload(7L, 1L);

        assertThat(result.media()).isSameAs(media);
        ArgumentCaptor<GetObjectPresignRequest> captor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        then(s3Presigner).should().presignGetObject(captor.capture());
        assertThat(captor.getValue().getObjectRequest().versionId())
                .isEqualTo("version-7");
    }

    @Test
    void ownedDownloadHidesForeignAndMissingMediaWithSameNotFoundError() {
        Media foreign = mock(Media.class);
        given(foreign.getUserId()).willReturn(2L);
        given(mediaRepository.findByIdAndDeletedAtIsNull(7L))
                .willReturn(Optional.of(foreign));
        given(mediaRepository.findByIdAndDeletedAtIsNull(8L))
                .willReturn(Optional.empty());

        assertMediaNotFound(() -> mediaService.getOwnedPresignedDownload(7L, 1L));
        assertMediaNotFound(() -> mediaService.getOwnedPresignedDownload(8L, 1L));
        then(s3Presigner).shouldHaveNoInteractions();
    }

    private void assertMediaNotFound(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
    }

    private Media downloadableMedia(
            Long id,
            MediaStatus status,
            String path
    ) {
        Media media = mock(Media.class);
        given(media.getId()).willReturn(id);
        given(media.getStatus()).willReturn(status);
        given(media.getPath()).willReturn(path);
        return media;
    }
}
