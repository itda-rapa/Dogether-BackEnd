package itda.pet.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.pet.dto.PetCreateRequest;
import itda.pet.dto.PetCreateResponse;
import itda.pet.dto.PetResponse;
import itda.pet.service.MyPetQueryService;
import itda.pet.service.PetCreationResult;
import itda.pet.service.PetCreationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pets")
public class PetController {

    private final PetCreationService petCreationService;
    private final MyPetQueryService myPetQueryService;

    public PetController(
            PetCreationService petCreationService,
            MyPetQueryService myPetQueryService
    ) {
        this.petCreationService = petCreationService;
        this.myPetQueryService = myPetQueryService;
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
}
