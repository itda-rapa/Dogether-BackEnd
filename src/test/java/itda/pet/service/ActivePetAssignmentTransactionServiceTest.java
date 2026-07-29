package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivePetAssignmentTransactionService")
class ActivePetAssignmentTransactionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;
    private static final Long PREVIOUS_PET_ID = 3L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PetRepository petRepository;

    private ActivePetAssignmentTransactionService service;

    @BeforeEach
    void setUp() {
        service = new ActivePetAssignmentTransactionService(
                userRepository,
                petRepository
        );
    }

    @Nested
    @DisplayName("Describe: 첫 Pet을 Active Pet으로 자동 지정한다")
    class DescribeAssignIfAbsent {

        @Test
        @DisplayName("It: User가 없으면 USER_NOT_FOUND를 반환하고 Pet을 조회하지 않는다")
        void itRejectsMissingUser() {
            given(userRepository.findByIdForUpdate(USER_ID))
                    .willReturn(Optional.empty());

            assertErrorCode(
                    () -> service.assignIfAbsent(USER_ID, PET_ID),
                    ErrorCode.USER_NOT_FOUND
            );
            then(petRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 비활성 User면 ACCOUNT_NOT_ACTIVE를 반환하고 Pet을 조회하지 않는다")
        void itRejectsInactiveUser() {
            given(userRepository.findByIdForUpdate(USER_ID))
                    .willReturn(Optional.of(user(USER_ID, AccountStatus.SUSPENDED)));

            assertErrorCode(
                    () -> service.assignIfAbsent(USER_ID, PET_ID),
                    ErrorCode.ACCOUNT_NOT_ACTIVE
            );
            then(petRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 이미 Active Pet이 있으면 기존 값을 유지하고 Pet을 조회하지 않는다")
        void itKeepsExistingActivePetWithoutPetLookup() {
            User user = user(USER_ID, AccountStatus.ACTIVE);
            user.selectActivePet(PREVIOUS_PET_ID);
            given(userRepository.findByIdForUpdate(USER_ID))
                    .willReturn(Optional.of(user));

            ActivePetAssignmentStatus status = service.assignIfAbsent(
                    USER_ID,
                    PET_ID
            );

            assertThat(status).isEqualTo(ActivePetAssignmentStatus.NOT_APPLICABLE);
            assertThat(user.getActivePetId()).isEqualTo(PREVIOUS_PET_ID);
            then(petRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: Pet이 없으면 PET_NOT_FOUND를 반환한다")
        void itRejectsMissingPet() {
            givenActiveUser();
            given(petRepository.findByIdForUpdate(PET_ID))
                    .willReturn(Optional.empty());

            assertErrorCode(
                    () -> service.assignIfAbsent(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_FOUND
            );
        }

        @Test
        @DisplayName("It: 다른 User 소유 Pet이면 PET_NOT_OWNED를 반환한다")
        void itRejectsPetOwnedByAnotherUser() {
            givenActiveUser();
            User anotherUser = user(9L, AccountStatus.ACTIVE);
            given(petRepository.findByIdForUpdate(PET_ID))
                    .willReturn(Optional.of(pet(PET_ID, anotherUser)));

            assertErrorCode(
                    () -> service.assignIfAbsent(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_OWNED
            );
        }

        @Test
        @DisplayName("It: SUSPENDED 또는 삭제된 Pet이면 PET_NOT_ACTIVE를 반환한다")
        void itRejectsUnavailablePet() {
            User user = givenActiveUser();
            Pet pet = pet(PET_ID, user);
            ReflectionTestUtils.setField(pet, "status", PetStatus.SUSPENDED);
            given(petRepository.findByIdForUpdate(PET_ID))
                    .willReturn(Optional.of(pet));

            assertErrorCode(
                    () -> service.assignIfAbsent(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_ACTIVE
            );

            Pet deletedPet = pet(PET_ID, user);
            ReflectionTestUtils.setField(
                    deletedPet,
                    "deletedAt",
                    Instant.now()
            );
            given(petRepository.findByIdForUpdate(PET_ID))
                    .willReturn(Optional.of(deletedPet));

            assertErrorCode(
                    () -> service.assignIfAbsent(USER_ID, PET_ID),
                    ErrorCode.PET_NOT_ACTIVE
            );
        }

        @Test
        @DisplayName("It: User를 먼저 잠그고 Pet을 잠근 뒤 Active Pet으로 지정한다")
        void itLocksInOrderAndAssignsActivePet() {
            User user = givenActiveUser();
            given(petRepository.findByIdForUpdate(PET_ID))
                    .willReturn(Optional.of(pet(PET_ID, user)));

            ActivePetAssignmentStatus status = service.assignIfAbsent(
                    USER_ID,
                    PET_ID
            );

            assertThat(status).isEqualTo(ActivePetAssignmentStatus.ASSIGNED);
            assertThat(user.getActivePetId()).isEqualTo(PET_ID);
            InOrder lockOrder = inOrder(userRepository, petRepository);
            lockOrder.verify(userRepository).findByIdForUpdate(USER_ID);
            lockOrder.verify(petRepository).findByIdForUpdate(PET_ID);
        }

        @Test
        @DisplayName("It: 잠금 예외를 상태값으로 변환하지 않고 원형 전파한다")
        void itPropagatesPessimisticLockFailure() {
            PessimisticLockingFailureException exception =
                    new PessimisticLockingFailureException("locked");
            given(userRepository.findByIdForUpdate(USER_ID))
                    .willThrow(exception);

            assertThatThrownBy(() -> service.assignIfAbsent(USER_ID, PET_ID))
                    .isSameAs(exception);
            then(petRepository).shouldHaveNoInteractions();
        }
    }

    private User givenActiveUser() {
        User user = user(USER_ID, AccountStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(USER_ID))
                .willReturn(Optional.of(user));
        return user;
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
