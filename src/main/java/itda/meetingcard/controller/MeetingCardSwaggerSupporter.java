package itda.meetingcard.controller;

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
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.dto.response.MeetingCardResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Meeting Card", description = "약속 카드 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface MeetingCardSwaggerSupporter {

    @Operation(summary = "약속 카드 확정", description = "약속 카드를 확정하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = MeetingCardCreateRequest.class),
            examples = @ExampleObject("""
                    {
                        "roomId":1,
                        "draftId":1,
                        "cardType":"WALK",
                        "placeText":"한강공원",
                        "meetAt":"2026-08-10T09:00:00Z"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "약속 카드 생성 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"약속 카드가 생성되었습니다.",
                                "data":{
                                    "cardId":1,
                                    "roomId":1,
                                    "cardType":"WALK",
                                    "placeText":"한강공원",
                                    "meetAt":"2026-08-10T09:00:00Z",
                                    "status":"CONFIRMED"
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<MeetingCardResponse>> confirm(
            @Parameter(hidden = true) CurrentUser currentUser,
            MeetingCardCreateRequest request
    );

    @Operation(summary = "약속 카드 상세 조회", description = "약속 카드 상세 정보를 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "약속 카드 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"약속 카드 조회 성공",
                                "data":{
                                    "cardId":1,
                                    "roomId":1,
                                    "cardType":"WALK",
                                    "placeText":"한강공원",
                                    "meetAt":"2026-08-10T09:00:00Z",
                                    "status":"CONFIRMED"
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<MeetingCardResponse>> get(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "약속 카드 ID") long cardId
    );

    @Operation(summary = "약속 카드 취소", description = "약속 카드를 취소하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "약속 카드 취소 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"약속이 취소되었습니다.",
                                "data":{
                                    "cardId":1,
                                    "status":"CANCELED"
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<MeetingCardResponse>> cancel(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "약속 카드 ID") long cardId
    );
}
