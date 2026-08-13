package itda.petverification.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.petverification.dto.PetVerificationRequest;
import itda.petverification.dto.PetVerificationResponse;
import itda.petverification.service.PetVerificationIssueService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/pet-verifications")
public class PetVerificationController {
    private final PetVerificationIssueService service;
    public PetVerificationController(PetVerificationIssueService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "Pet 등록정보 인증", description = "공식 등록정보를 확인하고 일회성 적용 토큰을 발급합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 evidence 토큰 발급 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "PET_VERIFICATION_CONFLICT")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "PET_VERIFICATION_NOT_MATCHED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "PET_VERIFICATION_UNAVAILABLE")
    public ResponseEntity<ApiResponse<PetVerificationResponse>> issue(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody PetVerificationRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(service.issue(currentUser.id(), request),
                "Pet 인증 정보를 확인했습니다."));
    }
}
