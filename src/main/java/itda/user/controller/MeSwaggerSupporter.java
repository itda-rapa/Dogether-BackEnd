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
import org.springframework.http.MediaType;

@Tag(name = "Me", description = "내 정보 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface MeSwaggerSupporter {

    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자 정보를 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "내 정보 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
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
