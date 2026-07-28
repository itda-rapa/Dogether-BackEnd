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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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

    private PetCreationService service;

    @BeforeEach
    void setUp() {
        service = new PetCreationService(
                petPublicTagGenerator,
                petCreationTransactionService
        );
    }

    @Nested
    @DisplayName("Describe: PublicTag 충돌을 재시도하며 Pet을 생성한다")
    class DescribeCreate {

        @Test
        @DisplayName("It: 첫 시도에 성공하면 Outcome을 그대로 반환한다")
        void itReturnsFirstSuccessfulOutcome() {
            PetCreateCommand command = command();
            PetCreationOutcome outcome = new PetCreationOutcome(2L, true);
            given(petPublicTagGenerator.generate(command.nickname()))
                    .willReturn(FIRST_TAG);
            given(petCreationTransactionService.createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            )).willReturn(outcome);

            assertThat(service.create(USER_ID, command)).isSameAs(outcome);
            then(petPublicTagGenerator).should().generate(command.nickname());
            then(petCreationTransactionService).should().createAttempt(
                    USER_ID,
                    command,
                    FIRST_TAG
            );
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

            assertThat(service.create(USER_ID, command)).isSameAs(outcome);
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
