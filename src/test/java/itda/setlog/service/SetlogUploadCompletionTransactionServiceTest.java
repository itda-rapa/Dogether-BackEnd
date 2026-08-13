package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.repository.MediaRepository;
import itda.media.storage.ObjectMetadata;
import itda.pet.domain.Pet;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogUpload;
import itda.setlog.domain.SetlogUploadStatus;
import itda.setlog.repository.SetlogRepository;
import itda.setlog.repository.SetlogUploadRepository;
import itda.user.domain.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SetlogUploadCompletionTransactionServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final UUID UPLOAD_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-12T01:15:00Z");
    private static final Instant NOW = Instant.parse("2026-08-12T01:00:00Z");

    @Mock private SetlogUploadRepository uploadRepository;
    @Mock private MediaRepository mediaRepository;
    @Mock private SetlogRepository setlogRepository;

    private SetlogUploadCompletionTransactionService service;

    @BeforeEach
    void setUp() {
        service = new SetlogUploadCompletionTransactionService(
                uploadRepository, mediaRepository, setlogRepository,
                new SetlogUploadProperties(false)
        );
    }

    @Test
    void hidesMissingAndForeignOwnedUploadsBehindSameNotFoundError() {
        given(uploadRepository.findOwnedByIdForUpdate(UPLOAD_ID, OWNER_ID))
                .willReturn(Optional.empty());

        assertError(() -> service.prepare(OWNER_ID, UPLOAD_ID, REQUEST_ID, NOW),
                ErrorCode.SETLOG_UPLOAD_NOT_FOUND);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "null", "NULL"})
    void requiredVersionIdFailsClosedButKeepsPresignedForRetry(
            String versionId
    ) {
        service = new SetlogUploadCompletionTransactionService(
                uploadRepository, mediaRepository, setlogRepository,
                new SetlogUploadProperties(true)
        );
        SetlogUpload upload = upload(EXPIRES_AT);
        stubOwned(upload);

        var result = service.finalizeUpload(
                OWNER_ID, UPLOAD_ID, REQUEST_ID,
                new ObjectMetadata(1024L, "video/mp4", "etag", NOW, versionId),
                NOW
        );

        assertThat(result.failure()).isEqualTo(ErrorCode.SETLOG_UPLOAD_VERSIONING_UNAVAILABLE);
        assertThat(upload.getStatus()).isEqualTo(SetlogUploadStatus.PRESIGNED);
        then(mediaRepository).shouldHaveNoInteractions();
        then(setlogRepository).shouldHaveNoInteractions();
    }

    @Test
    void expiresAtExactBoundaryAndPersistsStateDespiteBusinessError() {
        SetlogUpload upload = upload(EXPIRES_AT);
        stubOwned(upload);

        SetlogUploadCompletionTransactionService.PreparedUpload result =
                service.prepare(OWNER_ID, UPLOAD_ID, REQUEST_ID, EXPIRES_AT);

        assertThat(result.failure()).isEqualTo(ErrorCode.SETLOG_UPLOAD_EXPIRED);
        assertThat(upload.getStatus()).isEqualTo(SetlogUploadStatus.EXPIRED);
        then(mediaRepository).shouldHaveNoInteractions();
        then(setlogRepository).shouldHaveNoInteractions();
    }

    @Test
    void rejectedExpiredAndCanceledStatesCannotBeCompleted() {
        SetlogUpload rejected = upload(EXPIRES_AT);
        rejected.reject();
        stubOwned(rejected);

        assertError(() -> service.prepare(OWNER_ID, UPLOAD_ID, REQUEST_ID, NOW),
                ErrorCode.SETLOG_UPLOAD_STATE_CONFLICT);
    }

    @Test
    void metadataMismatchRejectsWithoutCreatingMediaOrSetlog() {
        SetlogUpload upload = upload(EXPIRES_AT);
        stubOwned(upload);
        ObjectMetadata wrongSize = new ObjectMetadata(
                1025L, "video/mp4", "etag", NOW, "v1"
        );

        SetlogUploadCompletionTransactionService.CompletionAttempt result =
                service.finalizeUpload(OWNER_ID, UPLOAD_ID, REQUEST_ID, wrongSize, NOW);

        assertThat(result.failure()).isEqualTo(ErrorCode.SETLOG_UPLOAD_METADATA_MISMATCH);
        assertThat(upload.getStatus()).isEqualTo(SetlogUploadStatus.REJECTED);
        then(mediaRepository).should(never()).saveAndFlush(any());
        then(setlogRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void contentTypeMismatchAlsoRejects() {
        SetlogUpload upload = upload(EXPIRES_AT);
        stubOwned(upload);
        ObjectMetadata wrongType = new ObjectMetadata(
                1024L, "video/webm", "etag", NOW, "v1"
        );

        SetlogUploadCompletionTransactionService.CompletionAttempt result =
                service.finalizeUpload(OWNER_ID, UPLOAD_ID, REQUEST_ID, wrongType, NOW);

        assertThat(result.failure()).isEqualTo(ErrorCode.SETLOG_UPLOAD_METADATA_MISMATCH);
        assertThat(upload.getStatus()).isEqualTo(SetlogUploadStatus.REJECTED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "null", "NULL"})
    void missingOrNullSentinelVersionCompletesAsUnversioned(String versionId) {
        SetlogUpload upload = upload(EXPIRES_AT);
        stubOwned(upload);
        ObjectMetadata metadata = new ObjectMetadata(
                1024L, "video/mp4", "etag", NOW, versionId
        );
        Media persistedMedia = mock(Media.class);
        Setlog persistedSetlog = mock(Setlog.class);
        given(persistedMedia.getPath()).willReturn("setlogs/1/12/video.mp4");
        given(persistedMedia.getObjectVersionId()).willReturn(null);
        given(persistedSetlog.getId()).willReturn(77L);
        given(persistedSetlog.getCreatedAt()).willReturn(NOW);
        given(mediaRepository.saveAndFlush(any(Media.class))).willReturn(persistedMedia);
        given(setlogRepository.saveAndFlush(any(Setlog.class))).willReturn(persistedSetlog);

        SetlogUploadCompletionTransactionService.CompletionAttempt result =
                service.finalizeUpload(OWNER_ID, UPLOAD_ID, REQUEST_ID, metadata, NOW);

        assertThat(result.failure()).isNull();
        assertThat(upload.getStatus()).isEqualTo(SetlogUploadStatus.COMPLETED);
        org.mockito.ArgumentCaptor<Media> captor =
                org.mockito.ArgumentCaptor.forClass(Media.class);
        then(mediaRepository).should().saveAndFlush(captor.capture());
        assertThat(captor.getValue().getObjectVersionId()).isNull();
    }

    @Test
    void nullOrIncompleteMetadataRejects() {
        SetlogUpload upload = upload(EXPIRES_AT);
        stubOwned(upload);

        SetlogUploadCompletionTransactionService.CompletionAttempt result =
                service.finalizeUpload(OWNER_ID, UPLOAD_ID, REQUEST_ID, null, NOW);

        assertThat(result.failure()).isEqualTo(ErrorCode.SETLOG_UPLOAD_METADATA_MISMATCH);
        assertThat(upload.getStatus()).isEqualTo(SetlogUploadStatus.REJECTED);
    }

    @Test
    void successfulFinalizeCreatesOneVerifiedMediaAndUserSetlog() {
        SetlogUpload upload = upload(EXPIRES_AT);
        stubOwned(upload);
        Media persistedMedia = mock(Media.class);
        Setlog persistedSetlog = mock(Setlog.class);
        given(persistedMedia.getPath()).willReturn("setlogs/1/12/video.mp4");
        given(persistedMedia.getObjectVersionId()).willReturn("v7");
        given(persistedSetlog.getId()).willReturn(77L);
        given(persistedSetlog.getCreatedAt()).willReturn(NOW);
        given(mediaRepository.saveAndFlush(any(Media.class))).willReturn(persistedMedia);
        given(setlogRepository.saveAndFlush(any(Setlog.class))).willReturn(persistedSetlog);

        SetlogUploadCompletionTransactionService.CompletionAttempt attempt =
                service.finalizeUpload(
                        OWNER_ID,
                        UPLOAD_ID,
                        REQUEST_ID,
                        new ObjectMetadata(1024L, "video/mp4", "etag", NOW, "v7"),
                        NOW
                );

        assertThat(attempt.failure()).isNull();
        assertThat(attempt.completed().setlogId()).isEqualTo(77L);
        assertThat(attempt.completed().replayed()).isFalse();
        assertThat(upload.getStatus()).isEqualTo(SetlogUploadStatus.COMPLETED);
        assertThat(upload.getCompletionRequestId()).isEqualTo(REQUEST_ID);
        assertThat(upload.getMedia()).isSameAs(persistedMedia);
        assertThat(upload.getSetlog()).isSameAs(persistedSetlog);
        then(mediaRepository).should().saveAndFlush(any(Media.class));
        then(setlogRepository).should().saveAndFlush(any(Setlog.class));
        then(uploadRepository).should().saveAndFlush(upload);
    }

    @Test
    void completedUploadReplaysOnlyForSameRequestId() {
        SetlogUpload upload = upload(EXPIRES_AT);
        Media media = mock(Media.class);
        Setlog setlog = mock(Setlog.class);
        given(media.getPath()).willReturn("setlogs/1/12/video.mp4");
        given(media.getObjectVersionId()).willReturn(null);
        given(setlog.getId()).willReturn(77L);
        given(setlog.getCreatedAt()).willReturn(NOW);
        upload.complete(REQUEST_ID, media, setlog, NOW);
        stubOwned(upload);

        SetlogUploadCompletionTransactionService.PreparedUpload replay =
                service.prepare(OWNER_ID, UPLOAD_ID, REQUEST_ID, NOW.plusSeconds(1));

        assertThat(replay.isReplay()).isTrue();
        assertThat(replay.replay().setlogId()).isEqualTo(77L);
        assertThat(replay.replay().replayed()).isTrue();

        assertError(() -> service.prepare(
                        OWNER_ID, UPLOAD_ID, UUID.randomUUID(), NOW.plusSeconds(1)),
                ErrorCode.SETLOG_UPLOAD_IDEMPOTENCY_CONFLICT);
    }

    private SetlogUpload upload(Instant expiresAt) {
        User owner = mock(User.class);
        Pet pet = mock(Pet.class);
        lenient().when(owner.isActive()).thenReturn(true);
        lenient().when(owner.isActivePet(12L)).thenReturn(true);
        lenient().when(owner.getId()).thenReturn(OWNER_ID);
        lenient().when(pet.getId()).thenReturn(12L);
        lenient().when(pet.getPublicTag()).thenReturn("몽이#A7K2");
        lenient().when(pet.getNickname()).thenReturn("몽이");
        lenient().when(pet.belongsTo(OWNER_ID)).thenReturn(true);
        lenient().when(pet.isActive()).thenReturn(true);
        return SetlogUpload.presigned(
                UPLOAD_ID,
                owner,
                pet,
                "setlogs/1/12/video.mp4",
                "video/mp4",
                1024L,
                expiresAt
        );
    }

    private void stubOwned(SetlogUpload upload) {
        given(uploadRepository.findOwnedByIdForUpdate(UPLOAD_ID, OWNER_ID))
                .willReturn(Optional.of(upload));
    }

    private void assertError(Runnable invocation, ErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
