package itda.auth.controller;

import itda.auth.dto.AuthTokensResponse;
import itda.auth.dto.LoginRequest;
import itda.auth.dto.RefreshRequest;
import itda.auth.dto.SignupRequest;
import itda.auth.service.AuthService;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
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

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        authService.logout(currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
