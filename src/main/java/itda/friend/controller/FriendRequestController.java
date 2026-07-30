package itda.friend.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.friend.dto.FriendRequestCreateRequest;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.service.FriendRequestCommandResult;
import itda.friend.service.FriendRequestCommandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/friend-requests")
@RequiredArgsConstructor
public class FriendRequestController {

    private final FriendRequestCommandService commandService;

    @PostMapping
    public ResponseEntity<ApiResponse<FriendRequestResponse>> create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody FriendRequestCreateRequest request
    ) {
        FriendRequestCommandResult result = commandService.create(
                currentUser.id(),
                request.targetPetId()
        );
        if (result.created()) {
            return ResponseEntity.status(201).body(
                    ApiResponse.created(
                            result.response(),
                            "친구 요청을 보냈습니다."
                    )
            );
        }
        return ResponseEntity.ok(
                ApiResponse.ok(
                        result.response(),
                        "친구 요청을 자동으로 수락했습니다."
                )
        );
    }
}
