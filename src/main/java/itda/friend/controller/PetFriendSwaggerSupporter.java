package itda.friend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.friend.dto.response.PetFriendListResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Pet Friend", description = "Pet 친구 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface PetFriendSwaggerSupporter {

    @Operation(summary = "Pet 친구 목록 조회", description = "Pet 친구 목록을 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Pet 친구 목록 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"Pet 친구 목록을 조회했습니다.",
                                "data":{
                                    "items":[
                                        {
                                            "petId":20,
                                            "publicTag":"pet#TAG2",
                                            "nickname":"target",
                                            "profileUrl":null,
                                            "verified":true,
                                            "relationship":"FRIEND"
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
    ResponseEntity<ApiResponse<PetFriendListResponse>> listFriends(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "Pet ID") Long petId,
            @Parameter(description = "다음 페이지 조회용 커서") String cursor,
            @Parameter(description = "조회 개수") Integer limit
    );

    @Operation(summary = "Pet 친구 삭제", description = "Pet 친구 관계를 삭제하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "Pet 친구 삭제 성공"
    )
    ResponseEntity<Void> deleteFriendship(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "Pet ID") Long petId,
            @Parameter(description = "친구 Pet ID") Long friendPetId
    );
}
