package itda.meetingreview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingreview.dto.FootprintListResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Footprint", description = "발자국 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface FootprintSwaggerSupporter {

    @Operation(summary = "내 Active Pet 발자국 조회", description = "내 Active Pet 발자국을 최신순으로 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "발자국 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success": true,
                                "message": "발자국 조회 성공",
                                "data": {
                                    "items": [
                                        {
                                            "footprintId": 81,
                                            "meetingId": 61,
                                            "counterpartPet": {
                                                "petId": 22,
                                                "nickname": "초코"
                                            },
                                            "earnedDate": "2026-08-20",
                                            "createdAt": "2026-08-20T09:10:00Z"
                                        }
                                    ],
                                    "page": { "nextCursor": null, "hasNext": false }
                                },
                                "error": null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED: 잘못된 커서 또는 페이지 크기 범위(1~100)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "ACTIVE_PET_REQUIRED: 활동 반려견 없음"
    )
    ResponseEntity<ApiResponse<FootprintListResponse>> listMine(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "커서. 첫 페이지는 생략") String cursor,
            @Parameter(description = "페이지 크기. 기본 20·최대 100") Integer size
    );
}
