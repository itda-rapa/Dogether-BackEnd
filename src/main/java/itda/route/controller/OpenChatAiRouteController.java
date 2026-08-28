package itda.route.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.route.dto.OpenChatAiRouteAcceptedResponse;
import itda.route.service.OpenChatAiRouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/rooms/open/{roomId}/ai-routes")
@RequiredArgsConstructor
public class OpenChatAiRouteController {

    private final OpenChatAiRouteService service;

    @PostMapping
    public ResponseEntity<ApiResponse<OpenChatAiRouteAcceptedResponse>> create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        return ResponseEntity.accepted().body(ApiResponse.ok(
                service.create(currentUser.id(), roomId),
                "최근 대화 기반 AI 경로 생성을 시작했습니다."));
    }
}
