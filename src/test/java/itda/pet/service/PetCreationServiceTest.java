package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
@DisplayName("PetCreationService")
class PetCreationServiceTest {

    private static final Long USER_ID = 1L;
    private static final String FIRST_TAG = "몽이#A7K2";
    private static final String SECOND_TAG = "몽이#B8M3";

    @Mock
    private PetPublicTagGenerator petPublicTagGenerator;

    @Mock
    private PetCreationTransactionService petCreationTransactionService;

    @Mock
    private ActivePetAssignmentTransactionService
            activePetAssignmentTransactionService;

    private PetCreationService service;

    @BeforeEach
    void setUp() {
        service = new PetCreationService(
                petPublicTagGenerator,
                petCreationTransactionService,
                activePetAssignmentTransactionService
        );
    }

    @Nested
    @DisplayName("Describe: PublicTag 충돌을 재시도하며 Pet을 생성한다")
    class DescribeCreate {

        @Test
        @DisplayName("It: 첫 Pet을 생성하고 자동 Active 지정 결과를 반환한다")
        void itReturnsAssignedResultForFirstPet() {
            PetCreateCommand command = command();
            PetCreationOutcome outcome = new PetCreationOutcome(2L, true);
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willReturn(outcome);
            given(activePetAssignmentTransactionService.assignIfAbsent(
                    USER_ID,
                    outcome.petId()
            )).willReturn(ActivePetAssignmentStatus.ASSIGNED);

            PetCreationResult result = service.create(USER_ID, command);

            assertThat(result.petId()).isEqualTo(outcome.petId());
            assertThat(result.activePetAssignmentStatus())
                    .isEqualTo(ActivePetAssignmentStatus.ASSIGNED);
            then(petPublicTagGenerator).should().generate(command.nickname());
            then(petCreationTransactionService).should().createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            );
            then(activePetAssignmentTransactionService).should()
                    .assignIfAbsent(USER_ID, outcome.petId());
        }

        @Test
        @DisplayName("It: 정확한 PublicTag Unique 충돌 뒤 새 후보로 다시 시도한다")
        void itRetriesAfterPublicTagUniqueConflict() {
            PetCreateCommand command = command();
            PetCreationOutcome outcome = new PetCreationOutcome(3L, false);
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG, SECOND_TAG);
            given(petCreationTransactionService.createAttempt(
                    eq(USER_ID),
                    eq(command),
                    anyString()
            )).willThrow(publicTagUniqueViolation()).willReturn(outcome);

            PetCreationResult result = service.create(USER_ID, command);

