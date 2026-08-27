package itda.auth.controller;

import itda.auth.dto.AuthTokensResponse;
import itda.auth.dto.LoginRequest;
import itda.auth.dto.OAuthExchangeRequest;
import itda.auth.dto.OAuthExchangeResponse;
import itda.auth.dto.OAuthSignupRequest;
import itda.auth.dto.OAuthSignupRequiredResponse;
import itda.auth.dto.PasswordResetRequest;
import itda.auth.dto.RefreshRequest;
import itda.auth.dto.SignupRequest;
import itda.auth.service.AuthService;
import itda.auth.service.PasswordResetService;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.email.EmailVerificationService;
import itda.email.dto.EmailVerificationChallengeResponse;
import itda.email.dto.EmailVerificationConfirmedResponse;
import itda.email.dto.EmailVerificationConfirmRequest;
import itda.email.dto.EmailVerificationSendRequest;
import itda.oauth.service.OAuthExchangeResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController implements AuthSwaggerSupporter {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService,
                          EmailVerificationService emailVerificationService,
                          PasswordResetService passwordResetService) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/email-verifications")
    public ResponseEntity<ApiResponse<EmailVerificationChallengeResponse>> requestEmailVerification(
            @Valid @RequestBody EmailVerificationSendRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                emailVerificationService.request(request), "이메일 인증 발송 요청이 접수되었습니다."
        ));
    }

    @PostMapping("/email-verifications/confirm")
    public ApiResponse<EmailVerificationConfirmedResponse> confirmEmailVerification(
            @Valid @RequestBody EmailVerificationConfirmRequest request
    ) {
        return ApiResponse.ok(emailVerificationService.confirm(request), "이메일 인증이 완료되었습니다.");
    }

    @PostMapping("/password-reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.reset(
                request.email(), request.verificationToken(), request.newPassword()
        );
        return ApiResponse.ok("비밀번호가 재설정되었습니다.");
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<AuthTokensResponse>> signup(
            @Valid @RequestBody SignupRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(
                        authService.signup(request),
                        "회원가입이 완료되었습니다."
                ));
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokensResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return ApiResponse.ok(
                authService.login(request),
                "로그인되었습니다."
        );
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokensResponse> refresh(
            @Valid @RequestBody RefreshRequest request
    ) {
        return ApiResponse.ok(
                authService.refresh(request.refreshToken()),
                "토큰이 재발급되었습니다."
        );
    }

    @PostMapping("/oauth/exchange")
    public ResponseEntity<ApiResponse<OAuthExchangeResponse>> exchangeOAuth(
            @Valid @RequestBody OAuthExchangeRequest request
    ) {
        OAuthExchangeResult<AuthTokensResponse> result = authService.exchangeOAuth(
                request.provider(), request.loginCode()
        );
        if (result instanceof OAuthExchangeResult.ExistingUser<AuthTokensResponse> existingUser) {
            return ResponseEntity.ok(ApiResponse.<OAuthExchangeResponse>ok(
                    existingUser.value(), "OAuth 로그인이 완료되었습니다."
            ));
        }

        OAuthExchangeResult.SignupRequired<AuthTokensResponse> signupRequired =
                (OAuthExchangeResult.SignupRequired<AuthTokensResponse>) result;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.<OAuthExchangeResponse>ok(
                new OAuthSignupRequiredResponse(
                        signupRequired.profileCompletionRequired(),
                        signupRequired.signupToken(),
                        signupRequired.signupTokenExpiresAt()
                ),
                "OAuth 가입을 위한 추가 정보 입력이 필요합니다."
        ));
    }

    @PostMapping("/oauth/signup")
    public ResponseEntity<ApiResponse<AuthTokensResponse>> signupOAuth(
            @Valid @RequestBody OAuthSignupRequest request
    ) {
        AuthTokensResponse tokens = authService.signupOAuth(
                request.signupToken(),
                request.nickname(),
                request.neighborhoodCode(),
                request.weightKg()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(
                tokens,
                "OAuth 회원가입이 완료되었습니다."
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        authService.logout(currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
