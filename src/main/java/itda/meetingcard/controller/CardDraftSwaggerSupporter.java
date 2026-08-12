package itda.meetingcard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingcard.dto.response.CardDraftResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Card Draft", description = "약속 카드 초안 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface CardDraftSwaggerSupporter {

    @Operation(summary = "약속 카드 초안 생성", description = "채팅방 대화 기반으로 약속 카드 초안을 생성하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "약속 카드 초안 생성 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"약속 카드 초안 생성 성공",
                                "data":[{
                                    "draftId":1,
                                    "roomId":1,
                                    "cardType":"WALK",
                                    "placeText":"한강공원",
                                    "meetAt":"2026-08-10T09:00:00Z"
                                }],
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<List<CardDraftResponse>>> createDraft(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "채팅방 ID") long roomId
    );
}
