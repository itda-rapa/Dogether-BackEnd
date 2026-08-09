package itda.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.auth.dto.AuthTokensResponse;
import itda.auth.dto.LoginRequest;
import itda.auth.dto.PasswordResetRequest;
import itda.auth.dto.RefreshRequest;
import itda.auth.dto.SignupRequest;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.email.dto.EmailVerificationChallengeResponse;
import itda.email.dto.EmailVerificationConfirmedResponse;
import itda.email.dto.EmailVerificationConfirmRequest;
import itda.email.dto.EmailVerificationSendRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "인증 관련 API")
public interface AuthSwaggerSupporter {

    @Operation(summary = "이메일 인증번호 발송", description = "회원가입 또는 비밀번호 재설정용 인증번호 발송을 요청합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "발송 요청 접수")
    ResponseEntity<ApiResponse<EmailVerificationChallengeResponse>> requestEmailVerification(
            EmailVerificationSendRequest request
    );

    @Operation(summary = "이메일 인증번호 확인", description = "인증번호를 확인하고 1회용 verification token을 발급합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 완료")
    ApiResponse<EmailVerificationConfirmedResponse> confirmEmailVerification(
            EmailVerificationConfirmRequest request
    );

    @Operation(
            summary = "비밀번호 재설정",
            description = "이메일 인증 완료 후 발급된 verification token을 사용해 비밀번호를 재설정합니다."
    )
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = PasswordResetRequest.class),
            examples = @ExampleObject("""
                    {
                        "email":"user@example.com",
                        "verificationToken":"email-verification-token",
                        "newPassword":"newPassword1234"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 재설정 성공")
    ApiResponse<Void> resetPassword(PasswordResetRequest request);

    @Operation(summary = "회원가입", description = "회원가입을 처리하고 토큰을 발급하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = SignupRequest.class),
            examples = @ExampleObject("""
                    {
                        "email":"user@example.com",
                        "password":"password1234",
                        "nickname":"도기",
                        "neighborhoodCode":"SEOUL_GANGNAM",
                        "verificationToken":"email-verification-token"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "회원가입 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"회원가입이 완료되었습니다.",
                                "data":{
                                    "accessToken":"eyJhbGciOiJIUzI1NiJ9.access",
                                    "refreshToken":"eyJhbGciOiJIUzI1NiJ9.refresh",
                                    "tokenType":"Bearer",
                                    "expiresIn":3600
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<AuthTokensResponse>> signup(SignupRequest request);

    @Operation(summary = "로그인", description = "로그인을 처리하고 토큰을 발급하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = LoginRequest.class),
            examples = @ExampleObject("""
                    {
                        "email":"user@example.com",
                        "password":"password1234"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "로그인 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"로그인되었습니다.",
                                "data":{
                                    "accessToken":"eyJhbGciOiJIUzI1NiJ9.access",
                                    "refreshToken":"eyJhbGciOiJIUzI1NiJ9.refresh",
                                    "tokenType":"Bearer",
                                    "expiresIn":3600
                                },
                                "error":null
                            }
                            """)
            )
    )
    ApiResponse<AuthTokensResponse> login(LoginRequest request);

    @Operation(summary = "토큰 재발급", description = "Refresh Token으로 토큰을 재발급하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = RefreshRequest.class),
            examples = @ExampleObject("""
                    {
                        "refreshToken":"eyJhbGciOiJIUzI1NiJ9.refresh"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "토큰 재발급 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"토큰이 재발급되었습니다.",
                                "data":{
                                    "accessToken":"eyJhbGciOiJIUzI1NiJ9.newAccess",
                                    "refreshToken":"eyJhbGciOiJIUzI1NiJ9.newRefresh",
                                    "tokenType":"Bearer",
                                    "expiresIn":3600
                                },
                                "error":null
                            }
                            """)
            )
    )
    ApiResponse<AuthTokensResponse> refresh(RefreshRequest request);

    @Operation(summary = "로그아웃", description = "현재 사용자의 Refresh Token을 폐기하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "로그아웃 성공"
    )
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<Void> logout(@Parameter(hidden = true) CurrentUser currentUser);
}
