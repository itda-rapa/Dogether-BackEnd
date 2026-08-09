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
import itda.user.domain.Role;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
                ));
            }
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
