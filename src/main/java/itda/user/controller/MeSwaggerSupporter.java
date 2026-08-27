package itda.user.controller;

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
import itda.user.dto.ActivePetUpdateRequest;
import itda.user.dto.MeResponse;
import itda.user.dto.MeUpdateRequest;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

@Tag(name = "Me", description = "내 정보 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface MeSwaggerSupporter {

    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자 정보를 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "내 정보 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(ref = "#/components/schemas/ApiResponseMeResponse"),
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"내 정보가 조회되었습니다.",
                                "data":{
                                    "userId":1,
                                    "email":"user@example.com",
                                    "nickname":"도기",
                                    "activePetId":10
                                },
                                "error":null
                            }
                            """)
            )
    )
    ApiResponse<MeResponse> getMe(
            @Parameter(hidden = true) CurrentUser currentUser
    );

    @Operation(summary = "내 정보 수정", description = "nickname, neighborhoodCode, weightKg 중 수정할 사용자 항목만 포함하는 API")
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = MeUpdateRequest.class),
            examples = @ExampleObject("""
                    {
                        "nickname":"도기",
                        "weightKg":12.50
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "내 정보 수정 성공"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "엄격한 요청 본문 검증 실패 (VALIDATION_FAILED)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "인증 실패 (UNAUTHORIZED)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "사용자 없음 (USER_NOT_FOUND)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "동시 수정 충돌 (CONCURRENT_UPDATE_CONFLICT)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "활성 동네 코드가 아님 (NEIGHBORHOOD_NOT_FOUND)"
    )
    ApiResponse<MeResponse> updateMe(
            @Parameter(hidden = true) CurrentUser currentUser,
            JsonNode requestBody
    );

    @Operation(summary = "Active Pet 변경", description = "대표 Pet을 변경하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ActivePetUpdateRequest.class),
            examples = @ExampleObject("""
                    {
                        "petId":10
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Active Pet 변경 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"Active Pet이 변경되었습니다.",
                                "data":{
                                    "userId":1,
                                    "email":"user@example.com",
                                    "nickname":"도기",
                                    "activePetId":10
                                },
                                "error":null
                            }
                            """)
            )
    )
    ApiResponse<MeResponse> selectActivePet(
            @Parameter(hidden = true) CurrentUser currentUser,
            ActivePetUpdateRequest request
    );
}
