package itda.user.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.pet.service.ActivePetSelectionService;
import itda.user.dto.ActivePetUpdateRequest;
import itda.user.dto.MeResponse;
import itda.user.service.MeQueryService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
public class MeController implements MeSwaggerSupporter {

    private final MeQueryService meQueryService;
    private final ActivePetSelectionService activePetSelectionService;

    public MeController(
            MeQueryService meQueryService,
            ActivePetSelectionService activePetSelectionService
    ) {
        this.meQueryService = meQueryService;
        this.activePetSelectionService = activePetSelectionService;
    }

    @GetMapping
    public ApiResponse<MeResponse> getMe(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(
                meQueryService.getMe(currentUser.id()),
                "내 정보가 조회되었습니다."
        );
    }

    @PutMapping("/active-pet")
    public ApiResponse<MeResponse> selectActivePet(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ActivePetUpdateRequest request
    ) {
        activePetSelectionService.selectActivePet(
                currentUser.id(),
                request.petId()
        );
        return ApiResponse.ok(
                meQueryService.getMe(currentUser.id()),
                "Active Pet이 변경되었습니다."
        );
    }
}
