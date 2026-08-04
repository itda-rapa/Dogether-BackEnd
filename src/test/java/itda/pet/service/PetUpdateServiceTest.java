package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.pet.domain.Pet;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import itda.pet.service.PetUpdateCommand.PatchValue;
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
@DisplayName("PetUpdateService")
class PetUpdateServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;

    @Mock
    private PetRepository petRepository;

    private PetUpdateService service;

    @BeforeEach
    void setUp() {
        service = new PetUpdateService(petRepository);
    }

    @Nested
    @DisplayName("Describe: Command 구조를 먼저 검증한다")
    class DescribeCommandValidation {

        @Test
        @DisplayName("It: null Command는 조회 전에 VALIDATION_FAILED다")
        void rejectsNullCommandBeforeRepositoryAccess() {
            assertErrorCode(
                    () -> service.update(USER_ID, PET_ID, null),
                    ErrorCode.VALIDATION_FAILED
            );

            then(petRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 모든 필드가 missing인 Command는 조회 전에 VALIDATION_FAILED다")
        void rejectsAllMissingCommandBeforeRepositoryAccess() {
            assertErrorCode(
                    () -> service.update(USER_ID, PET_ID, allMissing()),
                    ErrorCode.VALIDATION_FAILED
            );

            then(petRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: nickname의 명시적 null은 조회 전에 VALIDATION_FAILED다")
        void rejectsNullNicknameBeforeRepositoryAccess() {
            assertErrorCode(
                    () -> service.update(USER_ID, PET_ID, nickname(null)),
                    ErrorCode.VALIDATION_FAILED
            );

            then(petRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: personalityTags의 명시적 null은 조회 전에 VALIDATION_FAILED다")
        void rejectsNullPersonalityTagsBeforeRepositoryAccess() {
            PetUpdateCommand command = new PetUpdateCommand(
                    PatchValue.missing(),
                    PatchValue.missing(),
                    PatchValue.missing(),
                    PatchValue.missing(),
                    PatchValue.missing(),
                    PatchValue.missing(),
                    PatchValue.missing(),
                    PatchValue.missing(),
                    PatchValue.present(null),
                    PatchValue.missing()
            );

            assertErrorCode(
                    () -> service.update(USER_ID, PET_ID, command),
                    ErrorCode.VALIDATION_FAILED
            );

            then(petRepository).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("Describe: 단일 조회 후 권한을 검증한다")
    class DescribeOwnershipValidation {

        @Test
        @DisplayName("It: Pet이 없으면 PET_NOT_FOUND다")
        void rejectsMissingPet() {
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.empty());

            assertErrorCode(
                    () -> service.update(USER_ID, PET_ID, nickname("초코")),
                    ErrorCode.PET_NOT_FOUND
            );

            then(petRepository).should().findByIdWithOwner(PET_ID);
            then(petRepository).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("It: status가 DELETED인 타인 Pet도 먼저 PET_NOT_FOUND다")
        void rejectsDeletedStatusBeforeOwnership() {
            Pet pet = pet(user(9L));
            ReflectionTestUtils.setField(pet, "status", PetStatus.DELETED);
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.of(pet));

            assertErrorCode(
                    () -> service.update(USER_ID, PET_ID, nickname("초코")),
                    ErrorCode.PET_NOT_FOUND
            );
        }

        @Test
        @DisplayName("It: deletedAt이 있는 타인 Pet도 먼저 PET_NOT_FOUND다")
        void rejectsDeletedAtBeforeOwnership() {
            Pet pet = pet(user(9L));
            ReflectionTestUtils.setField(pet, "deletedAt", Instant.now());
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.of(pet));

            assertErrorCode(
                    () -> service.update(USER_ID, PET_ID, nickname("초코")),
                    ErrorCode.PET_NOT_FOUND
            );
        }

        @Test
        @DisplayName("It: 미삭제 타인 Pet은 PET_NOT_OWNED다")
        void rejectsUnownedPet() {
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.of(pet(user(9L))));

            assertErrorCode(
                    () -> service.update(USER_ID, PET_ID, nickname("초코")),
                    ErrorCode.PET_NOT_OWNED
            );
        }
    }

    @Nested
    @DisplayName("Describe: 수정하고 응답을 조립한다")
    class DescribeUpdate {

        @Test
        @DisplayName("It: ACTIVE인 현재 Active Pet을 수정한다")
        void updatesActivePet() {
            User owner = user(USER_ID);
            owner.selectActivePet(PET_ID);
            Pet pet = pet(owner);
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.of(pet));

            PetResponse response = service.update(
                    USER_ID,
                    PET_ID,
                    nickname("초코")
            );

            assertThat(response.nickname()).isEqualTo("초코");
            assertThat(response.active()).isTrue();
            assertThat(response.verified()).isFalse();
            assertThat(response.verifiedAt()).isNull();
            then(petRepository).should().findByIdWithOwner(PET_ID);
            then(petRepository).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("It: SUSPENDED이면서 비 Active인 본인 Pet도 수정한다")
        void updatesSuspendedNonActivePet() {
            User owner = user(USER_ID);
            owner.selectActivePet(99L);
            Pet pet = pet(owner);
            ReflectionTestUtils.setField(pet, "status", PetStatus.SUSPENDED);
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.of(pet));

            PetResponse response = service.update(
                    USER_ID,
                    PET_ID,
                    nickname("초코")
            );

            assertThat(response.status()).isEqualTo(PetStatus.SUSPENDED);
            assertThat(response.active()).isFalse();
            assertThat(response.nickname()).isEqualTo("초코");
        }

        @Test
        @DisplayName("It: missing은 유지하고 nullable null과 여러 값을 함께 반영한다")
        void appliesMultiplePresentFields() {
            Pet pet = pet(user(USER_ID));
            String publicTag = pet.getPublicTag();
            User owner = pet.getOwner();
            PetStatus status = pet.getStatus();
            Instant deletedAt = pet.getDeletedAt();
            Media profileAsset = pet.getProfileAsset();
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.of(pet));
            PetUpdateCommand command = new PetUpdateCommand(
                    PatchValue.missing(),
                    PatchValue.present(null),
                    PatchValue.present(PetSex.MALE),
                    PatchValue.present(null),
                    PatchValue.present(LocalDate.of(2022, 2, 2)),
                    PatchValue.present(new BigDecimal("5.10")),
                    PatchValue.present(PetSizeCode.MEDIUM),
                    PatchValue.present("새 소개"),
                    PatchValue.present(List.of()),
                    PatchValue.present(null)
            );

            PetResponse response = service.update(USER_ID, PET_ID, command);

            assertThat(response.nickname()).isEqualTo("몽이");
            assertThat(response.breedName()).isNull();
            assertThat(response.sex()).isEqualTo(PetSex.MALE);
            assertThat(response.neutered()).isNull();
            assertThat(response.birthDate())
                    .isEqualTo(LocalDate.of(2022, 2, 2));
            assertThat(response.weightKg()).isEqualByComparingTo("5.10");
            assertThat(response.sizeCode()).isEqualTo(PetSizeCode.MEDIUM);
            assertThat(response.bio()).isEqualTo("새 소개");
            assertThat(response.personalityTags()).isEmpty();
            assertThat(response.careNote()).isNull();
            assertThat(pet.getPublicTag()).isEqualTo(publicTag);
            assertThat(pet.getOwner()).isSameAs(owner);
            assertThat(pet.getStatus()).isEqualTo(status);
            assertThat(pet.getDeletedAt()).isEqualTo(deletedAt);
            assertThat(pet.getProfileAsset()).isSameAs(profileAsset);
        }

        @Test
        @DisplayName("It: null 이후 값과 값 이후 null을 모두 적용한다")
        void supportsNullAndValueTransitions() {
            Pet pet = pet(user(USER_ID));
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.of(pet));

            service.update(
                    USER_ID,
                    PET_ID,
                    commandWithBio(PatchValue.present(null))
            );
            assertThat(pet.getBio()).isNull();

            service.update(
                    USER_ID,
                    PET_ID,
                    commandWithBio(PatchValue.present("다시 설정"))
            );
            assertThat(pet.getBio()).isEqualTo("다시 설정");

            service.update(
                    USER_ID,
                    PET_ID,
                    commandWithBio(PatchValue.present(null))
            );
            assertThat(pet.getBio()).isNull();
        }

        @Test
        @DisplayName("It: nullable bio의 명시적 null은 정상적으로 초기화한다")
        void clearsBioWithExplicitNull() {
            Pet pet = pet(user(USER_ID));
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.of(pet));

            PetResponse response = service.update(
                    USER_ID,
                    PET_ID,
                    commandWithBio(PatchValue.present(null))
            );

            assertThat(response.bio()).isNull();
            then(petRepository).should().findByIdWithOwner(PET_ID);
            then(petRepository).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("It: 같은 값인 no-op도 현재 응답을 반환한다")
        void returnsCurrentResponseForNoOp() {
            Pet pet = pet(user(USER_ID));
            given(petRepository.findByIdWithOwner(PET_ID))
                    .willReturn(Optional.of(pet));

            PetResponse response = service.update(
                    USER_ID,
                    PET_ID,
                    nickname("몽이")
            );

            assertThat(response.nickname()).isEqualTo("몽이");
            then(petRepository).should().findByIdWithOwner(PET_ID);
            then(petRepository).shouldHaveNoMoreInteractions();
        }
    }

    private PetUpdateCommand nickname(String nickname) {
        return new PetUpdateCommand(
                PatchValue.present(nickname),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing()
        );
    }

    private PetUpdateCommand allMissing() {
        return new PetUpdateCommand(
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing()
        );
    }

    private PetUpdateCommand commandWithBio(PatchValue<String> bio) {
        return new PetUpdateCommand(
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                PatchValue.missing(),
                bio,
                PatchValue.missing(),
                PatchValue.missing()
        );
    }

    private Pet pet(User owner) {
        Pet pet = Pet.register(
                owner,
                "몽이#A7K2",
                "몽이",
                "말티즈",
                PetSex.FEMALE,
                true,
                LocalDate.of(2020, 1, 2),
                new BigDecimal("3.40"),
                PetSizeCode.SMALL,
                "소개",
                List.of("친화적"),
                "돌봄 메모"
        );
        ReflectionTestUtils.setField(pet, "id", PET_ID);
        return pet;
    }

    private User user(Long id) {
        User user = User.register(
                "owner" + id + "@example.com",
                "encoded",
                "보호자",
                "보호자#A7K2",
                "4113111500"
        );
        ReflectionTestUtils.setField(user, "id", id);
        return user;
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
