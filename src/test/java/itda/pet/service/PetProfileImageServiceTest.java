package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.reset;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.repository.MediaRepository;
import itda.media.service.MediaService;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import itda.user.domain.User;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("PetProfileImageService")
class PetProfileImageServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;
    private static final Long MEDIA_ID = 3L;

    @Mock
    private PetRepository petRepository;
    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private MediaService mediaService;

    private PetProfileImageService service;

    @BeforeEach
    void setUp() {
        service = new PetProfileImageService(
                petRepository,
                mediaRepository,
                mediaService
        );
    }

    @Test
    @DisplayName("UPLOADED IMAGE를 최초 프로필로 연결하고 Presigned URL을 반환한다")
    void setsUploadedImageAsInitialProfile() {
        Pet pet = pet(USER_ID, PetStatus.ACTIVE);
        Media media = media(MEDIA_ID, USER_ID, MediaType.IMAGE,
                MediaStatus.UPLOADED, null);
        given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID))
                .willReturn(Optional.of(pet));
        given(mediaRepository.findByIdAndDeletedAtIsNull(MEDIA_ID))
                .willReturn(Optional.of(media));
        given(mediaService.getPresignedDownloadUrl(MEDIA_ID)).willReturn(
                new MediaService.PresignedDownloadUrl(
                        "https://presigned.example/media/3",
                        Instant.now()
                )
        );

        PetResponse response = service.setInitialProfileImage(
                USER_ID, PET_ID, MEDIA_ID
        );

        assertThat(pet.getProfileAsset()).isSameAs(media);
        assertThat(response.profileUrl())
                .isEqualTo("https://presigned.example/media/3");
        then(mediaService).should().getPresignedDownloadUrl(MEDIA_ID);
    }

    @Test
    @DisplayName("COMPLETED IMAGE도 최초 프로필로 허용한다")
    void allowsCompletedImage() {
        Pet pet = pet(USER_ID, PetStatus.SUSPENDED);
        Media media = media(MEDIA_ID, USER_ID, MediaType.IMAGE,
                MediaStatus.COMPLETED, null);
        given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID))
                .willReturn(Optional.of(pet));
        given(mediaRepository.findByIdAndDeletedAtIsNull(MEDIA_ID))
                .willReturn(Optional.of(media));
        given(mediaService.getPresignedDownloadUrl(MEDIA_ID)).willReturn(
                new MediaService.PresignedDownloadUrl("https://url", Instant.now())
        );

        service.setInitialProfileImage(USER_ID, PET_ID, MEDIA_ID);

        assertThat(pet.getProfileAsset()).isSameAs(media);
    }

    @Test
    @DisplayName("기존 프로필이 있으면 같은 mediaId도 conflict이고 Media를 조회하지 않는다")
    void rejectsExistingProfileBeforeMediaLookup() {
        Pet pet = pet(USER_ID, PetStatus.ACTIVE);
        ReflectionTestUtils.setField(pet, "profileAsset", media(
                MEDIA_ID, USER_ID, MediaType.IMAGE, MediaStatus.UPLOADED, null
        ));
        given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID))
                .willReturn(Optional.of(pet));

        assertError(() -> service.setInitialProfileImage(
                USER_ID, PET_ID, MEDIA_ID
        ), ErrorCode.PET_PROFILE_IMAGE_ALREADY_SET);

        then(mediaRepository).shouldHaveNoInteractions();
        then(mediaService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("INIT, FAILED, VIDEO, 타인 소유, 삭제, 없는 Media를 거절한다")
    void rejectsUnavailableMedia() {
        assertMediaError(media(MEDIA_ID, USER_ID, MediaType.IMAGE,
                MediaStatus.INIT, null), ErrorCode.MEDIA_NOT_UPLOADED);
        assertMediaError(media(MEDIA_ID, USER_ID, MediaType.IMAGE,
                MediaStatus.FAILED, null), ErrorCode.MEDIA_NOT_UPLOADED);
        assertMediaError(media(MEDIA_ID, USER_ID, MediaType.VIDEO,
                MediaStatus.UPLOADED, null), ErrorCode.INVALID_MEDIA_TYPE);
        assertMediaError(media(MEDIA_ID, 9L, MediaType.IMAGE,
                MediaStatus.UPLOADED, null), ErrorCode.MEDIA_NOT_OWNED);
        assertMediaError(media(MEDIA_ID, USER_ID, MediaType.IMAGE,
                MediaStatus.UPLOADED, Instant.now()), ErrorCode.MEDIA_NOT_FOUND);
        Pet pet = pet(USER_ID, PetStatus.ACTIVE);
        given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID))
                .willReturn(Optional.of(pet));
        given(mediaRepository.findByIdAndDeletedAtIsNull(MEDIA_ID))
                .willReturn(Optional.empty());
        assertError(() -> service.setInitialProfileImage(
                USER_ID, PET_ID, MEDIA_ID
        ), ErrorCode.MEDIA_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제된 Pet과 타인 Pet은 Media 조회 전에 거절한다")
    void rejectsUnavailablePet() {
        Pet deleted = pet(USER_ID, PetStatus.DELETED);
        given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID))
                .willReturn(Optional.of(deleted));
        assertError(() -> service.setInitialProfileImage(
                USER_ID, PET_ID, MEDIA_ID
        ), ErrorCode.PET_NOT_FOUND);

        Pet otherOwner = pet(9L, PetStatus.ACTIVE);
        given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID))
                .willReturn(Optional.of(otherOwner));
        assertError(() -> service.setInitialProfileImage(
                USER_ID, PET_ID, MEDIA_ID
        ), ErrorCode.PET_NOT_OWNED);
        then(mediaRepository).shouldHaveNoInteractions();
    }

    private void assertMediaError(Media media, ErrorCode errorCode) {
        reset(petRepository, mediaRepository, mediaService);
        Pet pet = pet(USER_ID, PetStatus.ACTIVE);
        given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID))
                .willReturn(Optional.of(pet));
        if (media.getDeletedAt() == null) {
            given(mediaRepository.findByIdAndDeletedAtIsNull(MEDIA_ID))
                    .willReturn(Optional.of(media));
        } else {
            given(mediaRepository.findByIdAndDeletedAtIsNull(MEDIA_ID))
                    .willReturn(Optional.empty());
        }

        assertError(() -> service.setInitialProfileImage(
                USER_ID, PET_ID, MEDIA_ID
        ), errorCode);
        assertThat(pet.getProfileAsset()).isNull();
    }

    private Pet pet(Long ownerId, PetStatus status) {
        User owner = User.register("owner@example.com", "encoded", "owner",
                "owner#A1B2", "4113111500");
        ReflectionTestUtils.setField(owner, "id", ownerId);
        Pet pet = Pet.register(owner, "pet#A1B2", "몽이", null, null,
                null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(pet, "id", PET_ID);
        ReflectionTestUtils.setField(pet, "status", status);
        return pet;
    }

    private Media media(
            Long id,
            Long ownerId,
            MediaType type,
            MediaStatus status,
            Instant deletedAt
    ) {
        Media media = new Media(type, "users/1/profile.jpg", ownerId, 1L);
        ReflectionTestUtils.setField(media, "id", id);
        ReflectionTestUtils.setField(media, "status", status);
        ReflectionTestUtils.setField(media, "deletedAt", deletedAt);
        return media;
    }

    private void assertError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(operation).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(errorCode);
    }
}
