package itda.route.controller;

import itda.chat.dto.response.ChatMessageResponse;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.route.dto.RouteShareRequest;
import itda.route.dto.RouteResponse;
import itda.route.service.RouteShareService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/chat/rooms/{roomId}/route-shares")
public class RouteShareController {

    private final RouteShareService routeShareService;

    public RouteShareController(RouteShareService routeShareService) {
        this.routeShareService = routeShareService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChatMessageResponse>> share(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable long roomId,
            @Valid @RequestBody RouteShareRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(routeShareService.share(user.id(), roomId, request),
                "경로를 오픈채팅방에 공유했습니다."));
    }

    @GetMapping("/{routeId}")
    public ResponseEntity<ApiResponse<RouteResponse>> getShared(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable long roomId,
            @PathVariable UUID routeId) {
        return ResponseEntity.ok(ApiResponse.ok(
                routeShareService.getShared(user.id(), roomId, routeId),
                "공유된 경로를 조회했습니다."));
    }
}
