package itda.meetingverification.controller;

import io.swagger.v3.oas.annotations.Operation;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingverification.dto.ConfirmationCodeCreateResult;
import itda.meetingverification.dto.ConfirmationCodeResult;
import itda.meetingverification.dto.ConfirmationCodeVerifyRequest;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.dto.MeetingVerificationStatusResponse;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import itda.meetingverification.service.MeetingConfirmationCodeService;
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
    private final MeetingConfirmationCodeService codeService;

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

    @PostMapping("/{cardId}/confirmation-codes")
    @Operation(summary = "Confirmation Code 발급", description = "평문 4자리 코드는 이 응답에서만 반환되며 저장되지 않습니다. 기존 cycle은 최초 issuer만 재발급할 수 있으며 아니면 403 MEETING_CODE_REISSUE_ISSUER_FORBIDDEN입니다. receivedAt >= deadline인 새 발급·재발급은 409입니다.",
            responses = {@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "카드 없음·비DIRECT·비참여자·대상 비활성(MEETING_CARD_NOT_FOUND) 또는 Chat 접근 차단(CHAT_ROOM_NOT_FOUND)"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410")})
    public ResponseEntity<ApiResponse<ConfirmationCodeCreateResult>> issueCode(
            @AuthenticationPrincipal CurrentUser currentUser, @PathVariable long cardId) {
        return ResponseEntity.status(201).body(ApiResponse.created(
                codeService.issue(currentUser.id(), cardId), "확인 코드가 발급되었습니다."));
    }

    @PostMapping("/{cardId}/confirmation-codes/verify")
    @Operation(summary = "상대 Pet의 Confirmation Code 검증", description = "검증만으로 Meeting은 생성되지 않습니다.",
            responses = {@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "카드 없음·비DIRECT·비참여자·대상 비활성(MEETING_CARD_NOT_FOUND) 또는 Chat 접근 차단(CHAT_ROOM_NOT_FOUND)"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429")})
    public ResponseEntity<ApiResponse<ConfirmationCodeResult>> verifyCode(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long cardId,
            @Valid @RequestBody ConfirmationCodeVerifyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                codeService.verify(currentUser.id(), cardId, request.code()), "상대 확인이 기록되었습니다."));
    }

    @PostMapping("/{cardId}/confirmation-codes/confirm")
    @Operation(summary = "코드 생성자의 최종 확인", description = "상대 검증 뒤 생성자가 확인해야 CODE Meeting 한 건이 확정됩니다.",
            responses = {@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "카드 없음·비DIRECT·비참여자·대상 비활성(MEETING_CARD_NOT_FOUND) 또는 Chat 접근 차단(CHAT_ROOM_NOT_FOUND)"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410")})
    public ResponseEntity<ApiResponse<ConfirmationCodeResult>> confirmCode(
            @AuthenticationPrincipal CurrentUser currentUser, @PathVariable long cardId) {
        return ResponseEntity.ok(ApiResponse.ok(
                codeService.confirm(currentUser.id(), cardId), "만남이 확인되었습니다."));
    }
}
