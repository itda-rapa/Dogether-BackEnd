package itda.notification.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.notification.dto.NotificationResponse;
import itda.notification.service.NotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> list(
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.list(currentUser.id()), "알림 목록을 조회했습니다."));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markRead(
            @AuthenticationPrincipal CurrentUser currentUser, @PathVariable long notificationId) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.markRead(currentUser.id(), notificationId),
                "알림을 읽었습니다."));
    }
}
