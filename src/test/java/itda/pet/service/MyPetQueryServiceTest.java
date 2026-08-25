package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.pet.domain.Pet;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.PetHelpfulReceivedCountQueryService;
import itda.media.service.MediaService;
import itda.petverification.PetVerificationBadgeService;
import itda.user.domain.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MyPetQueryService")
class MyPetQueryServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;

    @Mock
    private PetRepository petRepository;

    @Mock
    private MediaService mediaService;
    @Mock
    private PetVerificationBadgeService badgeService;
    @Mock
    private PetHelpfulReceivedCountQueryService helpfulReceivedCounts;

    private MyPetQueryService service;

    @BeforeEach
    void setUp() {
        service = new MyPetQueryService(petRepository, mediaService, badgeService, helpfulReceivedCounts);
        lenient().when(helpfulReceivedCounts.countForPets(anyCollection())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("내 Pet 목록은 인증 배지를 단일 batch 조회로 조립한다")
    void assemblesMyPetListBadgesWithOneBatchLookup() {
        Pet verified = pet(user(USER_ID), null);
        Pet unverified = pet(user(USER_ID), null);
        ReflectionTestUtils.setField(unverified, "id", 3L);
        Instant verifiedAt = Instant.parse("2026-08-12T12:00:00Z");
        given(petRepository.findMyPetsOrdered(USER_ID)).willReturn(List.of(verified, unverified));
        given(badgeService.verifiedAtByPetIds(argThat(ids -> Set.copyOf(ids).equals(Set.of(PET_ID, 3L)))) )
                .willReturn(Map.of(PET_ID, verifiedAt));
        given(helpfulReceivedCounts.countForPets(argThat(ids -> Set.copyOf(ids).equals(Set.of(PET_ID, 3L)))))
                .willReturn(Map.of(PET_ID, 7L, 3L, 2L));

        List<PetResponse> responses = service.getMyPets(USER_ID);

        assertThat(responses).extracting(PetResponse::verified).containsExactly(true, false);
        assertThat(responses.getFirst().verifiedAt()).isEqualTo(verifiedAt);
        assertThat(responses).extracting(PetResponse::helpfulReceivedCount).containsExactly(7L, 2L);
        then(badgeService).should().verifiedAtByPetIds(argThat(ids -> Set.copyOf(ids).equals(Set.of(PET_ID, 3L))));
        then(badgeService).should(never()).verifiedAt(PET_ID);
        then(badgeService).should(never()).verifiedAt(3L);
        then(helpfulReceivedCounts).should().countForPets(argThat(ids -> Set.copyOf(ids).equals(Set.of(PET_ID, 3L))));
    }

    @Nested
    @DisplayName("Describe: 본인 소유의 미삭제 Pet을 조회한다")
    class DescribeGetMyPet {

        @Test
        @DisplayName("It: Pet 필드를 응답으로 매핑하고 현재 Active 상태를 계산한다")
        void itMapsPetAndCalculatesActiveState() {
            User owner = user(USER_ID);
            owner.selectActivePet(PET_ID);
            Pet pet = pet(owner, List.of("친화적", "활발함"));
            given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID)).willReturn(Optional.of(pet));
            given(helpfulReceivedCounts.countForPet(PET_ID)).willReturn(9L);

            PetResponse response = service.getMyPet(USER_ID, PET_ID);

            assertThat(response.petId()).isEqualTo(PET_ID);
            assertThat(response.ownerUserId()).isEqualTo(USER_ID);
            assertThat(response.ownerPublicTag()).isEqualTo("보호자#A7K2");
            assertThat(response.publicTag()).isEqualTo("몽이#B8M3");
            assertThat(response.nickname()).isEqualTo("몽이");
            assertThat(response.breedName()).isEqualTo("말티즈");
            assertThat(response.sex()).isEqualTo(PetSex.FEMALE);
            assertThat(response.neutered()).isTrue();
            assertThat(response.birthDate()).isEqualTo(LocalDate.of(2020, 1, 2));
            assertThat(response.weightKg()).isEqualByComparingTo("3.40");
            assertThat(response.sizeCode()).isEqualTo(PetSizeCode.SMALL);
            assertThat(response.bio()).isEqualTo("사람을 좋아해요.");
            assertThat(response.personalityTags())
                    .containsExactly("친화적", "활발함");
            assertThat(response.careNote()).isEqualTo("닭고기 알레르기");
            assertThat(response.profileUrl()).isNull();
            assertThat(response.status()).isEqualTo(PetStatus.ACTIVE);
            assertThat(response.deletedAt()).isNull();
            assertThat(response.verified()).isFalse();
            assertThat(response.verifiedAt()).isNull();
            assertThat(response.active()).isTrue();
            assertThat(response.helpfulReceivedCount()).isEqualTo(9L);
            assertThatThrownBy(() -> response.personalityTags().add("차분함"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("It: 프로필 Media가 있으면 Presigned URL을 상세 응답에 전달한다")
        void itPropagatesProfileUrlToDetailResponse() {
            Pet pet = pet(user(USER_ID), null);
            ReflectionTestUtils.setField(
                    pet, "profileAsset", profileMedia(31L)
            );
            given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID)).willReturn(Optional.of(pet));
            given(mediaService.getPresignedDownloadUrls(anyCollection())).willReturn(Map.of(
                    31L, new MediaService.PresignedDownloadUrl(
                            "https://presigned.example/pet/31", Instant.now()
                    )
            ));

            PetResponse response = service.getMyPet(USER_ID, PET_ID);

            assertThat(response.profileUrl())
                    .isEqualTo("https://presigned.example/pet/31");
            then(mediaService).should().getPresignedDownloadUrls(argThat(
                    media -> media.stream().map(Media::getId).toList().equals(List.of(31L))
            ));
            then(mediaService).should(never()).getPresignedDownloadUrl(31L);
        }

        @Test
        @DisplayName("It: personalityTags가 null이면 빈 배열을 반환한다")
        void itReturnsEmptyPersonalityTagsForNullValue() {
            User owner = user(USER_ID);
            Pet pet = pet(owner, null);
            given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID)).willReturn(Optional.of(pet));

            PetResponse response = service.getMyPet(USER_ID, PET_ID);

            assertThat(response.personalityTags()).isEmpty();
            assertThat(response.active()).isFalse();
        }

        @Test
        @DisplayName("It: 다른 Pet이 Active여도 현재 Pet은 active=false다")
        void itReturnsInactiveWhenAnotherPetIsSelected() {
            User owner = user(USER_ID);
            owner.selectActivePet(3L);
            Pet pet = pet(owner, null);
            given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID)).willReturn(Optional.of(pet));

            assertThat(service.getMyPet(USER_ID, PET_ID).active()).isFalse();
        }

        @Test
        @DisplayName("It: 본인 소유의 미삭제 SUSPENDED Pet도 현재 Active 상태와 함께 반환한다")
        void itReturnsOwnedSuspendedPet() {
            User owner = user(USER_ID);
            owner.selectActivePet(PET_ID);
            Pet pet = pet(owner, null);
            ReflectionTestUtils.setField(pet, "status", PetStatus.SUSPENDED);
            given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID)).willReturn(Optional.of(pet));

            PetResponse response = service.getMyPet(USER_ID, PET_ID);

            assertThat(response.status()).isEqualTo(PetStatus.SUSPENDED);
            assertThat(response.active()).isTrue();
        }

        @Test
        @DisplayName("It: Pet이 없으면 PET_NOT_FOUND를 반환한다")
        void itRejectsMissingPet() {
            given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID)).willReturn(Optional.empty());

            assertErrorCode(
                    () -> service.getMyPet(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_FOUND
            );
        }

        @Test
        @DisplayName("It: 삭제된 Pet은 소유권보다 먼저 PET_NOT_FOUND를 반환한다")
        void itRejectsDeletedPetBeforeOwnershipCheck() {
            Pet pet = pet(user(9L), null);
            ReflectionTestUtils.setField(pet, "status", PetStatus.DELETED);
            ReflectionTestUtils.setField(pet, "deletedAt", Instant.now());
            given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID)).willReturn(Optional.of(pet));

            assertErrorCode(
                    () -> service.getMyPet(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_FOUND
            );
        }

        @Test
        @DisplayName("It: 미삭제 타인 Pet은 PET_NOT_OWNED를 반환한다")
        void itRejectsPetOwnedByAnotherUser() {
            given(petRepository.findByIdWithOwnerAndProfileAsset(PET_ID))
                    .willReturn(Optional.of(pet(user(9L), null)));

            assertErrorCode(
                    () -> service.getMyPet(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_OWNED
            );
        }
    }

    @Nested
    @DisplayName("Describe: source Pet의 소유권과 미삭제 상태를 검증한다")
    class DescribeRequireOwnedUndeletedPet {

        @Test
        @DisplayName("It: 본인 소유 ACTIVE Pet을 허용한다")
        void itAllowsOwnedActivePet() {
            given(petRepository.findById(PET_ID))
                    .willReturn(Optional.of(pet(user(USER_ID), null)));

            service.requireOwnedUndeletedPet(USER_ID, PET_ID);

            then(petRepository).should().findById(PET_ID);
        }

        @Test
        @DisplayName("It: 본인 소유 SUSPENDED Pet을 허용한다")
        void itAllowsOwnedSuspendedPet() {
            Pet pet = pet(user(USER_ID), null);
            ReflectionTestUtils.setField(pet, "status", PetStatus.SUSPENDED);
            given(petRepository.findById(PET_ID))
                    .willReturn(Optional.of(pet));

            service.requireOwnedUndeletedPet(USER_ID, PET_ID);

            then(petRepository).should().findById(PET_ID);
        }

        @Test
        @DisplayName("It: 현재 Active Pet이 아니어도 허용한다")
        void itDoesNotRequireCurrentActivePet() {
            User owner = user(USER_ID);
            owner.selectActivePet(99L);
            given(petRepository.findById(PET_ID))
                    .willReturn(Optional.of(pet(owner, null)));

            service.requireOwnedUndeletedPet(USER_ID, PET_ID);

            then(petRepository).should().findById(PET_ID);
        }

        @Test
        @DisplayName("It: Pet이 없으면 PET_NOT_FOUND를 반환한다")
        void itRejectsMissingPet() {
            given(petRepository.findById(PET_ID)).willReturn(Optional.empty());

            assertErrorCode(
                    () -> service.requireOwnedUndeletedPet(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_FOUND
            );
        }

        @Test
        @DisplayName("It: 삭제된 타인 Pet도 소유권보다 먼저 PET_NOT_FOUND를 반환한다")
        void itRejectsDeletedPetBeforeOwnershipCheck() {
            Pet pet = pet(user(9L), null);
            ReflectionTestUtils.setField(pet, "status", PetStatus.DELETED);
            ReflectionTestUtils.setField(pet, "deletedAt", Instant.now());
            given(petRepository.findById(PET_ID))
                    .willReturn(Optional.of(pet));

            assertErrorCode(
                    () -> service.requireOwnedUndeletedPet(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_FOUND
            );
        }

        @Test
        @DisplayName("It: DELETED 상태는 deletedAt과 무관하게 PET_NOT_FOUND를 반환한다")
        void itRejectsDeletedStatusWithoutDeletedAt() {
            Pet pet = pet(user(USER_ID), null);
            ReflectionTestUtils.setField(pet, "status", PetStatus.DELETED);
            given(petRepository.findById(PET_ID))
                    .willReturn(Optional.of(pet));

            assertErrorCode(
                    () -> service.requireOwnedUndeletedPet(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_FOUND
            );
        }

        @Test
        @DisplayName("It: 미삭제 타인 Pet은 PET_NOT_OWNED를 반환한다")
        void itRejectsPetOwnedByAnotherUser() {
            given(petRepository.findById(PET_ID))
                    .willReturn(Optional.of(pet(user(9L), null)));

            assertErrorCode(
                    () -> service.requireOwnedUndeletedPet(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_OWNED
            );
        }
    }

    @Nested
    @DisplayName("Describe: 본인 소유의 미삭제 Pet 목록을 조회한다")
    class DescribeGetMyPets {

        @Test
        @DisplayName("It: Pet이 없으면 빈 불변 List를 반환한다")
        void itReturnsEmptyImmutableList() {
            given(petRepository.findMyPetsOrdered(USER_ID))
                    .willReturn(List.of());

            List<PetResponse> responses = service.getMyPets(USER_ID);

            assertThat(responses).isEmpty();
            assertThatThrownBy(() -> responses.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            then(petRepository).should().findMyPetsOrdered(USER_ID);
        }

        @Test
        @DisplayName("It: Active 여부와 SUSPENDED 상태를 그대로 매핑한다")
        void itMapsActiveStateAndSuspendedPet() {
            Long inactivePetId = 3L;
            Long activePetId = 4L;
            User owner = user(USER_ID);
            owner.selectActivePet(activePetId);
            Pet inactivePet = pet(
                    owner,
                    inactivePetId,
                    "몽이#B8M3",
                    "몽이",
                    List.of("친화적")
            );
            Pet activeSuspendedPet = pet(
                    owner,
                    activePetId,
                    "초코#C9N4",
                    "초코",
                    null
            );
            ReflectionTestUtils.setField(
                    activeSuspendedPet,
                    "status",
                    PetStatus.SUSPENDED
            );
            given(petRepository.findMyPetsOrdered(USER_ID))
                    .willReturn(List.of(activeSuspendedPet, inactivePet));

            List<PetResponse> responses = service.getMyPets(USER_ID);

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).petId()).isEqualTo(activePetId);
            assertThat(responses.get(0).ownerUserId()).isEqualTo(USER_ID);
            assertThat(responses.get(0).ownerPublicTag())
                    .isEqualTo("보호자#A7K2");
            assertThat(responses.get(0).publicTag()).isEqualTo("초코#C9N4");
            assertThat(responses.get(0).nickname()).isEqualTo("초코");
            assertThat(responses.get(0).status())
                    .isEqualTo(PetStatus.SUSPENDED);
            assertThat(responses.get(0).personalityTags()).isEmpty();
            assertThat(responses.get(0).active()).isTrue();
            assertThat(responses.get(1).petId()).isEqualTo(inactivePetId);
            assertThat(responses.get(1).active()).isFalse();
        }

        @Test
        @DisplayName("It: 목록에서 프로필 Media URL을 각 Pet 응답에 전달한다")
        void itPropagatesProfileUrlToMyPetList() {
            Pet profiled = pet(user(USER_ID), null);
            ReflectionTestUtils.setField(
                    profiled, "profileAsset", profileMedia(32L)
            );
            given(petRepository.findMyPetsOrdered(USER_ID))
                    .willReturn(List.of(profiled));
            given(mediaService.getPresignedDownloadUrls(anyCollection())).willReturn(Map.of(
                    32L, new MediaService.PresignedDownloadUrl(
                            "https://presigned.example/pet/32", Instant.now()
                    )
            ));

            List<PetResponse> responses = service.getMyPets(USER_ID);

            assertThat(responses).singleElement()
                    .extracting(PetResponse::profileUrl)
                    .isEqualTo("https://presigned.example/pet/32");
            then(mediaService).should().getPresignedDownloadUrls(argThat(
                    media -> media.stream().map(Media::getId).toList().equals(List.of(32L))
            ));
            then(mediaService).should(never()).getPresignedDownloadUrl(32L);
        }

        @Test
        @DisplayName("It: 여러 fetch-loaded profile Media를 단일 collection signing으로 조립한다")
        void batchSignsDistinctLoadedProfileAssetsWithoutSingleMediaLookup() {
            Pet first = pet(user(USER_ID), null);
            Pet second = pet(user(USER_ID), 3L, "초코#C9N4", "초코", null);
            Media firstAsset = profileMedia(32L);
            Media secondAsset = profileMedia(33L);
            ReflectionTestUtils.setField(first, "profileAsset", firstAsset);
            ReflectionTestUtils.setField(second, "profileAsset", secondAsset);
            given(petRepository.findMyPetsOrdered(USER_ID)).willReturn(List.of(first, second));
            given(mediaService.getPresignedDownloadUrls(anyCollection())).willReturn(Map.of(
                    32L, new MediaService.PresignedDownloadUrl("https://url/32", Instant.now()),
                    33L, new MediaService.PresignedDownloadUrl("https://url/33", Instant.now())
            ));

            List<PetResponse> responses = service.getMyPets(USER_ID);

            assertThat(responses).extracting(PetResponse::profileUrl)
                    .containsExactly("https://url/32", "https://url/33");
            then(mediaService).should().getPresignedDownloadUrls(argThat(media ->
                    media.stream().map(Media::getId).toList().equals(List.of(32L, 33L))
            ));
            then(mediaService).should(never()).getPresignedDownloadUrl(org.mockito.ArgumentMatchers.anyLong());
        }
    }

    private User user(Long id) {
        User user = User.register(
                "user%s@example.com".formatted(id),
                "encoded",
                "보호자",
                "보호자#A7K2",
                "4113111500"
        );
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Pet pet(User owner, List<String> personalityTags) {
        return pet(
                owner,
                PET_ID,
                "몽이#B8M3",
                "몽이",
                personalityTags
        );
    }

    private Pet pet(
            User owner,
            Long petId,
            String publicTag,
            String nickname,
            List<String> personalityTags
    ) {
        Pet pet = Pet.register(
                owner,
                publicTag,
                nickname,
                "말티즈",
                PetSex.FEMALE,
                true,
                LocalDate.of(2020, 1, 2),
                new BigDecimal("3.40"),
                PetSizeCode.SMALL,
                "사람을 좋아해요.",
                personalityTags,
                "닭고기 알레르기"
        );
        ReflectionTestUtils.setField(pet, "id", petId);
        return pet;
    }

    private Media profileMedia(Long mediaId) {
        Media media = new Media(
                MediaType.IMAGE, "users/1/pets/profile.jpg", USER_ID, 1L
        );
        ReflectionTestUtils.setField(media, "id", mediaId);
        ReflectionTestUtils.setField(media, "status", MediaStatus.UPLOADED);
        return media;
    }

    private void assertErrorCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(errorCode);
    }
}
