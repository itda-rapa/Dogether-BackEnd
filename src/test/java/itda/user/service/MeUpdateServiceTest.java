package itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import itda.user.dto.MeResponse;
import itda.user.dto.MeUpdateCommand;
import itda.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MeUpdateService")
class MeUpdateServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private NeighborhoodRepository neighborhoodRepository;

    @Nested
    @DisplayName("Describe: 내 정보 수정")
    class DescribeUpdate {

        @Test
        @DisplayName("It: 빈 수정 명령은 조회 전에 400으로 거절한다")
        void rejectsEmptyCommandBeforeLookup() {
            assertErrorCode(
                    () -> service().update(
                            USER_ID,
                            new MeUpdateCommand(false, null, false, null, false, null)
                    ),
                    ErrorCode.VALIDATION_FAILED
            );
            then(userRepository).shouldHaveNoInteractions();
            then(neighborhoodRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 존재하지 않는 User 수정은 USER_NOT_FOUND를 반환하고 flush하지 않는다")
        void rejectsMissingUser() {
            given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

            assertErrorCode(
                    () -> service().update(USER_ID, nickname("새이름")),
                    ErrorCode.USER_NOT_FOUND
            );
            then(userRepository).should().findById(USER_ID);
            then(userRepository).shouldHaveNoMoreInteractions();
            then(neighborhoodRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: nickname을 변경해도 publicTag는 보존하고 flush한다")
        void changesNicknameWithoutChangingPublicTag() {
            User user = user();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            MeResponse response = service().update(USER_ID, nickname("  새이름  "));

            assertThat(response.nickname()).isEqualTo("새이름");
            assertThat(response.publicTag()).isEqualTo("사용자#A7K2");
            then(userRepository).should().flush();
            then(neighborhoodRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 같은 nickname은 no-op이며 flush하지 않는다")
        void doesNotFlushForNicknameNoOp() {
            User user = user();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            MeResponse response = service().update(USER_ID, nickname("사용자"));

            assertThat(response.nickname()).isEqualTo("사용자");
            then(userRepository).should().findById(USER_ID);
            then(userRepository).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("It: 활성 동네 코드로 변경하고 trim한 값을 저장한다")
        void changesToActiveNeighborhood() {
            User user = user();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(neighborhoodRepository.existsByCodeAndActiveTrue("4113510900"))
                    .willReturn(true);

            MeResponse response = service().update(
                    USER_ID, neighborhood("  4113510900  ")
            );

            assertThat(response.neighborhoodCode()).isEqualTo("4113510900");
            then(userRepository).should().flush();
        }

        @Test
        @DisplayName("It: 알 수 없거나 비활성인 동네 코드는 422이고 User를 변경하지 않는다")
        void rejectsUnavailableNeighborhood() {
            User user = user();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(neighborhoodRepository.existsByCodeAndActiveTrue("9999999999"))
                    .willReturn(false);

            assertErrorCode(
                    () -> service().update(USER_ID, neighborhood("9999999999")),
                    ErrorCode.NEIGHBORHOOD_NOT_FOUND
            );
            assertThat(ErrorCode.NEIGHBORHOOD_NOT_FOUND.getStatus().value())
                    .isEqualTo(422);
            assertThat(user.getNeighborhoodCode()).isEqualTo("4113111500");
            then(userRepository).should().findById(USER_ID);
            then(userRepository).shouldHaveNoMoreInteractions();
        }

        @ParameterizedTest
        @MethodSource("itda.user.service.MeUpdateServiceTest#invalidNeighborhoodCodes")
        @DisplayName("It: 공백 또는 20자 초과 동네 코드는 조회 전에 400으로 거절한다")
        void rejectsInvalidNeighborhoodBeforeLookup(String neighborhoodCode) {
            assertErrorCode(
                    () -> service().update(USER_ID, neighborhood(neighborhoodCode)),
                    ErrorCode.VALIDATION_FAILED
            );
            then(userRepository).shouldHaveNoInteractions();
            then(neighborhoodRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: explicit null 동네는 조회 전에 400으로 거절한다")
        void rejectsNullNeighborhoodBeforeLookup() {
            assertErrorCode(
                    () -> service().update(USER_ID, neighborhood(null)),
                    ErrorCode.VALIDATION_FAILED
            );
            then(userRepository).shouldHaveNoInteractions();
            then(neighborhoodRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: weightKg를 설정, 변경, null 초기화한다")
        void setsChangesAndClearsWeight() {
            User user = user();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            assertThat(service().update(USER_ID, weight("72.50")).weightKg())
                    .isEqualByComparingTo("72.50");
            assertThat(service().update(USER_ID, weight("73.25")).weightKg())
                    .isEqualByComparingTo("73.25");
            assertThat(service().update(USER_ID, weight(null)).weightKg()).isNull();
            then(userRepository).should(org.mockito.Mockito.times(3)).flush();
        }

        @Test
        @DisplayName("It: 72.5와 72.50은 동일한 체중으로 보고 flush하지 않는다")
        void treatsEquivalentDecimalScaleAsNoOp() {
            User user = user();
            user.changeWeightKg(new BigDecimal("72.5"));
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            MeResponse response = service().update(USER_ID, weight("72.50"));

            assertThat(response.weightKg()).isEqualByComparingTo("72.5");
            then(userRepository).should().findById(USER_ID);
            then(userRepository).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("It: 비활성 계정은 ACCOUNT_NOT_ACTIVE로 수정 또는 flush하지 않는다")
        void rejectsInactiveUser() {
            User user = user();
            ReflectionTestUtils.setField(user, "accountStatus", AccountStatus.SUSPENDED);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            assertErrorCode(
                    () -> service().update(USER_ID, nickname("새이름")),
                    ErrorCode.ACCOUNT_NOT_ACTIVE
            );
            assertThat(user.getNickname()).isEqualTo("사용자");
            then(userRepository).should().findById(USER_ID);
            then(userRepository).shouldHaveNoMoreInteractions();
            then(neighborhoodRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 실제 User 변경 뒤 flush의 optimistic lock 예외를 전파한다")
        void propagatesFlushOptimisticLockAfterMutation() {
            User user = user();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            ObjectOptimisticLockingFailureException conflict =
                    new ObjectOptimisticLockingFailureException(User.class, USER_ID);
            org.mockito.BDDMockito.willThrow(conflict).given(userRepository).flush();

            assertThatThrownBy(() -> service().update(USER_ID, nickname("새이름")))
                    .isSameAs(conflict);
            assertThat(user.getNickname()).isEqualTo("새이름");
            then(userRepository).should().flush();
        }
    }

    static Stream<String> invalidNeighborhoodCodes() {
        return Stream.of("   ", "123456789012345678901");
    }

    private MeUpdateService service() {
        return new MeUpdateService(userRepository, neighborhoodRepository);
    }

    private MeUpdateCommand nickname(String value) {
        return new MeUpdateCommand(true, value, false, null, false, null);
    }

    private MeUpdateCommand neighborhood(String value) {
        return new MeUpdateCommand(false, null, true, value, false, null);
    }

    private MeUpdateCommand weight(String value) {
        return new MeUpdateCommand(
                false, null, false, null, true,
                value == null ? null : new BigDecimal(value)
        );
    }

    private User user() {
        User user = User.register(
                "user@example.com", "encoded", "사용자", "사용자#A7K2", "4113111500"
        );
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private void assertErrorCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            ErrorCode expected
    ) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
