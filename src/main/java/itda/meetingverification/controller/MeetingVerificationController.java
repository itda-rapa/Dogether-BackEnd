package itda.meetingverification.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.dto.MeetingVerificationStatusResponse;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import itda.meetingverification.service.MeetingVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/meeting-cards")
@RequiredArgsConstructor
public class MeetingVerificationController implements MeetingVerificationSwaggerSupporter {

    private final MeetingVerificationService meetingVerificationService;

    /** 만남 위치 제출·판정. 확정되면 Meeting 정보를, 아니면 대기 상태를 반환한다. */
    @PostMapping("/{cardId}/meeting-verifications")
    public ResponseEntity<ApiResponse<MeetingVerificationResult>> submit(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long cardId,
            @Valid @RequestBody MeetingVerificationSubmitCommand command
    ) {
        MeetingVerificationResult result =
                meetingVerificationService.submit(currentUser.id(), cardId, command);
        String message = result.confirmed()
                ? "만남이 확인되었습니다."
                : result.codeRequired()
                        ? "위치 정확도가 낮아 확인 코드가 필요합니다."
                        : "상대방의 확인을 기다리고 있습니다.";
        return ResponseEntity.ok(ApiResponse.ok(result, message));
    }

    /** 현재 사용자 기준 만남 확인 상태 조회. 좌표는 반환하지 않는다. */
    @GetMapping("/{cardId}/meeting-verification")
    public ResponseEntity<ApiResponse<MeetingVerificationStatusResponse>> getStatus(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long cardId
    ) {
        MeetingVerificationStatusResponse status =
                meetingVerificationService.getStatus(currentUser.id(), cardId);
        return ResponseEntity.ok(ApiResponse.ok(status, "만남 확인 상태 조회 성공"));
    }
}
