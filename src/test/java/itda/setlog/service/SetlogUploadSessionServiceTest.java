package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.storage.ObjectStorage;
import itda.media.storage.PresignedUpload;
import itda.media.storage.StorageProviderRejectedException;
import itda.media.storage.StorageProviderUnavailableException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.setlog.domain.SetlogUpload;
import itda.setlog.domain.SetlogUploadStatus;
import itda.setlog.dto.SetlogUploadCreateRequest;
import itda.setlog.dto.SetlogUploadCreateResponse;
import itda.setlog.repository.SetlogUploadRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SetlogUploadSessionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 12L;
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-12T01:15:00Z");

    @Mock private UserRepository userRepository;
    @Mock private PetRepository petRepository;
    @Mock private SetlogUploadRepository uploadRepository;
    @Mock private ObjectStorage objectStorage;

    private SetlogUploadSessionService service;

    @BeforeEach
    void setUp() {
        service = new SetlogUploadSessionService(
                userRepository, petRepository, uploadRepository, objectStorage
        );
    }

    @ParameterizedTest
    @CsvSource({"video/mp4,mp4", "video/webm,webm"})
    void createsPresignedSessionForAllowedVideo(String contentType, String extension) {
        stubActiveOwnerAndPet();
        PresignedUpload presigned = new PresignedUpload(
                "https://storage.example/upload",
                Map.of("Content-Type", contentType, "Content-Length", "1024"),
                EXPIRES_AT
        );
        given(objectStorage.presignPut(any(), any(), any(Long.class), any(Duration.class)))
                .willReturn(presigned);

        SetlogUploadCreateResponse response = service.create(
                USER_ID,
                new SetlogUploadCreateRequest(PET_ID, "ignored.exe", contentType, 1024L)
        );

        assertThat(response.uploadUrl()).isEqualTo(presigned.url());
        assertThat(response.headers()).containsEntry("Content-Type", contentType);
        assertThat(response.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(response.objectKey())
                .matches("setlogs/1/12/[0-9a-f\\-]{36}\\." + extension)
                .doesNotContain("ignored", "..", "exe");

        then(objectStorage).should().presignPut(
                response.objectKey(), contentType, 1024L, Duration.ofMinutes(15)
        );
        ArgumentCaptor<SetlogUpload> captor = ArgumentCaptor.forClass(SetlogUpload.class);
        then(uploadRepository).should().save(captor.capture());
        SetlogUpload saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(response.uploadId());
        assertThat(saved.getObjectKey()).isEqualTo(response.objectKey());
        assertThat(saved.getContentType()).isEqualTo(contentType);
        assertThat(saved.getExpectedSize()).isEqualTo(1024L);
        assertThat(saved.getStatus()).isEqualTo(SetlogUploadStatus.PRESIGNED);
        assertThat(saved.getExpiresAt()).isEqualTo(EXPIRES_AT);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 209715200L})
    void acceptsSizeBoundaries(long size) {
        stubActiveOwnerAndPet();
        given(objectStorage.presignPut(any(), any(), any(Long.class), any(Duration.class)))
                .willReturn(new PresignedUpload("https://storage.example/upload", Map.of(), EXPIRES_AT));

        service.create(USER_ID, request("video/mp4", size));

        then(objectStorage).should().presignPut(any(), any(), org.mockito.ArgumentMatchers.eq(size), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L})
    void rejectsNonPositiveSize(long size) {
        assertBusinessError(request("video/mp4", size), ErrorCode.SETLOG_UPLOAD_SIZE_INVALID);
    }

    @Test
    void rejectsSizeOverLimit() {
        assertBusinessError(request("video/mp4", 209715201L), ErrorCode.UPLOAD_SIZE_EXCEEDED);
    }

    @Test
    void rejectsUnsupportedContentTypeExactly() {
        assertBusinessError(request("Video/MP4", 1024L), ErrorCode.UPLOAD_CONTENT_TYPE_UNSUPPORTED);
    }

    @Test
    void rejectsPathTraversalFileName() {
        assertBusinessError(
                new SetlogUploadCreateRequest(PET_ID, "../walk.mp4", "video/mp4", 1024L),
                ErrorCode.SETLOG_UPLOAD_FILE_NAME_INVALID
        );
    }

    @Test
    void rejectsPetThatIsNotCurrentActivePet() {
        User user = mock(User.class);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(user.isActive()).willReturn(true);
        given(user.hasActivePet()).willReturn(true);
        given(user.isActivePet(PET_ID)).willReturn(false);

        assertBusinessError(request("video/mp4", 1024L), ErrorCode.SETLOG_UPLOAD_PET_FORBIDDEN);
        then(petRepository).shouldHaveNoInteractions();
        then(objectStorage).shouldHaveNoInteractions();
    }

    @Test
    void rejectsWhenNoActivePetExists() {
        User user = mock(User.class);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(user.isActive()).willReturn(true);
        given(user.hasActivePet()).willReturn(false);

        assertBusinessError(request("video/mp4", 1024L), ErrorCode.ACTIVE_PET_REQUIRED);
        then(objectStorage).shouldHaveNoInteractions();
    }

    @Test
    void rejectsActivePetThatDoesNotBelongToAuthenticatedUser() {
        User user = mock(User.class);
        Pet pet = mock(Pet.class);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(user.isActive()).willReturn(true);
        given(user.hasActivePet()).willReturn(true);
        given(user.isActivePet(PET_ID)).willReturn(true);
        given(petRepository.findByIdForUpdate(PET_ID)).willReturn(Optional.of(pet));
        given(pet.belongsTo(USER_ID)).willReturn(false);

        assertBusinessError(request("video/mp4", 1024L), ErrorCode.SETLOG_UPLOAD_PET_FORBIDDEN);
        then(objectStorage).shouldHaveNoInteractions();
    }

    private void stubActiveOwnerAndPet() {
        User user = mock(User.class);
        Pet pet = mock(Pet.class);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(user.isActive()).willReturn(true);
        given(user.hasActivePet()).willReturn(true);
        given(user.isActivePet(PET_ID)).willReturn(true);
        given(petRepository.findByIdForUpdate(PET_ID)).willReturn(Optional.of(pet));
        given(pet.belongsTo(USER_ID)).willReturn(true);
        given(pet.isActive()).willReturn(true);
        given(pet.getId()).willReturn(PET_ID);
    }

    @Test
    void mapsRetryableStorageFailureToServiceUnavailable() {
        stubActiveOwnerAndPet();
        given(objectStorage.presignPut(any(), any(), any(Long.class), any(Duration.class)))
                .willThrow(new StorageProviderUnavailableException("presignPut", new RuntimeException()));

        assertBusinessError(request("video/mp4", 1024L), ErrorCode.SETLOG_UPLOAD_STORAGE_UNAVAILABLE);
    }

    @Test
    void mapsNonRetryableStorageRejectionToBadGatewayError() {
        stubActiveOwnerAndPet();
        given(objectStorage.presignPut(any(), any(), any(Long.class), any(Duration.class)))
                .willThrow(new StorageProviderRejectedException("presignPut", 403, new RuntimeException()));

        assertBusinessError(request("video/mp4", 1024L), ErrorCode.SETLOG_UPLOAD_STORAGE_REJECTED);
    }

    @Test
    void validatesNullRequestWithoutRepositoryAccess() {
        assertThatThrownBy(() -> service.create(USER_ID, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
        then(userRepository).shouldHaveNoInteractions();
    }

    private SetlogUploadCreateRequest request(String contentType, long size) {
        return new SetlogUploadCreateRequest(PET_ID, "walk.mp4", contentType, size);
    }

    private void assertBusinessError(SetlogUploadCreateRequest request, ErrorCode errorCode) {
        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
        then(uploadRepository).should(never()).save(any());
    }
}
