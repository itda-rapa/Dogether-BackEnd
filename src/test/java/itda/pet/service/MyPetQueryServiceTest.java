package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import itda.user.domain.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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

    private MyPetQueryService service;

    @BeforeEach
    void setUp() {
        service = new MyPetQueryService(petRepository);
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
            given(petRepository.findById(PET_ID)).willReturn(Optional.of(pet));

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
            assertThatThrownBy(() -> response.personalityTags().add("차분함"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("It: personalityTags가 null이면 빈 배열을 반환한다")
        void itReturnsEmptyPersonalityTagsForNullValue() {
            User owner = user(USER_ID);
            Pet pet = pet(owner, null);
            given(petRepository.findById(PET_ID)).willReturn(Optional.of(pet));

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
            given(petRepository.findById(PET_ID)).willReturn(Optional.of(pet));

            assertThat(service.getMyPet(USER_ID, PET_ID).active()).isFalse();
        }

        @Test
        @DisplayName("It: Pet이 없으면 PET_NOT_FOUND를 반환한다")
        void itRejectsMissingPet() {
            given(petRepository.findById(PET_ID)).willReturn(Optional.empty());

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
            given(petRepository.findById(PET_ID)).willReturn(Optional.of(pet));

            assertErrorCode(
                    () -> service.getMyPet(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_FOUND
            );
        }

        @Test
        @DisplayName("It: 미삭제 타인 Pet은 PET_NOT_OWNED를 반환한다")
        void itRejectsPetOwnedByAnotherUser() {
            given(petRepository.findById(PET_ID))
                    .willReturn(Optional.of(pet(user(9L), null)));

            assertErrorCode(
                    () -> service.getMyPet(USER_ID, PET_ID),
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
