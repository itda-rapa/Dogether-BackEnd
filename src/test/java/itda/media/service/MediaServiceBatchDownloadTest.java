package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import itda.common.properties.S3Properties;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.repository.MediaRepository;
import itda.media.storage.ObjectMetadata;
import itda.media.storage.ObjectStorage;
import itda.media.storage.ObjectNotFoundException;
import itda.media.storage.StorageProviderUnavailableException;
import itda.media.dto.uploaddto.MultipartUploaded;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
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
    @Mock private ObjectStorage objectStorage;

    private MediaService mediaService;

    @BeforeEach
    void setUp() {
        mediaService = new MediaService(
                mediaRepository,
                multipartService,
                s3Presigner,
                new S3Properties("access", "secret", "bucket", "region", 600L),
                userRepository,
                objectStorage
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

    @Test
    void multipartUploadWithoutPartsIsRejectedBeforeStorageCompletion() {
        Media media = new Media(MediaType.IMAGE, "users/1/image.png", 1L, 9L * 1024 * 1024, "upload-1");
        User owner = owner(1L);
        given(userRepository.findByIdOrThrow(1L)).willReturn(owner);
        given(mediaRepository.findByIdAndDeletedAtIsNullOrThrow(7L)).willReturn(media);

        assertMediaError(() -> mediaService.mediaUploaded(7L, List.of(), 1L), ErrorCode.MEDIA_STATE_CONFLICT);

        then(objectStorage).shouldHaveNoInteractions();
        assertThat(media.getStatus()).isEqualTo(MediaStatus.FAILED);
        then(mediaRepository).should().save(media);
        then(multipartService).should().abortMultipartUpload("users/1/image.png", "upload-1");
    }

    @Test
    void uploadCompletionRejectsActualObjectSizeMismatch() {
        Media media = new Media(MediaType.IMAGE, "users/1/image.png", 1L, 10L, "upload-1");
        User owner = owner(1L);
        given(userRepository.findByIdOrThrow(1L)).willReturn(owner);
        given(mediaRepository.findByIdAndDeletedAtIsNullOrThrow(7L)).willReturn(media);
        given(objectStorage.head("users/1/image.png")).willReturn(
                new ObjectMetadata(11L, "image/png", "etag", Instant.now(), "version-1"));

        assertMediaError(() -> mediaService.mediaUploaded(
                7L, List.of(new MultipartUploaded(1, "etag")), 1L), ErrorCode.MEDIA_SIZE_INVALID);

        assertThat(media.getStatus()).isEqualTo(MediaStatus.FAILED);
        then(mediaRepository).should().save(media);
        then(objectStorage).should().delete("users/1/image.png", "version-1");
    }

    @Test
    void uploadCompletionRejectsActualObjectContentTypeMismatch() {
        Media media = new Media(MediaType.IMAGE, "users/1/image.png", 1L, 10L);
        User owner = owner(1L);
        given(userRepository.findByIdOrThrow(1L)).willReturn(owner);
        given(mediaRepository.findByIdAndDeletedAtIsNullOrThrow(7L)).willReturn(media);
        given(objectStorage.head("users/1/image.png")).willReturn(
                new ObjectMetadata(10L, "image/gif", "etag", Instant.now(), "version-1"));

        assertMediaError(() -> mediaService.mediaUploaded(7L, List.of(), 1L), ErrorCode.INVALID_MEDIA_TYPE);

        assertThat(media.getStatus()).isEqualTo(MediaStatus.FAILED);
        then(objectStorage).should().delete("users/1/image.png", "version-1");
    }

    @Test
    void uploadCompletionMarksMediaFailedWhenStorageObjectIsMissing() {
        Media media = new Media(MediaType.VIDEO, "users/1/video.mp4", 1L, 10L);
        User owner = owner(1L);
        given(userRepository.findByIdOrThrow(1L)).willReturn(owner);
        given(mediaRepository.findByIdAndDeletedAtIsNullOrThrow(7L)).willReturn(media);
        given(objectStorage.head("users/1/video.mp4")).willThrow(
                new ObjectNotFoundException("head", new IllegalStateException("missing")));

        assertMediaError(() -> mediaService.mediaUploaded(7L, List.of(), 1L), ErrorCode.MEDIA_NOT_UPLOADED);

        assertThat(media.getStatus()).isEqualTo(MediaStatus.FAILED);
        then(mediaRepository).should().save(media);
    }

    @Test
    void uploadCompletionPersistsVerifiedObjectMetadataAndCompletedStatus() {
        Media media = new Media(MediaType.VIDEO, "users/1/video.mp4", 1L, 10L);
        User owner = owner(1L);
        given(userRepository.findByIdOrThrow(1L)).willReturn(owner);
        given(mediaRepository.findByIdAndDeletedAtIsNullOrThrow(7L)).willReturn(media);
        Instant lastModified = Instant.parse("2026-08-26T01:00:00Z");
        given(objectStorage.head("users/1/video.mp4")).willReturn(
                new ObjectMetadata(10L, "video/mp4", "etag", lastModified, "version-1"));
        given(objectStorage.read("users/1/video.mp4", "version-1"))
                .willReturn(mp4WithDuration(5_000, 1_000));
        given(mediaRepository.save(media)).willReturn(media);

        Media result = mediaService.mediaUploaded(7L, List.of(), 1L);

        assertThat(result.getStatus()).isEqualTo(MediaStatus.COMPLETED);
        assertThat(result.getContentType()).isEqualTo("video/mp4");
        assertThat(result.getObjectVersionId()).isEqualTo("version-1");
        assertThat(result.getVerifiedAt()).isNotNull();
        then(mediaRepository).should().save(media);
    }

    @Test
    void uploadCompletionRejectsVideoExceedingContractDurationAndDeletesObject() {
        Media media = new Media(MediaType.VIDEO, "users/1/video.mp4", 1L, 10L);
        User owner = owner(1L);
        given(userRepository.findByIdOrThrow(1L)).willReturn(owner);
        given(mediaRepository.findByIdAndDeletedAtIsNullOrThrow(7L)).willReturn(media);
        given(objectStorage.head("users/1/video.mp4")).willReturn(
                new ObjectMetadata(10L, "video/mp4", "etag", Instant.now(), "version-1"));
        given(objectStorage.read("users/1/video.mp4", "version-1"))
                .willReturn(mp4WithDuration(5_001, 1_000));

        assertMediaError(() -> mediaService.mediaUploaded(7L, List.of(), 1L),
                ErrorCode.MEDIA_DURATION_INVALID);

        assertThat(media.getStatus()).isEqualTo(MediaStatus.FAILED);
        then(mediaRepository).should().save(media);
        then(objectStorage).should().delete("users/1/video.mp4", "version-1");
    }

    @Test
    void multipartCompletionSkipsRepeatedCompleteAfterTransientHeadFailureRetry() {
        Media media = new Media(MediaType.IMAGE, "users/1/image.png", 1L, 9L * 1024 * 1024, "upload-1");
        User owner = owner(1L);
        given(userRepository.findByIdOrThrow(1L)).willReturn(owner);
        given(mediaRepository.findByIdAndDeletedAtIsNullOrThrow(7L)).willReturn(media);
        given(mediaRepository.save(media)).willReturn(media);
        List<MultipartUploaded> parts = List.of(new MultipartUploaded(1, "etag"));
        ObjectMetadata verified = new ObjectMetadata(
                9L * 1024 * 1024, "image/jpeg", "etag", Instant.now(), "version-1");
        // 1) objectExists 선확인: 아직 병합 전 -> completeMultipartUpload 호출
        // 2) headUploadedObject: 병합 직후 HEAD 일시 장애 -> 503, Media는 INIT 유지
        // 3) 재시도 objectExists: 이미 병합된 객체 확인 -> completeMultipartUpload 재호출 생략
        // 4) 재시도 headUploadedObject: 검증 성공 -> COMPLETED
        given(objectStorage.head("users/1/image.png"))
                .willThrow(new ObjectNotFoundException("head", new IllegalStateException("missing")))
                .willThrow(new StorageProviderUnavailableException("head", new IllegalStateException("timeout")))
                .willReturn(verified)
                .willReturn(verified);

        assertMediaError(() -> mediaService.mediaUploaded(7L, parts, 1L),
                ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
        assertThat(media.getStatus()).isEqualTo(MediaStatus.INIT);

        Media result = mediaService.mediaUploaded(7L, parts, 1L);

        assertThat(result.getStatus()).isEqualTo(MediaStatus.COMPLETED);
        then(multipartService).should(org.mockito.Mockito.times(1))
                .completeMultipartUpload("users/1/image.png", "upload-1", parts);
    }

    private void assertMediaNotFound(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
    }

    private void assertMediaError(Runnable invocation, ErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private User owner(long id) {
        User user = mock(User.class);
        given(user.getId()).willReturn(id);
        return user;
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

    private static byte[] mp4WithDuration(long duration, long timescale) {
        ByteBuffer mvhdPayload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        mvhdPayload.putInt(0); // version 0 + flags
        mvhdPayload.putInt(0); // creation time
        mvhdPayload.putInt(0); // modification time
        mvhdPayload.putInt(Math.toIntExact(timescale));
        mvhdPayload.putInt(Math.toIntExact(duration));
        return join(box("ftyp", new byte[8]), box("moov", box("mvhd", mvhdPayload.array())));
    }

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer value = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        value.putInt(value.capacity());
        value.put(type.getBytes(StandardCharsets.ISO_8859_1));
        value.put(payload);
        return value.array();
    }

    private static byte[] join(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        ByteBuffer result = ByteBuffer.allocate(length);
        for (byte[] value : values) {
            result.put(value);
        }
        return result.array();
    }
}
