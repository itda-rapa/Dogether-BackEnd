package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.chat.service.ChatAuthorizationCacheService;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ActivePetSelectionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;
    private static final Long PREVIOUS_PET_ID = 99L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PetRepository petRepository;

    @Mock
    private ChatAuthorizationCacheService chatAuthorizationCacheService;

    private ActivePetSelectionService service;

    @BeforeEach
    void setUp() {
        service = new ActivePetSelectionService(
                userRepository,
                petRepository,
                chatAuthorizationCacheService
        );
    }

    @Nested
    @DisplayName("Describe: Active Pet을 선택한다")
    class DescribeSelectActivePet {

        @Nested
        @DisplayName("Context: Pet ID가 null일 때")
        class ContextWithNullPetId {

            @Test
            @DisplayName("It: Repository 접근 전에 거절한다")
            void itRejectsBeforeRepositoryAccess() {
                assertThatThrownBy(() ->
                        service.selectActivePet(USER_ID, null)
                )
                        .isInstanceOf(NullPointerException.class)
                        .hasMessage("petId는 null일 수 없습니다.");

                then(userRepository).shouldHaveNoInteractions();
                then(petRepository).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: User가 존재하지 않을 때")
        class ContextWithoutUser {

            @Test
            @DisplayName("It: USER_NOT_FOUND를 반환하고 Pet을 조회하지 않는다")
            void itRejectsWithoutPetLookup() {
                given(userRepository.findByIdForUpdate(USER_ID))
                        .willReturn(Optional.empty());

                assertErrorCode(
                        () -> service.selectActivePet(USER_ID, PET_ID),
                        ErrorCode.USER_NOT_FOUND
                );
                then(petRepository).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: User가 비활성 상태일 때")
        class ContextWithInactiveUser {

            @Test
            @DisplayName("It: ACCOUNT_NOT_ACTIVE를 반환하고 기존 선택을 유지한다")
            void itRejectsAndKeepsPreviousSelection() {
                User user = user(USER_ID, AccountStatus.SUSPENDED);
                user.selectActivePet(PREVIOUS_PET_ID);
                given(userRepository.findByIdForUpdate(USER_ID))
                        .willReturn(Optional.of(user));

                assertErrorCode(
                        () -> service.selectActivePet(USER_ID, PET_ID),
                        ErrorCode.ACCOUNT_NOT_ACTIVE
                );
                assertThat(user.getActivePetId()).isEqualTo(PREVIOUS_PET_ID);
                then(petRepository).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: 대상 Pet이 존재하지 않을 때")
        class ContextWithoutPet {

            @Test
            @DisplayName("It: PET_NOT_FOUND를 반환하고 기존 선택을 유지한다")
            void itRejectsAndKeepsPreviousSelection() {
                User user = activeUserWithPreviousPet();
                given(userRepository.findByIdForUpdate(USER_ID))
                        .willReturn(Optional.of(user));
                given(petRepository.findByIdForUpdate(PET_ID))
                        .willReturn(Optional.empty());

                assertErrorCode(
                        () -> service.selectActivePet(USER_ID, PET_ID),
                        ErrorCode.PET_NOT_FOUND
                );
                assertThat(user.getActivePetId()).isEqualTo(PREVIOUS_PET_ID);
            }
        }

        @Nested
        @DisplayName("Context: 대상 Pet이 다른 User 소유일 때")
        class ContextWithOtherOwnersPet {

            @Test
            @DisplayName("It: PET_NOT_OWNED를 반환하고 기존 선택을 유지한다")
            void itRejectsAndKeepsPreviousSelection() {
                User user = activeUserWithPreviousPet();
                Pet pet = pet(PET_ID, user(3L, AccountStatus.ACTIVE));
                givenLockedUserAndPet(user, pet);

                assertErrorCode(
                        () -> service.selectActivePet(USER_ID, PET_ID),
                        ErrorCode.PET_NOT_OWNED
                );
                assertThat(user.getActivePetId()).isEqualTo(PREVIOUS_PET_ID);
            }
        }

        @Nested
        @DisplayName("Context: 대상 Pet이 SUSPENDED 상태일 때")
        class ContextWithSuspendedPet {

            @Test
            @DisplayName("It: PET_NOT_ACTIVE를 반환하고 기존 선택을 유지한다")
            void itRejectsAndKeepsPreviousSelection() {
                User user = activeUserWithPreviousPet();
                Pet pet = pet(PET_ID, user);
                setPetState(pet, PetStatus.SUSPENDED, null);
                givenLockedUserAndPet(user, pet);

                assertErrorCode(
                        () -> service.selectActivePet(USER_ID, PET_ID),
                        ErrorCode.PET_NOT_ACTIVE
                );
                assertThat(user.getActivePetId()).isEqualTo(PREVIOUS_PET_ID);
            }
        }

        @Nested
        @DisplayName("Context: 대상 Pet이 DELETED 상태일 때")
        class ContextWithDeletedPet {

            @Test
            @DisplayName("It: PET_NOT_ACTIVE를 반환하고 기존 선택을 유지한다")
            void itRejectsAndKeepsPreviousSelection() {
                User user = activeUserWithPreviousPet();
                Pet pet = pet(PET_ID, user);
                setPetState(
                        pet,
                        PetStatus.DELETED,
                        Instant.parse("2026-07-28T00:00:00Z")
                );
                givenLockedUserAndPet(user, pet);

                assertErrorCode(
                        () -> service.selectActivePet(USER_ID, PET_ID),
                        ErrorCode.PET_NOT_ACTIVE
                );
                assertThat(user.getActivePetId()).isEqualTo(PREVIOUS_PET_ID);
            }
        }

        @Nested
        @DisplayName("Context: DB 불변조건을 위반한 ACTIVE Pet에 deletedAt이 존재할 때")
        class ContextWithActiveDeletedPetFixture {

            @Test
            @DisplayName("It: PET_NOT_ACTIVE를 반환하고 기존 선택을 유지한다")
            void itRejectsAndKeepsPreviousSelection() {
                User user = activeUserWithPreviousPet();
                Pet pet = pet(PET_ID, user);
                setPetState(
                        pet,
                        PetStatus.ACTIVE,
                        Instant.parse("2026-07-28T00:00:00Z")
                );
                givenLockedUserAndPet(user, pet);

                assertErrorCode(
                        () -> service.selectActivePet(USER_ID, PET_ID),
                        ErrorCode.PET_NOT_ACTIVE
                );
                assertThat(user.getActivePetId()).isEqualTo(PREVIOUS_PET_ID);
            }
        }

        @Nested
        @DisplayName("Context: 본인 소유의 ACTIVE·미삭제 Pet일 때")
        class ContextWithOwnedActivePet {

            @Test
            @DisplayName("It: User를 먼저 잠그고 Pet을 잠근 뒤 선택한다")
            void itLocksInOrderAndSelectsPet() {
                User user = user(USER_ID, AccountStatus.ACTIVE);
                Pet pet = pet(PET_ID, user);
                givenLockedUserAndPet(user, pet);

                service.selectActivePet(USER_ID, PET_ID);

                assertThat(user.getActivePetId()).isEqualTo(PET_ID);
                InOrder lockOrder = inOrder(userRepository, petRepository);
                lockOrder.verify(userRepository).findByIdForUpdate(USER_ID);
                lockOrder.verify(petRepository).findByIdForUpdate(PET_ID);
            }
        }

        @Nested
        @DisplayName("Context: 이미 같은 Pet이 선택되어 있을 때")
        class ContextReselectingSamePet {

            @Test
            @DisplayName("It: Pet 유효성을 조회하고 같은 Active Pet ID를 유지한다")
            void itValidatesPetAndKeepsSameSelection() {
                User user = user(USER_ID, AccountStatus.ACTIVE);
                user.selectActivePet(PET_ID);
                Pet pet = pet(PET_ID, user);
                givenLockedUserAndPet(user, pet);

                assertThatCode(() ->
                        service.selectActivePet(USER_ID, PET_ID)
                ).doesNotThrowAnyException();

                assertThat(user.getActivePetId()).isEqualTo(PET_ID);
                then(petRepository).should().findByIdForUpdate(PET_ID);
            }
        }
    }

    private User activeUserWithPreviousPet() {
        User user = user(USER_ID, AccountStatus.ACTIVE);
        user.selectActivePet(PREVIOUS_PET_ID);
        return user;
    }

    private void givenLockedUserAndPet(User user, Pet pet) {
        given(userRepository.findByIdForUpdate(USER_ID))
                .willReturn(Optional.of(user));
        given(petRepository.findByIdForUpdate(PET_ID))
                .willReturn(Optional.of(pet));
    }

    private User user(Long id, AccountStatus status) {
        User user = User.register(
                "user%s@example.com".formatted(id),
                "encoded",
                "사용자",
                "사용자#A7K2",
                "4113111500"
        );
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "accountStatus", status);
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
