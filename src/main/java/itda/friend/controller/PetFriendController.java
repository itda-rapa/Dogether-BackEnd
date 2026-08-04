package itda.friend.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.friend.dto.response.PetFriendListResponse;
import itda.friend.service.FriendshipDeletionService;
import itda.friend.service.query.FriendshipQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pets/{petId}/friends")
@RequiredArgsConstructor
public class PetFriendController {

    private final FriendshipQueryService friendshipQueryService;
    private final FriendshipDeletionService friendshipDeletionService;

    @GetMapping
    public ResponseEntity<ApiResponse<PetFriendListResponse>> listFriends(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long petId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                friendshipQueryService.listFriends(
                        currentUser.id(),
                        petId,
                        cursor,
                        limit
                ),
                "Pet 친구 목록이 조회되었습니다."
        ));
    }

    @DeleteMapping("/{friendPetId}")
    public ResponseEntity<Void> deleteFriendship(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long petId,
            @PathVariable Long friendPetId
    ) {
        friendshipDeletionService.deleteFriendship(
                currentUser.id(),
                petId,
                friendPetId
        );
        return ResponseEntity.noContent().build();
    }
}
