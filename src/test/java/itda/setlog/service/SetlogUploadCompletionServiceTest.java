package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.properties.S3Properties;
import itda.media.storage.ObjectMetadata;
import itda.media.storage.ObjectNotFoundException;
import itda.media.storage.ObjectStorage;
import itda.media.storage.PresignedDownload;
import itda.media.storage.StorageProviderRejectedException;
import itda.media.storage.StorageProviderUnavailableException;
import itda.setlog.service.SetlogUploadCompletionTransactionService.CompletionSnapshot;
import itda.setlog.service.SetlogUploadCompletionTransactionService.CompletionAttempt;
import itda.setlog.service.SetlogUploadCompletionTransactionService.PreparedUpload;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SetlogUploadCompletionServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final UUID UPLOAD_ID = UUID.fromString("10f7ed34-8aa7-4ffc-b3be-7a72c5d3bf35");
    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String KEY = "setlogs/1/12/video.mp4";
    private static final Instant LAST_MODIFIED = Instant.parse("2026-08-12T01:00:00Z");

    @Mock private SetlogUploadCompletionTransactionService transactions;
    @Mock private ObjectStorage objectStorage;
    @Mock private PetDisplayQueryService petDisplayQueryService;

    private SetlogUploadCompletionService service;

    @BeforeEach
    void setUp() {
        service = new SetlogUploadCompletionService(
                transactions,
                objectStorage,
                new S3Properties(null, null, "bucket", "ap-northeast-2", 600L),
                petDisplayQueryService
        );
        org.mockito.Mockito.lenient().when(
                petDisplayQueryService.getPetDisplaySummary(12L)
        ).thenReturn(new PetDisplaySummary(
                12L, OWNER_ID, "정본#A7K2", "정본이",
                "https://example.com/profile.jpg", true,
                PetStatus.ACTIVE, null
        ));
    }

    @Test
    void completesAfterHeadAndUsesExactVersionForDownload() {
        PreparedUpload prepared = PreparedUpload.pending(KEY, 1024L, "video/mp4");
        ObjectMetadata metadata = metadata(1024L, "video/mp4");
        CompletionSnapshot completed = snapshot(false, "version-7");
        given(transactions.prepare(org.mockito.ArgumentMatchers.eq(OWNER_ID),
                org.mockito.ArgumentMatchers.eq(UPLOAD_ID), org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.any(Instant.class))).willReturn(prepared);
        given(objectStorage.head(KEY)).willReturn(metadata);
        given(transactions.finalizeUpload(org.mockito.ArgumentMatchers.eq(OWNER_ID),
                org.mockito.ArgumentMatchers.eq(UPLOAD_ID), org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.eq("오늘 산책"),
                org.mockito.ArgumentMatchers.eq(metadata), org.mockito.ArgumentMatchers.any(Instant.class)))
                .willReturn(CompletionAttempt.completed(completed));
        given(objectStorage.presignGet(KEY, "version-7", Duration.ofMinutes(10)))
                .willReturn(new PresignedDownload("https://storage.example/video", LAST_MODIFIED.plusSeconds(600)));

        SetlogUploadCompletionService.CompletionResult result =
                service.complete(OWNER_ID, UPLOAD_ID, REQUEST_ID, "오늘 산책");

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().source().name()).isEqualTo("USER");
        assertThat(result.response().mediaUrl()).isEqualTo("https://storage.example/video");
        assertThat(result.response().authorPet().publicTag()).isEqualTo("정본#A7K2");
        assertThat(result.response().authorPet().profileUrl())
                .isEqualTo("https://example.com/profile.jpg");
        assertThat(result.response().authorPet().verified()).isTrue();
        assertThat(result.response().caption()).isEqualTo("오늘 산책");
        then(objectStorage).should().head(KEY);
        then(objectStorage).should().presignGet(KEY, "version-7", Duration.ofMinutes(10));
    }

    @Test
    void replaySkipsHeadAndReturnsExistingSetlog() {
        CompletionSnapshot replay = snapshot(true, null);
        given(transactions.prepare(org.mockito.ArgumentMatchers.eq(OWNER_ID),
                org.mockito.ArgumentMatchers.eq(UPLOAD_ID), org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.any(Instant.class))).willReturn(PreparedUpload.replayed(replay));
        given(objectStorage.presignGet(KEY, null, Duration.ofMinutes(10)))
                .willReturn(new PresignedDownload("https://storage.example/video", LAST_MODIFIED));

        SetlogUploadCompletionService.CompletionResult result =
                service.complete(OWNER_ID, UPLOAD_ID, REQUEST_ID, "다른 캡션");

        assertThat(result.replayed()).isTrue();
        assertThat(result.response().setlogId()).isEqualTo(77L);
        then(objectStorage).should(never()).head(org.mockito.ArgumentMatchers.anyString());
        then(transactions).should(never()).finalizeUpload(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingObjectMapsToNotFoundAndDoesNotFinalize() {
        stubPending();
        given(objectStorage.head(KEY)).willThrow(
                new ObjectNotFoundException("head", new RuntimeException()));

        assertError(ErrorCode.SETLOG_UPLOAD_OBJECT_NOT_FOUND);

        then(transactions).should(never()).finalizeUpload(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unavailableHeadRemainsRetryable() {
        stubPending();
        given(objectStorage.head(KEY)).willThrow(
                new StorageProviderUnavailableException("head", new RuntimeException()));

        assertError(ErrorCode.SETLOG_UPLOAD_STORAGE_UNAVAILABLE);
    }

    @Test
    void rejectedHeadIsNotMisclassifiedAsUnavailable() {
        stubPending();
        given(objectStorage.head(KEY)).willThrow(
                new StorageProviderRejectedException("head", 403, new RuntimeException()));

        assertError(ErrorCode.SETLOG_UPLOAD_COMPLETE_STORAGE_REJECTED);
    }

    @Test
    void rejectsNullInputsBeforeStorageOrDatabaseAccess() {
        assertThatThrownBy(() -> service.complete(null, UPLOAD_ID, REQUEST_ID, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
        then(transactions).shouldHaveNoInteractions();
        then(objectStorage).shouldHaveNoInteractions();
    }

    @Test
    void mapsCompletionUniqueRaceToConflict() {
        stubPending();
        ObjectMetadata metadata = metadata(1024L, "video/mp4");
        given(objectStorage.head(KEY)).willReturn(metadata);
        given(transactions.finalizeUpload(org.mockito.ArgumentMatchers.eq(OWNER_ID),
                org.mockito.ArgumentMatchers.eq(UPLOAD_ID), org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq(metadata), org.mockito.ArgumentMatchers.any(Instant.class)))
                .willThrow(new DataIntegrityViolationException(
                        "duplicate key violates uk_setlogs_media"));

        assertError(ErrorCode.SETLOG_UPLOAD_STATE_CONFLICT);
    }

    @Test
    void unrelatedIntegrityFailureIsNotHiddenAsCompletionConflict() {
        stubPending();
        ObjectMetadata metadata = metadata(1024L, "video/mp4");
        given(objectStorage.head(KEY)).willReturn(metadata);
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("fk_media_user violated");
        given(transactions.finalizeUpload(org.mockito.ArgumentMatchers.eq(OWNER_ID),
                org.mockito.ArgumentMatchers.eq(UPLOAD_ID), org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq(metadata), org.mockito.ArgumentMatchers.any(Instant.class)))
                .willThrow(failure);

        assertThatThrownBy(() -> service.complete(OWNER_ID, UPLOAD_ID, REQUEST_ID, null))
                .isSameAs(failure);
    }

    private void stubPending() {
        given(transactions.prepare(org.mockito.ArgumentMatchers.eq(OWNER_ID),
                org.mockito.ArgumentMatchers.eq(UPLOAD_ID), org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .willReturn(PreparedUpload.pending(KEY, 1024L, "video/mp4"));
    }

    private void assertError(ErrorCode expected) {
        assertThatThrownBy(() -> service.complete(OWNER_ID, UPLOAD_ID, REQUEST_ID, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private ObjectMetadata metadata(long size, String contentType) {
        return new ObjectMetadata(size, contentType, "etag", LAST_MODIFIED, "version-7");
    }

    private CompletionSnapshot snapshot(boolean replayed, String versionId) {
        return new CompletionSnapshot(
                77L, 12L, "몽이#A7K2", "몽이", "오늘 산책", KEY, versionId,
                Instant.parse("2026-08-12T01:01:00Z"), replayed
        );
    }
}
