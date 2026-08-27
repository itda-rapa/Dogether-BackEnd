package itda.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import itda.auth.dto.LoginRequest;
import itda.auth.dto.SignupRequest;
import itda.auth.dto.AuthTokensResponse;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.service.TokenProvider;
import itda.email.EmailVerificationService;
import itda.email.EmailVerificationPurpose;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.service.OAuthExchangeResult;
import itda.oauth.service.OAuthExchangeService;
import itda.oauth.service.OAuthSignupCommand;
import itda.oauth.service.OAuthSignupService;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import itda.user.service.PublicTagGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private NeighborhoodRepository neighborhoodRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenProvider tokenProvider;
    @Mock
    private PublicTagGenerator publicTagGenerator;
    @Mock
    private UserRegistrationService userRegistrationService;
    @Mock
    private EmailVerificationService emailVerificationService;
    @Mock
    private OAuthExchangeService oauthExchangeService;
    @Mock
    private OAuthSignupService oauthSignupService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                neighborhoodRepository,
                passwordEncoder,
                tokenProvider,
                publicTagGenerator,
                userRegistrationService,
                emailVerificationService,
                oauthExchangeService,
                oauthSignupService
        );
    }

    @Test
    void exchangeOAuthAllowsNaverProvider() {
        OAuthExchangeResult<AuthTokensResponse> expected = new OAuthExchangeResult.SignupRequired<>(
                "signup-token", Instant.parse("2026-08-26T00:00:00Z"));
        given(oauthExchangeService.<AuthTokensResponse>exchange(any(), any())).willReturn(expected);

        OAuthExchangeResult<AuthTokensResponse> actual = authService.exchangeOAuth(OAuthProvider.NAVER, "login-code");

        assertThat(actual).isSameAs(expected);
        then(oauthExchangeService).should().exchange(
                org.mockito.ArgumentMatchers.eq(new itda.oauth.service.OAuthExchangeCommand(OAuthProvider.NAVER, "login-code")), any());
    }

    @Test
    void signupChecksNeighborhoodAndStoresEncodedPassword() {
        SignupRequest request = new SignupRequest(
                "USER@example.com",
                "long-password",
                "사용자",
                "1168010100",
                "verification-token"
        );
        given(userRepository.findByEmailIgnoreCase("user@example.com"))
                .willReturn(Optional.empty());
        given(neighborhoodRepository.existsByCodeAndActiveTrue("1168010100"))
                .willReturn(true);
        given(passwordEncoder.encode("long-password")).willReturn("encoded");
        given(publicTagGenerator.generate("사용자")).willReturn("사용자#A7K2");
        given(userRegistrationService.registerAndIssue(any(User.class)))
                .willReturn(new AuthTokensResponse(
                        "access",
                        "refresh",
                        Instant.parse("2026-07-24T00:30:00Z")
                ));

        authService.signup(request);

        verify(passwordEncoder).encode("long-password");
        verify(emailVerificationService).consume("verification-token", "user@example.com", EmailVerificationPurpose.SIGNUP);
        verify(userRegistrationService).registerAndIssue(any(User.class));
    }

    @Test
    void signupPropagatesWeightKgToPersistedUser() {
        SignupRequest request = new SignupRequest(
                "user@example.com", "long-password", "사용자", "1168010100",
                "verification-token", new BigDecimal("3.25"));
        given(userRepository.findByEmailIgnoreCase("user@example.com")).willReturn(Optional.empty());
        given(neighborhoodRepository.existsByCodeAndActiveTrue("1168010100")).willReturn(true);
        given(passwordEncoder.encode("long-password")).willReturn("encoded");
        given(publicTagGenerator.generate("사용자")).willReturn("사용자#A7K2");
        given(userRegistrationService.registerAndIssue(any(User.class))).willReturn(tokens());

        authService.signup(request);

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        then(userRegistrationService).should().registerAndIssue(savedUser.capture());
        assertThat(savedUser.getValue().getWeightKg()).isEqualByComparingTo("3.25");
    }

    @Test
    void signupOAuthPropagatesWeightKgToOAuthCompletionCommand() {
        given(oauthSignupService.<AuthTokensResponse>complete(any(), any())).willReturn(tokens());

        authService.signupOAuth("signup-token", "사용자", "1168010100", new BigDecimal("3.25"));

        ArgumentCaptor<OAuthSignupCommand> command = ArgumentCaptor.forClass(OAuthSignupCommand.class);
        then(oauthSignupService).should().complete(command.capture(), any());
        assertThat(command.getValue().weightKg()).isEqualByComparingTo("3.25");
    }

    @Test
    void loginDoesNotRevealWhetherEmailOrPasswordWasWrong() {
        given(userRepository.findByEmailIgnoreCase("missing@example.com"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("missing@example.com", "wrong-password")
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    void signupRetriesAfterPublicTagUniqueCollision() {
        SignupRequest request = new SignupRequest(
                "user@example.com",
                "long-password",
                "사용자",
                "4113111500",
                "verification-token"
        );
        given(userRepository.findByEmailIgnoreCase("user@example.com"))
                .willReturn(Optional.empty());
        given(neighborhoodRepository.existsByCodeAndActiveTrue("4113111500"))
                .willReturn(true);
        given(passwordEncoder.encode("long-password")).willReturn("encoded");
        given(publicTagGenerator.generate("사용자"))
                .willReturn("사용자#AAAA", "사용자#BBBB");
        given(userRegistrationService.registerAndIssue(any(User.class)))
                .willThrow(new DataIntegrityViolationException(
                        "duplicate key violates uk_users_public_tag"
                ))
                .willReturn(new AuthTokensResponse(
                        "access",
                        "refresh",
                        Instant.parse("2026-07-24T00:30:00Z")
                ));

        authService.signup(request);

        verify(userRegistrationService, times(2))
                .registerAndIssue(any(User.class));
        verify(publicTagGenerator, times(2)).generate("사용자");
    }

    private AuthTokensResponse tokens() {
        return new AuthTokensResponse("access", "refresh", Instant.parse("2026-07-24T00:30:00Z"));
    }

    @Nested
    @DisplayName("Describe: login")
    class DescribeLogin {

        @Nested
        @DisplayName("Context: password가 일치하지 않으면")
        class WithIncorrectPassword {

            @Test
            @DisplayName("It: LOGIN_FAILED로 거부한다")
            void itRejectsAsLoginFailed() {
                // given
                User user = mock(User.class);
                given(userRepository.findByEmailIgnoreCase("user@example.com"))
                        .willReturn(Optional.of(user));
                given(user.hasPasswordCredential()).willReturn(true);
                given(user.getPasswordHash()).willReturn("encoded-password");
                given(passwordEncoder.matches("wrong-password", "encoded-password"))
                        .willReturn(false);

                // when & then
                assertThatThrownBy(() -> authService.login(
                        new LoginRequest("user@example.com", "wrong-password")
                ))
                        .isInstanceOf(BusinessException.class)
                        .extracting(exception ->
                                ((BusinessException) exception).getErrorCode()
                        )
                        .isEqualTo(ErrorCode.LOGIN_FAILED);
                then(tokenProvider).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: User가 비활성이면")
        class WithInactiveUser {

            @Test
            @DisplayName("It: ACCOUNT_NOT_ACTIVE로 거부한다")
            void itRejectsAsAccountNotActive() {
                // given
                User user = mock(User.class);
                given(userRepository.findByEmailIgnoreCase("user@example.com"))
                        .willReturn(Optional.of(user));
                given(user.hasPasswordCredential()).willReturn(true);
                given(user.getPasswordHash()).willReturn("encoded-password");
                given(passwordEncoder.matches("correct-password", "encoded-password"))
                        .willReturn(true);
                given(user.isActive()).willReturn(false);

                // when & then
                assertThatThrownBy(() -> authService.login(
                        new LoginRequest("user@example.com", "correct-password")
                ))
                        .isInstanceOf(BusinessException.class)
                        .extracting(exception ->
                                ((BusinessException) exception).getErrorCode()
                        )
                        .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE);
                then(tokenProvider).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: OAuth 전용 계정이라 passwordHash가 없으면")
        class WithOAuthOnlyAccount {

            @Test
            @DisplayName("It: PasswordEncoder를 호출하지 않고 LOGIN_FAILED로 거부한다")
            void itRejectsWithoutMatchingNullPasswordHash() {
                User user = mock(User.class);
                given(userRepository.findByEmailIgnoreCase("oauth@example.com"))
                        .willReturn(Optional.of(user));
                given(user.hasPasswordCredential()).willReturn(false);

                assertThatThrownBy(() -> authService.login(
                        new LoginRequest("oauth@example.com", "password")
                ))
                        .isInstanceOf(BusinessException.class)
                        .extracting(exception -> ((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.LOGIN_FAILED);

                then(passwordEncoder).shouldHaveNoInteractions();
                then(tokenProvider).shouldHaveNoInteractions();
            }
        }
    }

    @Nested
    @DisplayName("Describe: signup")
    class DescribeSignup {

        @Nested
        @DisplayName("Context: email이 이미 존재하면")
        class WithDuplicatedEmail {

            @Test
            @DisplayName("It: verification token을 소비하지 않는다")
            void itRejectsBeforeConsumingVerificationToken() {
                SignupRequest request = new SignupRequest(
                        "user@example.com", "long-password", "사용자", "4113111500", "verification-token"
                );
                given(userRepository.findByEmailIgnoreCase("user@example.com"))
                        .willReturn(Optional.of(mock(User.class)));

                assertThatThrownBy(() -> authService.signup(request))
                        .isInstanceOf(BusinessException.class)
                        .extracting(exception -> ((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.USER_EMAIL_DUPLICATED);

                then(emailVerificationService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: 가입 가능한 동네가 아니면")
        class WithUnavailableNeighborhood {

            @Test
            @DisplayName("It: NEIGHBORHOOD_NOT_FOUND로 거부하고 가입 처리를 진행하지 않는다")
            void itRejectsWithoutRegisteringUser() {
                // given
                SignupRequest request = new SignupRequest(
                        "user@example.com",
                        "long-password",
                        "사용자",
                        "4113111500",
                        "verification-token"
                );
                given(userRepository.findByEmailIgnoreCase("user@example.com"))
                        .willReturn(Optional.empty());
                given(neighborhoodRepository.existsByCodeAndActiveTrue("4113111500"))
                        .willReturn(false);

                // when & then
                assertThatThrownBy(() -> authService.signup(request))
                        .isInstanceOf(BusinessException.class)
                        .extracting(exception ->
                                ((BusinessException) exception).getErrorCode()
                        )
                        .isEqualTo(ErrorCode.NEIGHBORHOOD_NOT_FOUND);
                then(neighborhoodRepository)
                        .should()
                        .existsByCodeAndActiveTrue("4113111500");
                then(passwordEncoder).shouldHaveNoInteractions();
                then(publicTagGenerator).shouldHaveNoInteractions();
                then(userRegistrationService).shouldHaveNoInteractions();
                then(emailVerificationService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: verification token이 유효하지 않으면")
        class WithInvalidVerificationToken {

            @Test
            @DisplayName("It: password와 가입 처리를 시작하지 않는다")
            void itRejectsBeforePasswordAndRegistration() {
                SignupRequest request = new SignupRequest(
                        "user@example.com", "long-password", "사용자", "4113111500", "verification-token"
                );
                given(userRepository.findByEmailIgnoreCase("user@example.com"))
                        .willReturn(Optional.empty());
                given(neighborhoodRepository.existsByCodeAndActiveTrue("4113111500"))
                        .willReturn(true);
                org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID))
                        .when(emailVerificationService).consume(
                                "verification-token", "user@example.com", EmailVerificationPurpose.SIGNUP
                        );

                assertThatThrownBy(() -> authService.signup(request))
                        .isInstanceOf(BusinessException.class)
                        .extracting(exception -> ((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);

                then(passwordEncoder).shouldHaveNoInteractions();
                then(publicTagGenerator).shouldHaveNoInteractions();
                then(userRegistrationService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: PublicTag 충돌 재시도를 모두 소진하면")
        class WithExhaustedPublicTagRetries {

            @Test
            @DisplayName("It: PUBLIC_TAG_GENERATION_FAILED로 거부한다")
            void itRejectsAsPublicTagGenerationFailed() {
                // given
                SignupRequest request = new SignupRequest(
                        "user@example.com",
                        "long-password",
                        "사용자",
                        "4113111500",
                        "verification-token"
                );
                given(userRepository.findByEmailIgnoreCase("user@example.com"))
                        .willReturn(Optional.empty());
                given(neighborhoodRepository.existsByCodeAndActiveTrue("4113111500"))
                        .willReturn(true);
                given(passwordEncoder.encode("long-password")).willReturn("encoded");
                given(publicTagGenerator.generate("사용자"))
                        .willReturn("사용자#AAAA");
                given(userRegistrationService.registerAndIssue(any(User.class)))
                        .willThrow(new DataIntegrityViolationException(
                                "duplicate key violates uk_users_public_tag"
                        ));

                // when & then
                assertThatThrownBy(() -> authService.signup(request))
                        .isInstanceOf(BusinessException.class)
                        .extracting(exception ->
                                ((BusinessException) exception).getErrorCode()
                        )
                        .isEqualTo(ErrorCode.PUBLIC_TAG_GENERATION_FAILED);
                then(userRegistrationService).should(times(5))
                        .registerAndIssue(any(User.class));
                then(emailVerificationService).should(times(1)).consume(
                        "verification-token", "user@example.com", EmailVerificationPurpose.SIGNUP);
            }
        }
    }
}
