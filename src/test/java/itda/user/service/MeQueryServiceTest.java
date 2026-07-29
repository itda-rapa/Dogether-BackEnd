package itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.user.domain.AccountStatus;
import itda.user.domain.Role;
import itda.user.domain.User;
import itda.user.dto.AccessLevel;
import itda.user.dto.MeResponse;
import itda.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MeQueryService")
class MeQueryServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;

    @Mock
    private UserRepository userRepository;

    @Nested
    @DisplayName("Describe: 내 정보를 조회한다")
    class DescribeGetMe {

        @Nested
        @DisplayName("Context: Active Pet이 없는 활성 User가 존재할 때")
        class ContextWithActiveL1User {

            @Test
            @DisplayName("It: User 정보와 L1을 반환한다")
            void itReturnsUserWithL1() {
                User user = user(AccountStatus.ACTIVE);
                given(userRepository.findById(USER_ID))
                        .willReturn(Optional.of(user));

                MeResponse response = service().getMe(USER_ID);

                assertThat(response.userId()).isEqualTo(USER_ID);
                assertThat(response.email()).isEqualTo("user@example.com");
                assertThat(response.nickname()).isEqualTo("사용자");
                assertThat(response.publicTag()).isEqualTo("사용자#A7K2");
                assertThat(response.role()).isEqualTo(Role.USER);
                assertThat(response.accountStatus()).isEqualTo(AccountStatus.ACTIVE);
                assertThat(response.accessLevel()).isEqualTo(AccessLevel.L1);
                assertThat(response.neighborhoodCode()).isEqualTo("4113111500");
                assertThat(response.activePetId()).isNull();
            }
        }

        @Nested
        @DisplayName("Context: Active Pet이 있는 활성 User가 존재할 때")
        class ContextWithActiveL2User {

            @Test
            @DisplayName("It: Pet 유효성을 다시 조회하지 않고 L2를 반환한다")
            void itReturnsL2FromStoredActivePetId() {
                User user = user(AccountStatus.ACTIVE);
                user.selectActivePet(PET_ID);
                given(userRepository.findById(USER_ID))
                        .willReturn(Optional.of(user));

                MeResponse response = service().getMe(USER_ID);

                assertThat(response.accessLevel()).isEqualTo(AccessLevel.L2);
                assertThat(response.activePetId()).isEqualTo(PET_ID);
                then(userRepository).should().findById(USER_ID);
            }
        }

        @Nested
        @DisplayName("Context: SUSPENDED User가 존재할 때")
        class ContextWithSuspendedUser {

            @Test
            @DisplayName("It: 계정 상태를 그대로 포함하고 L1을 반환한다")
            void itReturnsSuspendedStatus() {
                User user = user(AccountStatus.SUSPENDED);
                given(userRepository.findById(USER_ID))
                        .willReturn(Optional.of(user));

                MeResponse response = service().getMe(USER_ID);

                assertThat(response.accountStatus()).isEqualTo(AccountStatus.SUSPENDED);
                assertThat(response.accessLevel()).isEqualTo(AccessLevel.L1);
            }
        }

        @Nested
        @DisplayName("Context: Active Pet이 있는 WITHDRAWN User가 존재할 때")
        class ContextWithWithdrawnL2User {

            @Test
            @DisplayName("It: 계정 상태와 activePetId를 그대로 포함하고 L2를 반환한다")
            void itReturnsWithdrawnStatusWithL2() {
                User user = user(AccountStatus.WITHDRAWN);
                user.selectActivePet(PET_ID);
                given(userRepository.findById(USER_ID))
                        .willReturn(Optional.of(user));

                MeResponse response = service().getMe(USER_ID);

                assertThat(response.accountStatus()).isEqualTo(AccountStatus.WITHDRAWN);
                assertThat(response.accessLevel()).isEqualTo(AccessLevel.L2);
                assertThat(response.activePetId()).isEqualTo(PET_ID);
            }
        }

        @Nested
        @DisplayName("Context: User가 존재하지 않을 때")
        class ContextWithoutUser {

            @Test
            @DisplayName("It: USER_NOT_FOUND를 반환한다")
            void itReturnsUserNotFound() {
                given(userRepository.findById(USER_ID))
                        .willReturn(Optional.empty());

                assertThatThrownBy(() -> service().getMe(USER_ID))
                        .isInstanceOf(BusinessException.class)
                        .extracting(exception ->
                                ((BusinessException) exception).getErrorCode()
                        )
                        .isEqualTo(ErrorCode.USER_NOT_FOUND);
            }
        }
    }

    private MeQueryService service() {
        return new MeQueryService(userRepository);
    }

    private User user(AccountStatus status) {
        User user = User.register(
                "user@example.com",
                "encoded",
                "사용자",
                "사용자#A7K2",
                "4113111500"
        );
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "accountStatus", status);
        return user;
    }
}
