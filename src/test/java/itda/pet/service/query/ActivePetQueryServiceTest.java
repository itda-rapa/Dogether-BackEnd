package itda.pet.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
import itda.petverification.PetVerificationBadgeService;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
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
class ActivePetQueryServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PetRepository petRepository;

    @Mock
    private PetVerificationBadgeService badgeService;

    private ActivePetQueryService service;

    @BeforeEach
    void setUp() {
        service = new ActivePetQueryService(
                userRepository,
                petRepository,
                new ActivePetValidator(),
                badgeService
        );
    }

    @Nested
    @DisplayName("Describe: 행위 가능한 Active Pet을 조회한다")
    class DescribeRequireActivePet {

        @Nested
        @DisplayName("Context: User가 존재하지 않을 때")
        class ContextWithoutUser {

            @Test
            @DisplayName("It: ACTIVE_PET_REQUIRED를 반환한다")
            void itRequiresActivePet() {
                given(userRepository.findById(USER_ID))
                        .willReturn(Optional.empty());

                assertActivePetRequired();
                then(petRepository).shouldHaveNoInteractions();
                then(badgeService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: User가 비활성 상태일 때")
        class ContextWithInactiveUser {

            @Test
            @DisplayName("It: ACTIVE_PET_REQUIRED를 반환한다")
            void itRequiresActivePet() {
                User user = user(USER_ID, AccountStatus.SUSPENDED, PET_ID);
                given(userRepository.findById(USER_ID))
                        .willReturn(Optional.of(user));

                assertActivePetRequired();
                then(petRepository).shouldHaveNoInteractions();
                then(badgeService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: activePetId가 없을 때")
        class ContextWithoutActivePetId {

            @Test
            @DisplayName("It: ACTIVE_PET_REQUIRED를 반환하고 Pet을 조회하지 않는다")
            void itRequiresActivePetWithoutPetLookup() {
                User user = user(USER_ID, AccountStatus.ACTIVE, null);
                given(userRepository.findById(USER_ID))
                        .willReturn(Optional.of(user));

                assertActivePetRequired();
                then(petRepository).shouldHaveNoInteractions();
                then(badgeService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: activePetId가 가리키는 Pet이 없을 때")
        class ContextWithoutPet {

            @Test
            @DisplayName("It: ACTIVE_PET_REQUIRED를 반환한다")
            void itRequiresActivePet() {
                User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
                given(userRepository.findById(USER_ID))
                        .willReturn(Optional.of(user));
                given(petRepository.findById(PET_ID))
                        .willReturn(Optional.empty());

                assertActivePetRequired();
                then(badgeService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: Active Pet이 다른 User 소유일 때")
        class ContextWithOtherOwnersPet {

            @Test
            @DisplayName("It: ACTIVE_PET_REQUIRED를 반환한다")
            void itRequiresActivePet() {
                User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
                Pet pet = pet(
                        PET_ID,
                        user(3L, AccountStatus.ACTIVE, null)
                );
                givenUserAndPet(user, pet);

                assertActivePetRequired();
                then(badgeService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: Active Pet이 SUSPENDED 상태일 때")
        class ContextWithSuspendedPet {

            @Test
            @DisplayName("It: ACTIVE_PET_REQUIRED를 반환한다")
            void itRequiresActivePet() {
                User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
                Pet pet = pet(PET_ID, user);
                setPetState(pet, PetStatus.SUSPENDED, null);
                givenUserAndPet(user, pet);

                assertActivePetRequired();
                then(badgeService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: Active Pet이 DELETED 상태일 때")
        class ContextWithDeletedPet {

            @Test
            @DisplayName("It: ACTIVE_PET_REQUIRED를 반환한다")
            void itRequiresActivePet() {
                User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
                Pet pet = pet(PET_ID, user);
                setPetState(
                        pet,
                        PetStatus.DELETED,
                        Instant.parse("2026-07-28T00:00:00Z")
                );
                givenUserAndPet(user, pet);

                assertActivePetRequired();
                then(badgeService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: DB 불변조건을 위반한 ACTIVE Pet에 deletedAt이 존재할 때")
        class ContextWithActiveDeletedPetFixture {

            @Test
            @DisplayName("It: ACTIVE_PET_REQUIRED를 반환한다")
            void itRequiresActivePet() {
                User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
                Pet pet = pet(PET_ID, user);
                setPetState(
                        pet,
                        PetStatus.ACTIVE,
                        Instant.parse("2026-07-28T00:00:00Z")
                );
                givenUserAndPet(user, pet);

                assertActivePetRequired();
                then(badgeService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: 모든 행위 가능 조건을 만족할 때")
        class ContextWithUsableActivePet {

            @Test
            @DisplayName("It: 잠금 없이 ActivePetContext를 반환한다")
            void itReturnsActivePetContextWithoutLocks() {
                User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
                Pet pet = pet(PET_ID, user);
                givenUserAndPet(user, pet);

                ActivePetContext result = service.requireActivePet(USER_ID);

                assertThat(result.petId()).isEqualTo(PET_ID);
                assertThat(result.ownerUserId()).isEqualTo(USER_ID);
                assertThat(result.publicTag()).isEqualTo("몽이#B8M3");
                assertThat(result.nickname()).isEqualTo("몽이");
                assertThat(result.profileUrl()).isNull();
                assertThat(result.verified()).isFalse();
                then(badgeService).should().verifiedAt(PET_ID);
                then(badgeService).shouldHaveNoMoreInteractions();
                then(userRepository).should(never())
                        .findByIdForUpdate(anyLong());
                then(petRepository).should(never())
                        .findByIdForUpdate(anyLong());
            }
        }
    }

    @Nested
    @DisplayName("Describe: Optional Active Pet을 조회한다")
    class DescribeFindActivePet {

        @Test
        @DisplayName("It: User가 없으면 empty를 반환한다")
        void itReturnsEmptyWithoutUser() {
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.empty());

            assertThat(service.findActivePet(USER_ID)).isEmpty();
            then(petRepository).shouldHaveNoInteractions();
            then(badgeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 비활성 User면 empty이고 Pet을 조회하지 않는다")
        void itReturnsEmptyForInactiveUser() {
            User user = user(USER_ID, AccountStatus.SUSPENDED, PET_ID);
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.of(user));

            assertThat(service.findActivePet(USER_ID)).isEmpty();
            then(petRepository).shouldHaveNoInteractions();
            then(badgeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: activePetId가 없으면 empty이고 Pet을 조회하지 않는다")
        void itReturnsEmptyWithoutActivePetId() {
            User user = user(USER_ID, AccountStatus.ACTIVE, null);
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.of(user));

            assertThat(service.findActivePet(USER_ID)).isEmpty();
            then(petRepository).shouldHaveNoInteractions();
            then(badgeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: activePetId의 Pet이 없으면 empty를 반환한다")
        void itReturnsEmptyWithoutPet() {
            User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.of(user));
            given(petRepository.findById(PET_ID))
                    .willReturn(Optional.empty());

            assertThat(service.findActivePet(USER_ID)).isEmpty();
            then(badgeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: Active Pet이 다른 User 소유이면 empty를 반환한다")
        void itReturnsEmptyForOtherOwnersPet() {
            User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
            Pet pet = pet(
                    PET_ID,
                    user(3L, AccountStatus.ACTIVE, null)
            );
            givenUserAndPet(user, pet);

            assertThat(service.findActivePet(USER_ID)).isEmpty();
            then(petRepository).should().findById(PET_ID);
            then(badgeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: Active Pet이 SUSPENDED 상태이면 empty를 반환한다")
        void itReturnsEmptyForSuspendedPet() {
            User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
            Pet pet = pet(PET_ID, user);
            setPetState(pet, PetStatus.SUSPENDED, null);
            givenUserAndPet(user, pet);

            assertThat(service.findActivePet(USER_ID)).isEmpty();
            then(petRepository).should().findById(PET_ID);
            then(badgeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: Active Pet이 DELETED 상태이고 deletedAt이 존재하면 empty를 반환한다")
        void itReturnsEmptyForDeletedPet() {
            User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
            Pet pet = pet(PET_ID, user);
            setPetState(
                    pet,
                    PetStatus.DELETED,
                    Instant.parse("2026-07-28T00:00:00Z")
            );
            givenUserAndPet(user, pet);

            assertThat(service.findActivePet(USER_ID)).isEmpty();
            then(petRepository).should().findById(PET_ID);
            then(badgeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: ACTIVE 상태여도 deletedAt이 존재하면 empty를 반환한다")
        void itReturnsEmptyForActivePetWithDeletedAt() {
            User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
            Pet pet = pet(PET_ID, user);
            setPetState(
                    pet,
                    PetStatus.ACTIVE,
                    Instant.parse("2026-07-28T00:00:00Z")
            );
            givenUserAndPet(user, pet);

            assertThat(service.findActivePet(USER_ID)).isEmpty();
            then(petRepository).should().findById(PET_ID);
            then(badgeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 유효한 Active Pet이면 Context를 반환한다")
        void itReturnsUsableActivePet() {
            User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
            Pet pet = pet(PET_ID, user);
            givenUserAndPet(user, pet);

            ActivePetContext result = service.findActivePet(USER_ID)
                    .orElseThrow();

            assertThat(result.petId()).isEqualTo(PET_ID);
            assertThat(result.ownerUserId()).isEqualTo(USER_ID);
            then(badgeService).should().verifiedAt(PET_ID);
            then(badgeService).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("It: Verification row가 있으면 verified=true를 반환한다")
        void itReturnsVerifiedBadgeFromTheVerificationRow() {
            User user = user(USER_ID, AccountStatus.ACTIVE, PET_ID);
            Pet pet = pet(PET_ID, user);
            givenUserAndPet(user, pet);
            given(badgeService.verifiedAt(PET_ID)).willReturn(Instant.parse("2026-08-12T00:00:00Z"));

            ActivePetContext result = service.findActivePet(USER_ID).orElseThrow();

            assertThat(result.verified()).isTrue();
            then(badgeService).should().verifiedAt(PET_ID);
            then(badgeService).shouldHaveNoMoreInteractions();
        }
    }

    private void assertActivePetRequired() {
        assertThatThrownBy(() -> service.requireActivePet(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);
    }

    private void givenUserAndPet(User user, Pet pet) {
        given(userRepository.findById(USER_ID))
                .willReturn(Optional.of(user));
        given(petRepository.findById(PET_ID))
                .willReturn(Optional.of(pet));
    }

    private User user(
            Long id,
            AccountStatus status,
            Long activePetId
    ) {
        User user = User.register(
                "user%s@example.com".formatted(id),
                "encoded",
                "사용자",
                "사용자#A7K2",
                "4113111500"
        );
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "accountStatus", status);
        if (activePetId != null) {
            user.selectActivePet(activePetId);
        }
        return user;
    }

    private Pet pet(Long id, User owner) {
        Pet pet = Pet.register(
                owner,
                "몽이#B8M3",
                "몽이",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        ReflectionTestUtils.setField(pet, "id", id);
        return pet;
    }

    private void setPetState(
            Pet pet,
            PetStatus status,
            Instant deletedAt
    ) {
        ReflectionTestUtils.setField(pet, "status", status);
        ReflectionTestUtils.setField(pet, "deletedAt", deletedAt);
    }
}
