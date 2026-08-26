package itda.meetingverification.controller;

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
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.dto.MeetingVerificationStatusResponse;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Meeting Verification", description = "만남 위치 제출·양쪽 확인 API")
@SecurityRequirement(name = "bearerAuth")
public interface MeetingVerificationSwaggerSupporter {

    @Operation(summary = "만남 위치 제출·판정",
            description = "위치를 제출하고 양쪽 제출이 모이면 GPS 확정을 시도한다.")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = MeetingVerificationSubmitCommand.class),
            examples = @ExampleObject("""
                    {
                        "clientRequestId":"b0bbdc4d-4174-4abe-b837-e364866a9308",
                        "latitude":37.5665,
                        "longitude":126.978,
                        "accuracyMeters":24.5,
                        "capturedAt":"2026-08-20T09:00:00Z"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "위치 제출 성공(대기 또는 확정)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"만남이 확인되었습니다.",
                                "data":{
                                    "cardId":51,
                                    "submittedPetId":12,
                                    "counterpartSubmitted":true,
                                    "meetingId":61,
                                    "confirmed":true,
                                    "verificationMethod":"GPS",
                                    "confirmedAt":"2026-08-20T09:02:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "잘못된 위치 또는 요청",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "활성 Pet이 없거나 유효하지 않음, 또는 약속 카드 참여자가 아님",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "약속 카드 없음",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "취소 카드·요청 충돌·거리/시간 초과·이미 확정",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
    )
    ResponseEntity<ApiResponse<MeetingVerificationResult>> submit(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "약속 카드 ID") long cardId,
            MeetingVerificationSubmitCommand command
    );

    @Operation(summary = "만남 확인 상태 조회", description = "현재 사용자 기준 만남 확인 상태를 조회한다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "상태 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"만남 확인 상태 조회 성공",
                                "data":{
                                    "cardId":51,
                                    "mySubmitted":true,
                                    "counterpartSubmitted":false,
                                    "meetingId":null,
                                    "confirmed":false,
                                    "verificationMethod":null,
                                    "confirmedAt":null,
                                    "codeRequired":false
                                },
                                "error":null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "약속 카드 없음",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "참여자 아님",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
    )
    ResponseEntity<ApiResponse<MeetingVerificationStatusResponse>> getStatus(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "약속 카드 ID") long cardId
    );
}
