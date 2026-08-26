package itda.meetingreview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingreview.dto.MeetingReviewSubmitCommand;
import itda.meetingreview.dto.MeetingReviewSubmitResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Meeting Review", description = "만남 후기·발자국 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface MeetingReviewSwaggerSupporter {

    @Operation(summary = "만남 후기 작성", description = "확정된 만남에 장소 태그와 선택형 한 줄 후기를 등록한다. footprint.granted는 이번 HTTP 요청이 새 Footprint 행을 INSERT했는지를 뜻하므로 멱등 replay나 같은 날 기존 발자국 재사용이면 false다.")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = MeetingReviewSubmitCommand.class),
            examples = @ExampleObject("""
                    {
                        "clientRequestId": "9eb374ad-e81d-433a-8934-93faf399d48e",
                        "placeTag": "공원",
                        "content": "즐겁게 산책했어요."
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "만남 후기 등록 성공(발자국 적립 또는 이미 그날 발자국 존재)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success": true,
                                "message": "만남 후기가 등록되었습니다.",
                                "data": {
                                    "reviewId": 71,
                                    "meetingId": 61,
                                    "placeTag": "공원",
                                    "content": "즐겁게 산책했어요.",
                                    "createdAt": "2026-08-20T09:10:00Z",
                                    "footprint": {
                                        "granted": true,
                                        "footprintId": 81,
                                        "duplicateDay": false,
                                        "earnedDate": "2026-08-20"
                                    }
                                },
                                "error": null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED: 요청 검증 실패"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "ACTIVE_PET_REQUIRED / REVIEW_NOT_PARTICIPANT: 활동 반려견 없음 또는 약속 참여 Pet 이 아님"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "MEETING_NOT_FOUND: 확정된 만남이 없음"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "REVIEW_ALREADY_EXISTS / REVIEW_REQUEST_CONFLICT / REVIEW_CARD_NOT_OPEN"
    )
    ResponseEntity<ApiResponse<MeetingReviewSubmitResult>> submit(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "확정된 만남 ID") long meetingId,
            MeetingReviewSubmitCommand command
    );
}
