package itda.meetingreview.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingreview.dto.MeetingReviewSubmitCommand;
import itda.meetingreview.dto.MeetingReviewSubmitResult;
import itda.meetingreview.service.MeetingReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/meetings")
@RequiredArgsConstructor
public class MeetingReviewController implements MeetingReviewSwaggerSupporter {

    private final MeetingReviewService meetingReviewService;

    /**
     * 만남 후기 작성. 후기 저장과 발자국 적립이 한 트랜잭션으로 처리된다.
     * 같은 (meetingId, petId) 재작성은 409, 같은 clientRequestId 재요청은 멱등이다.
     */
    @PostMapping("/{meetingId}/reviews")
    public ResponseEntity<ApiResponse<MeetingReviewSubmitResult>> submit(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long meetingId,
            @Valid @RequestBody MeetingReviewSubmitCommand command
    ) {
        MeetingReviewSubmitResult result =
                meetingReviewService.submit(currentUser.id(), meetingId, command);
        return ResponseEntity.status(201)
                .body(ApiResponse.created(result, "만남 후기가 등록되었습니다."));
    }
}
