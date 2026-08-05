package itda.friend.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.friend.dto.FriendRequestCreateRequest;
import itda.friend.dto.response.FriendRequestListResponse;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.service.FriendRequestCommandResult;
import itda.friend.service.FriendRequestCommandService;
import itda.friend.service.query.FriendRequestQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/friend-requests")
@RequiredArgsConstructor
public class FriendRequestController implements FriendRequestSwaggerSupporter{

    private final FriendRequestCommandService commandService;
    private final FriendRequestQueryService queryService;

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

    @PostMapping("/{requestId}/accept")
    public ResponseEntity<ApiResponse<FriendRequestResponse>> accept(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long requestId
    ) {
        FriendRequestResponse response = commandService.accept(
                currentUser.id(),
                requestId
        );
        return ResponseEntity.ok(
                ApiResponse.ok(response, "친구 요청을 수락했습니다.")
        );
    }

    @PostMapping("/{requestId}/reject")
    public ResponseEntity<ApiResponse<FriendRequestResponse>> reject(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long requestId
    ) {
        FriendRequestResponse response = commandService.reject(
                currentUser.id(),
                requestId
        );
        return ResponseEntity.ok(
                ApiResponse.ok(response, "친구 요청을 거절했습니다.")
        );
    }

    @DeleteMapping("/{requestId}")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long requestId
    ) {
        commandService.cancel(currentUser.id(), requestId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/received")
    public ResponseEntity<ApiResponse<FriendRequestListResponse>> listReceived(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.listReceived(currentUser.id(), cursor, limit),
                "받은 친구 요청 목록이 조회되었습니다."
        ));
    }

    @GetMapping("/sent")
    public ResponseEntity<ApiResponse<FriendRequestListResponse>> listSent(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.listSent(currentUser.id(), cursor, limit),
                "보낸 친구 요청 목록이 조회되었습니다."
        ));
    }
}
