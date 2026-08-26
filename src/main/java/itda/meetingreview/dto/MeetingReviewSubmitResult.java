package itda.meetingreview.dto;

import java.time.Instant;

/**
 * 후기 작성 응답(04_M3_API_상세명세.md §11 POST /meetings/{meetingId}/reviews).
 */
public record MeetingReviewSubmitResult(
        Long reviewId,
        Long meetingId,
        String placeTag,
        String content,
        Instant createdAt,
        ReviewFootprintResult footprint
) {
}