            assertThat(result.petId()).isEqualTo(outcome.petId());
            assertThat(result.activePetAssignmentStatus())
                    .isEqualTo(ActivePetAssignmentStatus.NOT_APPLICABLE);
            then(petPublicTagGenerator).should(times(2))
                    .generate(command.nickname());
            then(petCreationTransactionService).should().createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            );
            then(petCreationTransactionService).should().createAttempt(
                    USER_ID,
                    command,
                    SECOND_TAG
            );
            then(activePetAssignmentTransactionService)
                    .shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: PublicTag 충돌이 총 5회면 생성 실패 오류를 반환한다")
        void itFailsAfterFivePublicTagConflicts() {
            PetCreateCommand command = command();
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(
                            "몽이#A7K2",
                            "몽이#B8M3",
                            "몽이#C9N4",
                            "몽이#D2P5",
                            "몽이#E3Q6"
                    );
            given(petCreationTransactionService.createAttempt(
                    eq(USER_ID),
                    eq(command),
                    anyString()
            )).willAnswer(invocation -> {
                throw publicTagUniqueViolation();
            });

            assertErrorCode(
                    () -> service.create(USER_ID, command),
                    ErrorCode.PET_PUBLIC_TAG_GENERATION_FAILED
            );
            then(petPublicTagGenerator).should(times(5))
                    .generate(command.nickname());
            then(petCreationTransactionService).should(times(5))
                    .createAttempt(eq(USER_ID), eq(command), anyString());
            then(activePetAssignmentTransactionService)
                    .shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 다른 무결성 오류는 재시도 없이 원형을 전파한다")
        void itDoesNotRetryOtherIntegrityViolation() {
            PetCreateCommand command = command();
            DataIntegrityViolationException exception =
                    constraintViolation("ck_pets_nickname");
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willThrow(exception);

            assertThatThrownBy(() -> service.create(USER_ID, command))
                    .isSameAs(exception);
            then(petPublicTagGenerator).should().generate(command.nickname());
            then(petCreationTransactionService).should().createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            );
            then(activePetAssignmentTransactionService)
                    .shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: BusinessException은 재시도 없이 전파한다")
        void itDoesNotRetryBusinessException() {
            PetCreateCommand command = command();
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willThrow(new BusinessException(ErrorCode.PET_LIMIT_EXCEEDED));

            assertErrorCode(
                    () -> service.create(USER_ID, command),
                    ErrorCode.PET_LIMIT_EXCEEDED
            );
            then(petPublicTagGenerator).should().generate(command.nickname());
            then(petCreationTransactionService).should().createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            );
            then(activePetAssignmentTransactionService)
                    .shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 첫 Pet 후보가 아니면 자동 Active 지정을 호출하지 않는다")
        void itDoesNotAssignWhenNotFirstPetCandidate() {
            PetCreateCommand command = command();
            PetCreationOutcome outcome = new PetCreationOutcome(4L, false);
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willReturn(outcome);

            PetCreationResult result = service.create(USER_ID, command);

            assertThat(result.petId()).isEqualTo(outcome.petId());
            assertThat(result.activePetAssignmentStatus())
                    .isEqualTo(ActivePetAssignmentStatus.NOT_APPLICABLE);
            then(activePetAssignmentTransactionService)
                    .shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 기존 Active Pet이 있으면 자동 지정 결과를 그대로 반환한다")
        void itReturnsNotApplicableWhenActivePetAlreadyExists() {
            PetCreateCommand command = command();
            PetCreationOutcome outcome = new PetCreationOutcome(5L, true);
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willReturn(outcome);
            given(activePetAssignmentTransactionService.assignIfAbsent(
                    USER_ID,
                    outcome.petId()
            )).willReturn(ActivePetAssignmentStatus.NOT_APPLICABLE);

            PetCreationResult result = service.create(USER_ID, command);

            assertThat(result.petId()).isEqualTo(outcome.petId());
            assertThat(result.activePetAssignmentStatus())
                    .isEqualTo(ActivePetAssignmentStatus.NOT_APPLICABLE);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "40P01",
                "55P03"
        })
        @DisplayName("It: 허용된 PostgreSQL 잠금 오류만 RETRY_REQUIRED로 변환한다")
        void itConvertsRetryablePostgreSqlLockFailure(String sqlState) {
            PetCreateCommand command = command();
            PetCreationOutcome outcome = new PetCreationOutcome(6L, true);
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willReturn(outcome);
            given(activePetAssignmentTransactionService.assignIfAbsent(
                    USER_ID,
                    outcome.petId()
            )).willThrow(pessimisticLockFailure(sqlState));

            PetCreationResult result = service.create(USER_ID, command);

            assertThat(result.petId()).isEqualTo(outcome.petId());
            assertThat(result.activePetAssignmentStatus())
                    .isEqualTo(ActivePetAssignmentStatus.RETRY_REQUIRED);
        }

        @Test
        @DisplayName("It: SQLSTATE를 확인할 수 없는 비관적 잠금 오류는 숨기지 않는다")
        void itPropagatesLockFailureWithoutSqlState() {
            PetCreateCommand command = command();
            PetCreationOutcome outcome = new PetCreationOutcome(7L, true);
            PessimisticLockingFailureException exception =
                    new PessimisticLockingFailureException(
                            "unknown lock failure"
                    );
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willReturn(outcome);
            given(activePetAssignmentTransactionService.assignIfAbsent(
                    USER_ID,
                    outcome.petId()
            )).willThrow(exception);

            assertThatThrownBy(() -> service.create(USER_ID, command))
                    .isSameAs(exception);
        }

        @Test
        @DisplayName("It: 허용되지 않은 SQLSTATE의 비관적 잠금 오류는 숨기지 않는다")
        void itPropagatesLockFailureWithOtherSqlState() {
            PetCreateCommand command = command();
            PetCreationOutcome outcome = new PetCreationOutcome(8L, true);
            PessimisticLockingFailureException exception =
                    pessimisticLockFailure("23505");
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willReturn(outcome);
            given(activePetAssignmentTransactionService.assignIfAbsent(
                    USER_ID,
                    outcome.petId()
            )).willThrow(exception);

            assertThatThrownBy(() -> service.create(USER_ID, command))
                    .isSameAs(exception);
        }

        @Test
        @DisplayName("It: 자동 지정의 BusinessException을 RETRY_REQUIRED로 숨기지 않는다")
        void itPropagatesAssignmentBusinessException() {
            PetCreateCommand command = command();
            PetCreationOutcome outcome = new PetCreationOutcome(7L, true);
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willReturn(outcome);
            given(activePetAssignmentTransactionService.assignIfAbsent(
                    USER_ID,
                    outcome.petId()
            )).willThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));

            assertErrorCode(
                    () -> service.create(USER_ID, command),
                    ErrorCode.ACCOUNT_NOT_ACTIVE
            );
        }
    }

    private PetCreateCommand command() {
        return new PetCreateCommand(
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
    }

    private DataIntegrityViolationException publicTagUniqueViolation() {
        return constraintViolation("uk_pets_public_tag");
    }

    private PessimisticLockingFailureException pessimisticLockFailure(
            String sqlState
    ) {
        return new PessimisticLockingFailureException(
                "locked",
                new SQLException("postgres lock failure", sqlState)
        );
    }

    private DataIntegrityViolationException constraintViolation(
            String constraintName
    ) {
        return new DataIntegrityViolationException(
                "constraint violation",
                new ConstraintViolationException(
                        "constraint violation",
                        new SQLException("constraint violation", "23505"),
                        constraintName
                )
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
