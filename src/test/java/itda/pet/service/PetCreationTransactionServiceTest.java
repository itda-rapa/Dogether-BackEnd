package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.repository.PetRepository;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("PetCreationTransactionService")
class PetCreationTransactionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;
    private static final String PUBLIC_TAG = "몽이#A7K2";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PetRepository petRepository;

    private PetCreationTransactionService service;

    @BeforeEach
    void setUp() {
        service = new PetCreationTransactionService(
                userRepository,
                petRepository
        );
    }

    @Nested
    @DisplayName("Describe: 한 번의 Pet 생성 Transaction을 실행한다")
    class DescribeCreateAttempt {

        @Test
        @DisplayName("It: User가 없으면 USER_NOT_FOUND를 반환하고 count를 조회하지 않는다")
        void itRejectsMissingUser() {
            given(userRepository.findByIdForUpdate(USER_ID))
                    .willReturn(Optional.empty());

            assertErrorCode(
                    () -> service.createAttempt(USER_ID, command(), PUBLIC_TAG),
                    ErrorCode.USER_NOT_FOUND
            );
            then(petRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 비활성 User면 ACCOUNT_NOT_ACTIVE를 반환한다")
        void itRejectsInactiveUser() {
            given(userRepository.findByIdForUpdate(USER_ID))
                    .willReturn(Optional.of(user(AccountStatus.SUSPENDED)));

            assertErrorCode(
                    () -> service.createAttempt(USER_ID, command(), PUBLIC_TAG),
                    ErrorCode.ACCOUNT_NOT_ACTIVE
            );
            then(petRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 미삭제 Pet이 5마리면 PET_LIMIT_EXCEEDED를 반환한다")
        void itRejectsWhenPetLimitIsReached() {
            givenActiveUserAndCount(5);

            assertErrorCode(
                    () -> service.createAttempt(USER_ID, command(), PUBLIC_TAG),
                    ErrorCode.PET_LIMIT_EXCEEDED
            );
            then(petRepository).should()
                    .countByOwner_IdAndDeletedAtIsNull(USER_ID);
            then(petRepository).should(never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("It: count가 0이면 User Lock 뒤 저장하고 첫 Pet 후보를 반환한다")
        void itCreatesFirstPetCandidate() {
            givenActiveUserAndCount(0);
            givenSavedPet();

            PetCreationOutcome outcome = service.createAttempt(
                    USER_ID,
                    command(),
                    PUBLIC_TAG
            );

            assertThat(outcome.petId()).isEqualTo(PET_ID);
            assertThat(outcome.firstPetCandidate()).isTrue();
            ArgumentCaptor<Pet> petCaptor = ArgumentCaptor.forClass(Pet.class);
            then(petRepository).should().saveAndFlush(petCaptor.capture());
            assertThat(petCaptor.getValue().getOwner().getId()).isEqualTo(USER_ID);
            assertThat(petCaptor.getValue().getPublicTag()).isEqualTo(PUBLIC_TAG);
            assertThat(petCaptor.getValue().getNickname()).isEqualTo("몽이");
            assertThat(petCaptor.getValue().getBreedName()).isEqualTo("골든리트리버");
            assertThat(petCaptor.getValue().getSex()).isEqualTo(PetSex.FEMALE);
            assertThat(petCaptor.getValue().getNeutered()).isTrue();
            assertThat(petCaptor.getValue().getBirthDate())
                    .isEqualTo(LocalDate.of(2020, 1, 2));
            assertThat(petCaptor.getValue().getWeightKg())
                    .isEqualByComparingTo("23.45");
            assertThat(petCaptor.getValue().getSizeCode()).isEqualTo(PetSizeCode.LARGE);
            assertThat(petCaptor.getValue().getBio()).isEqualTo("활발한 강아지");
            assertThat(petCaptor.getValue().getPersonalityTags())
                    .containsExactly("친화적", "활발함");
            assertThat(petCaptor.getValue().getCareNote())
                    .isEqualTo("닭고기 알레르기");
            assertThat(petCaptor.getValue().isActive()).isTrue();
            assertThat(petCaptor.getValue().getDeletedAt()).isNull();
            assertThat(petCaptor.getValue().getProfileAsset()).isNull();
            InOrder lockOrder = inOrder(userRepository, petRepository);
            lockOrder.verify(userRepository).findByIdForUpdate(USER_ID);
            lockOrder.verify(petRepository)
                    .countByOwner_IdAndDeletedAtIsNull(USER_ID);
        }

        @Test
        @DisplayName("It: count가 1 이상이면 첫 Pet 후보가 아니다")
        void itCreatesNonFirstPetCandidate() {
            givenActiveUserAndCount(1);
            givenSavedPet();

            PetCreationOutcome outcome = service.createAttempt(
                    USER_ID,
                    command(),
                    PUBLIC_TAG
            );

            assertThat(outcome.petId()).isEqualTo(PET_ID);
            assertThat(outcome.firstPetCandidate()).isFalse();
        }
    }

    private void givenActiveUserAndCount(long count) {
        given(userRepository.findByIdForUpdate(USER_ID))
                .willReturn(Optional.of(user(AccountStatus.ACTIVE)));
        given(petRepository.countByOwner_IdAndDeletedAtIsNull(USER_ID))
                .willReturn(count);
    }

    private void givenSavedPet() {
        given(petRepository.saveAndFlush(any(Pet.class)))
                .willAnswer(invocation -> {
                    Pet pet = invocation.getArgument(0);
                    ReflectionTestUtils.setField(pet, "id", PET_ID);
                    return pet;
                });
    }

    private User user(AccountStatus accountStatus) {
        User user = User.register(
                "user@example.com",
                "encoded",
                "사용자",
                "사용자#A7K2",
                "4113111500"
        );
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "accountStatus", accountStatus);
        return user;
    }

    private PetCreateCommand command() {
        return new PetCreateCommand(
                "몽이",
                "골든리트리버",
                PetSex.FEMALE,
                true,
                LocalDate.of(2020, 1, 2),
                new BigDecimal("23.45"),
                PetSizeCode.LARGE,
                "활발한 강아지",
                List.of("친화적", "활발함"),
                "닭고기 알레르기"
        );
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
