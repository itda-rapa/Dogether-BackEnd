package itda.auth.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.auth.dto.AuthTokensResponse;
import itda.auth.dto.LoginRequest;
import itda.auth.dto.SignupRequest;
import itda.auth.service.AuthService;
import itda.auth.service.PasswordResetService;
import itda.common.constants.ErrorCode;
import itda.common.filter.JwtFilter;
import itda.email.EmailVerificationService;
import itda.email.dto.EmailVerificationChallengeResponse;
import itda.email.dto.EmailVerificationConfirmedResponse;
import itda.common.security.CurrentUser;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.service.OAuthExchangeResult;
import itda.user.domain.Role;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AuthController")
class AuthControllerTest {

    private static final Instant ACCESS_TOKEN_EXPIRES_AT =
            Instant.parse("2026-07-26T01:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtFilter jwtFilter;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("Describe: POST /auth/signup")
    class DescribeSignup {

        @Nested
        @DisplayName("Context: 공백이 포함된 유효한 요청이면")
        class WithValidRequest {

            @Test
            @DisplayName("It: 정규화된 요청으로 201과 Token 응답을 반환한다")
            void itReturnsCreatedWithNormalizedRequest() throws Exception {
                // given
                given(authService.signup(any(SignupRequest.class))).willReturn(tokens());

                // when
                var result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": " user@example.com ",
                                  "password": "  long-password  ",
                                  "nickname": " 사용자 ",
                                  "neighborhoodCode": " 1168010100 ",
                                  "verificationToken": "verification-token-123"
                                }
                                """));

                // then
                result.andExpect(status().isCreated())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.accessToken").value("access-token"));
                then(authService).should().signup(argThat(request ->
                        request.email().equals("user@example.com")
                                && request.nickname().equals("사용자")
                                && request.neighborhoodCode().equals("1168010100")
                                && request.verificationToken().equals("verification-token-123")
                                && request.password().equals("  long-password  ")
                                && request.weightKg() == null
                ));
            }
        }

        @Test
        @DisplayName("It: 명시적 null 몸무게도 이전 요청과 호환되게 전달한다")
        void itAcceptsExplicitNullWeightKg() throws Exception {
            given(authService.signup(any(SignupRequest.class))).willReturn(tokens());

            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","password":"long-password",
                                     "nickname":"사용자","neighborhoodCode":"1168010100",
                                     "verificationToken":"verification-token-123","weightKg":null}
                                    """))
                    .andExpect(status().isCreated());

            then(authService).should().signup(argThat(request -> request.weightKg() == null));
        }

