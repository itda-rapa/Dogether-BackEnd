package itda.block.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.block.dto.BlockCreateRequest;
import itda.block.dto.response.BlockListResponse;
import itda.block.dto.response.BlockResponse;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Block", description = "차단 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface BlockSwaggerSupporter {

    @Operation(summary = "차단 생성", description = "대상 Pet을 기준으로 사용자를 차단하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = BlockCreateRequest.class),
            examples = @ExampleObject("""
                    {
                        "targetPetId":20
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "차단 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"차단이 완료되었습니다.",
                                "data":{
                                    "blockId":1,
                                    "blockedPetId":20,
                                    "blockedAt":"2026-08-05T00:00:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<BlockResponse>> block(
            @Parameter(hidden = true) CurrentUser currentUser,
            BlockCreateRequest request
    );

    @Operation(summary = "차단 목록 조회", description = "내 차단 목록을 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "차단 목록 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"차단 목록 조회 성공",
                                "data":{
                                    "items":[
                                        {
                                            "blockId":1,
                                            "blockedPetId":20,
                                            "blockedAt":"2026-08-05T00:00:00Z"
                                        }
                                    ],
                                    "nextCursor":null,
                                    "hasNext":false
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<BlockListResponse>> listBlocks(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "다음 페이지 조회용 커서") String cursor,
            @Parameter(description = "조회 개수") Integer limit
    );
}
