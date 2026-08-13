package itda.petverification.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.pet.dto.PetResponse;
import itda.pet.service.MyPetQueryService;
import itda.petverification.dto.ApplyPetVerificationRequest;
import itda.petverification.service.ExistingPetVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pets")
public class ExistingPetVerificationController {
    private final ExistingPetVerificationService service;
    private final MyPetQueryService myPetQueryService;
    public ExistingPetVerificationController(ExistingPetVerificationService service,
                                             MyPetQueryService myPetQueryService) {
        this.service = service;
        this.myPetQueryService = myPetQueryService;
    }
    @PostMapping("/{petId}/verification")
    @Operation(summary = "기존 Pet 인증 적용")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기존 Pet 인증 적용 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PET_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "PET_NOT_OWNED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "PET_VERIFICATION_CONFLICT")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410", description = "PET_VERIFICATION_TOKEN_INVALID")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "PET_VERIFICATION_UNAVAILABLE")
    public ResponseEntity<ApiResponse<PetResponse>> apply(
            @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long petId,
            @Valid @RequestBody ApplyPetVerificationRequest request) {
        service.apply(currentUser.id(), petId, request.petVerificationToken());
        return ResponseEntity.ok(ApiResponse.ok(myPetQueryService.getMyPet(currentUser.id(), petId),
                "Pet 인증이 완료되었습니다."));
    }
}