        @ParameterizedTest
        @ValueSource(strings = {"1", "3.25", "500.00"})
        @DisplayName("It: 범위 안의 숫자 몸무게를 그대로 Service에 전달한다")
        void itForwardsValidNumericWeightKg(String weightKg) throws Exception {
            given(authService.signup(any(SignupRequest.class))).willReturn(tokens());

            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","password":"long-password",
                                     "nickname":"사용자","neighborhoodCode":"1168010100",
                                     "verificationToken":"verification-token-123","weightKg":%s}
                                    """.formatted(weightKg)))
                    .andExpect(status().isCreated());

            then(authService).should().signup(argThat(request ->
                    request.weightKg().compareTo(new java.math.BigDecimal(weightKg)) == 0));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.99", "500.01", "3.256", "\"3.25\""})
        @DisplayName("It: 범위 밖·소수 셋째 자리·문자열 몸무게는 400으로 거부한다")
        void itRejectsInvalidWeightKg(String weightKg) throws Exception {
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","password":"long-password",
                                     "nickname":"사용자","neighborhoodCode":"1168010100",
                                     "verificationToken":"verification-token-123","weightKg":%s}
                                    """.formatted(weightKg)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            then(authService).shouldHaveNoInteractions();
        }

        @Nested
        @DisplayName("Context: trim 후 닉네임이 두 글자 미만이면")
        class WithShortNicknameAfterTrim {

            @Test
            @DisplayName("It: 400을 반환하고 Service를 호출하지 않는다")
            void itReturnsBadRequestWithoutCallingService() throws Exception {
                // when
                var result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com",
                                  "password": "long-password",
                                  "nickname": " a",
                                  "neighborhoodCode": "1168010100"
                                }
                                """));

                // then
                result.andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.success").value(false))
                        .andExpect(jsonPath("$.error.code")
                                .value(ErrorCode.VALIDATION_FAILED.name()));
                then(authService).shouldHaveNoInteractions();
            }
        }
    }

    @Nested
    @DisplayName("Describe: email verification endpoints")
    class DescribeEmailVerification {

        @Test
        @DisplayName("POST /auth/email-verifications returns 202 with a challenge id")
        void requestEmailVerificationReturnsAccepted() throws Exception {
            given(emailVerificationService.request(any())).willReturn(
                    new EmailVerificationChallengeResponse("challenge-id", ACCESS_TOKEN_EXPIRES_AT, 60)
            );

            mockMvc.perform(post("/auth/email-verifications")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","purpose":"SIGNUP"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.challengeId").value("challenge-id"));
        }

        @Test
        @DisplayName("POST /auth/email-verifications/confirm returns 200 with a verification token")
        void confirmEmailVerificationReturnsToken() throws Exception {
            given(emailVerificationService.confirm(any())).willReturn(
                    new EmailVerificationConfirmedResponse("verification-token", ACCESS_TOKEN_EXPIRES_AT)
            );

            mockMvc.perform(post("/auth/email-verifications/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"challengeId":"challenge-id","code":"123456"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.verificationToken").value("verification-token"));
        }
    }

    @Nested
    @DisplayName("Describe: POST /auth/password-reset")
    class DescribePasswordReset {

        @Test
        @DisplayName("It: provisional request를 Service로 전달하고 200을 반환한다")
        void itResetsPassword() throws Exception {
            mockMvc.perform(post("/auth/password-reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email":" user@example.com ",
                                      "verificationToken":"verification-token-123",
                                      "newPassword":"newPassword1234"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("비밀번호가 재설정되었습니다."));

            then(passwordResetService).should().reset(
                    "user@example.com", "verification-token-123", "newPassword1234"
            );
        }

        @Test
        @DisplayName("It: 잘못된 email은 400으로 거부한다")
        void itRejectsInvalidEmail() throws Exception {
            mockMvc.perform(post("/auth/password-reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"not-an-email","verificationToken":"verification-token-123","newPassword":"newPassword1234"}
                                    """))
                    .andExpect(status().isBadRequest());
            then(passwordResetService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 빈 verification token은 400으로 거부한다")
        void itRejectsBlankVerificationToken() throws Exception {
            mockMvc.perform(post("/auth/password-reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","verificationToken":"","newPassword":"newPassword1234"}
                                    """))
                    .andExpect(status().isBadRequest());
            then(passwordResetService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: Signup password 정책을 만족하지 않는 password는 400으로 거부한다")
        void itRejectsInvalidNewPassword() throws Exception {
            mockMvc.perform(post("/auth/password-reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","verificationToken":"verification-token-123","newPassword":"short"}
                                    """))
                    .andExpect(status().isBadRequest());
            then(passwordResetService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("Describe: POST /auth/login")
    class DescribeLogin {

        @Nested
        @DisplayName("Context: 공백이 포함된 유효한 요청이면")
        class WithValidRequest {

            @Test
            @DisplayName("It: 정규화된 요청으로 200과 Token 응답을 반환한다")
            void itReturnsOkWithNormalizedRequest() throws Exception {
                // given
                given(authService.login(any(LoginRequest.class))).willReturn(tokens());

                // when
                var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": " user@example.com ",
                                  "password": "  long-password  "
                                }
                                """));

                // then
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));
                then(authService).should().login(argThat(request ->
                        request.email().equals("user@example.com")
                                && request.password().equals("  long-password  ")
                ));
            }
        }
    }

    @Nested
    @DisplayName("Describe: POST /auth/refresh")
    class DescribeRefresh {

        @Nested
        @DisplayName("Context: 유효한 Refresh Token이면")
        class WithValidRefreshToken {

            @Test
            @DisplayName("It: 200과 Token 응답을 반환한다")
            void itReturnsOkWithTokens() throws Exception {
                // given
                given(authService.refresh("refresh-token")).willReturn(tokens());

                // when
                var result = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "refresh-token"
                                }
                                """));

                // then
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.accessToken").value("access-token"));
            }
        }
    }

    @Nested
    @DisplayName("Describe: OAuth JSON endpoints")
    class DescribeOAuth {

        @Test
        @DisplayName("existing OAuth identity exchange returns Dogether tokens")
        void exchangeExistingIdentityReturnsTokens() throws Exception {
            given(authService.exchangeOAuth(OAuthProvider.GOOGLE, "login-code"))
                    .willReturn(new OAuthExchangeResult.ExistingUser<>(tokens()));

            mockMvc.perform(post("/auth/oauth/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"GOOGLE\",\"loginCode\":\"login-code\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                    .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                    .andExpect(jsonPath("$.data.accessTokenExpiresAt")
                            .value(ACCESS_TOKEN_EXPIRES_AT.toString()));
            then(authService).should().exchangeOAuth(OAuthProvider.GOOGLE, "login-code");
        }

        @Test
        @DisplayName("new OAuth identity exchange returns 202 signup token, never a password payload")
        void exchangeNewIdentityReturnsSignupRequired() throws Exception {
            given(authService.exchangeOAuth(OAuthProvider.GOOGLE, "login-code"))
                    .willReturn(new OAuthExchangeResult.SignupRequired<>(
                            "signup-token", ACCESS_TOKEN_EXPIRES_AT));

            mockMvc.perform(post("/auth/oauth/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"GOOGLE\",\"loginCode\":\"login-code\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.profileCompletionRequired").value(true))
                    .andExpect(jsonPath("$.data.signupToken").value("signup-token"))
                    .andExpect(jsonPath("$.data.signupTokenExpiresAt")
                            .value(ACCESS_TOKEN_EXPIRES_AT.toString()))
                    .andExpect(jsonPath("$.data.password").doesNotExist());
        }

        @Test
        @DisplayName("OAuth signup returns 201 and forwards the trimmed profile completion data and weight")
        void signupOAuthReturnsCreated() throws Exception {
            given(authService.signupOAuth(
                    "signup-token", "사용자", "1168010100", new java.math.BigDecimal("3.25")))
                    .willReturn(tokens());

            mockMvc.perform(post("/auth/oauth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"signupToken":"signup-token","nickname":" 사용자 ","neighborhoodCode":" 1168010100 ","weightKg":3.25}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));
            then(authService).should().signupOAuth(
                    "signup-token", "사용자", "1168010100", new java.math.BigDecimal("3.25"));
        }

        @Test
        @DisplayName("OAuth signup passes omitted and explicit null weight to the four-argument service contract")
        void signupOAuthKeepsNullWeightBackwardCompatible() throws Exception {
            given(authService.signupOAuth("omitted-token", "사용자", "1168010100", null))
                    .willReturn(tokens());
            given(authService.signupOAuth("null-token", "사용자", "1168010100", null))
                    .willReturn(tokens());

            mockMvc.perform(post("/auth/oauth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"signupToken":"omitted-token","nickname":"사용자","neighborhoodCode":"1168010100"}
                                    """))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/auth/oauth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"signupToken":"null-token","nickname":"사용자","neighborhoodCode":"1168010100","weightKg":null}
                                    """))
                    .andExpect(status().isCreated());

            then(authService).should().signupOAuth("omitted-token", "사용자", "1168010100", null);
            then(authService).should().signupOAuth("null-token", "사용자", "1168010100", null);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1", "500.00"})
        @DisplayName("OAuth signup forwards inclusive weight bounds")
        void signupOAuthForwardsInclusiveWeightBounds(String weightKg) throws Exception {
            BigDecimal expectedWeight = new BigDecimal(weightKg);
            given(authService.signupOAuth("signup-token", "사용자", "1168010100", expectedWeight))
                    .willReturn(tokens());

            mockMvc.perform(post("/auth/oauth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"signupToken":"signup-token","nickname":"사용자","neighborhoodCode":"1168010100","weightKg":%s}
                                    """.formatted(weightKg)))
                    .andExpect(status().isCreated());

            then(authService).should().signupOAuth("signup-token", "사용자", "1168010100", expectedWeight);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.99", "500.01", "3.256", "\"3.25\""})
        @DisplayName("OAuth signup rejects invalid or string weight before service")
        void signupOAuthRejectsInvalidWeightKg(String weightKg) throws Exception {
            mockMvc.perform(post("/auth/oauth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"signupToken":"signup-token","nickname":"사용자","neighborhoodCode":"1168010100","weightKg":%s}
                                    """.formatted(weightKg)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            then(authService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("NAVER existing and new identities retain the common exchange and signup response contract")
        void naverOAuthUsesTheSameExchangeAndSignupContract() throws Exception {
            given(authService.exchangeOAuth(OAuthProvider.NAVER, "existing-code"))
                    .willReturn(new OAuthExchangeResult.ExistingUser<>(tokens()));
            given(authService.exchangeOAuth(OAuthProvider.NAVER, "new-code"))
                    .willReturn(new OAuthExchangeResult.SignupRequired<>("naver-signup-token", ACCESS_TOKEN_EXPIRES_AT));
            given(authService.signupOAuth("naver-signup-token", "네이버", "1168010100", null))
                    .willReturn(tokens());

            mockMvc.perform(post("/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"NAVER\",\"loginCode\":\"existing-code\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").value("access-token"));
            mockMvc.perform(post("/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"NAVER\",\"loginCode\":\"new-code\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.signupToken").value("naver-signup-token"));
            mockMvc.perform(post("/auth/oauth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"signupToken\":\"naver-signup-token\",\"nickname\":\" 네이버 \",\"neighborhoodCode\":\" 1168010100 \"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));

            then(authService).should().exchangeOAuth(OAuthProvider.NAVER, "existing-code");
            then(authService).should().exchangeOAuth(OAuthProvider.NAVER, "new-code");
            then(authService).should().signupOAuth("naver-signup-token", "네이버", "1168010100", null);
        }
    }

    @Nested
    @DisplayName("Describe: POST /auth/logout")
    class DescribeLogout {

        @Nested
        @DisplayName("Context: CurrentUser가 인증되어 있으면")
        class WithAuthenticatedCurrentUser {

            @Test
            @DisplayName("It: 204를 반환하고 현재 User의 Token을 폐기한다")
            void itReturnsNoContentAndRevokesCurrentUsersTokens() throws Exception {
                // given
                CurrentUser currentUser = new CurrentUser(
                        1L,
                        "user@example.com",
                        Role.USER
                );
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        currentUser,
                        null,
                        currentUser.getAuthorities()
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // when
                var result = mockMvc.perform(post("/auth/logout"));

                // then
                result.andExpect(status().isNoContent());
                then(authService).should().logout(1L);
            }
        }
    }

    private AuthTokensResponse tokens() {
        return new AuthTokensResponse(
                "access-token",
                "refresh-token",
                ACCESS_TOKEN_EXPIRES_AT
        );
    }
}
