package itda.pet.controller;

import itda.common.constants.ErrorCode;
import itda.common.dto.ApiResponse;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.pet.dto.PetCreateRequest;
import itda.pet.dto.PetCreateResponse;
import itda.pet.dto.PetResponse;
import itda.pet.dto.PetProfileImageRequest;
import itda.pet.dto.PetSearchItemResponse;
import itda.pet.dto.PetUpdateRequest;
import itda.pet.dto.PetUpdateRequestParser;
import itda.pet.service.MyPetQueryService;
import itda.pet.service.PetCreationResult;
import itda.pet.service.PetCreationService;
import itda.pet.service.PetUpdateService;
import itda.pet.service.PetProfileImageService;
import itda.pet.service.query.PetSearchQueryService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/pets")
public class PetController implements PetSwaggerSupporter {

    private final PetCreationService petCreationService;
    private final MyPetQueryService myPetQueryService;
    private final PetSearchQueryService petSearchQueryService;
    private final PetUpdateRequestParser petUpdateRequestParser;
    private final PetUpdateService petUpdateService;
    private final PetProfileImageService petProfileImageService;

    public PetController(
            PetCreationService petCreationService,
            MyPetQueryService myPetQueryService,
            PetSearchQueryService petSearchQueryService,
            PetUpdateRequestParser petUpdateRequestParser,
            PetUpdateService petUpdateService,
            PetProfileImageService petProfileImageService
    ) {
        this.petCreationService = petCreationService;
        this.myPetQueryService = myPetQueryService;
        this.petSearchQueryService = petSearchQueryService;
        this.petUpdateRequestParser = petUpdateRequestParser;
        this.petUpdateService = petUpdateService;
        this.petProfileImageService = petProfileImageService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PetCreateResponse>> createPet(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody PetCreateRequest request
    ) {
        PetCreationResult result = petCreationService.create(
                currentUser.id(),
                request.toCommand()
        );
        PetResponse pet = myPetQueryService.getMyPet(
                currentUser.id(),
                result.petId()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(
                        new PetCreateResponse(
                                pet,
                                result.activePetAssignmentStatus()
                        ),
                        "Pet 등록이 완료되었습니다."
                ));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<PetResponse>>> getMyPets(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                myPetQueryService.getMyPets(currentUser.id()),
                "내 Pet 목록이 조회되었습니다."
        ));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PetSearchItemResponse>> searchPet(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String publicTag
    ) {
        String normalizedPublicTag = normalizePublicTag(publicTag);
        PetSearchItemResponse result = petSearchQueryService.search(
                currentUser.id(),
                normalizedPublicTag
        ).orElse(null);

        return ResponseEntity.ok(ApiResponse.ok(
                result,
                "Pet 검색이 완료되었습니다."
        ));
    }

    @GetMapping("/{petId}")
    public ResponseEntity<ApiResponse<PetResponse>> getMyPet(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long petId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                myPetQueryService.getMyPet(currentUser.id(), petId),
                "Pet 상세 정보가 조회되었습니다."
        ));
    }

    @PostMapping("/{petId}/profile-image")
    public ResponseEntity<ApiResponse<PetResponse>> setInitialProfileImage(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long petId,
            @Valid @RequestBody PetProfileImageRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(
                        petProfileImageService.setInitialProfileImage(
                                currentUser.id(),
                                petId,
                                request.mediaId()
                        ),
                        "Pet 프로필 이미지가 설정되었습니다."
                ));
    }

    @PatchMapping("/{petId}")
    @Operation(
            summary = "Pet 정보 부분 수정",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(
                                    implementation = PetUpdateRequest.class
                            )
                    )
            )
    )
    public ResponseEntity<ApiResponse<PetResponse>> updatePet(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long petId,
            @RequestBody JsonNode requestBody
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                petUpdateService.update(
                        currentUser.id(),
                        petId,
                        petUpdateRequestParser.parse(requestBody)
                ),
                "Pet 정보가 수정되었습니다."
        ));
    }

    private String normalizePublicTag(String publicTag) {
        if (publicTag == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        String normalizedPublicTag = publicTag.strip();
        int codePointLength = normalizedPublicTag.codePointCount(
                0,
                normalizedPublicTag.length()
        );
        if (normalizedPublicTag.isEmpty() || codePointLength > 30) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalizedPublicTag;
    }
}
