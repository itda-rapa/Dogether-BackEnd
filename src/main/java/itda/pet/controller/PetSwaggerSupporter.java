package itda.pet.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.pet.dto.PetCreateRequest;
import itda.pet.dto.PetCreateResponse;
import itda.pet.dto.PetPublicProfileResponse;
import itda.pet.dto.PetProfileImageRequest;
import itda.pet.dto.PetResponse;
import itda.pet.dto.PetSearchItemResponse;
import itda.pet.dto.PetUpdateRequest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@Tag(name = "Pet", description = "Pet 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface PetSwaggerSupporter {

    @Operation(summary = "Pet 등록", description = "Pet을 등록하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = PetCreateRequest.class),
            examples = @ExampleObject("""
                    {
                        "nickname":"초코",
                        "breedName":"푸들",
                        "sex":"MALE",
                        "neutered":true,
                        "birthDate":"2023-01-01",
                        "weightKg":5.2,
                        "sizeCode":"SMALL",
                        "bio":"산책을 좋아해요",
                        "personalityTags":["활발함","사교적"],
                        "careNote":"낯선 소리에 민감해요",
                        "petVerificationToken":"pet-verification-token-placeholder"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Pet 등록 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"Pet 등록이 완료되었습니다.",
                                "data":{
                                    "pet":{
                                        "petId":10,
                                        "publicTag":"pet#TAG1",
                                        "nickname":"초코",
                                        "profileUrl":null,
                                        "verified":true,
                                        "version":0,
                                        "helpfulReceivedCount":0
                                    },
                                    "activePetAssignmentStatus":"ASSIGNED"
                                },
                                "error":null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "PET_VERIFICATION_CONFLICT")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410", description = "PET_VERIFICATION_TOKEN_INVALID")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "PET_VERIFICATION_UNAVAILABLE")
    ResponseEntity<ApiResponse<PetCreateResponse>> createPet(
            @Parameter(hidden = true) CurrentUser currentUser,
            PetCreateRequest request
    );

    @Operation(summary = "내 Pet 목록 조회", description = "내 Pet 목록을 조회하는 API입니다. 각 PetResponse의 helpfulReceivedCount는 삭제되지 않은 게시글·댓글이 받은 HELPFUL 합계입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "내 Pet 목록 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"내 Pet 목록을 조회했습니다.",
                                "data":[
                                    {
                                        "petId":10,
                                        "publicTag":"pet#TAG1",
                                        "nickname":"초코",
                                        "profileUrl":null,
                                        "verified":true,
                                        "version":0,
                                        "helpfulReceivedCount":12
                                    }
                                ],
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<List<PetResponse>>> getMyPets(
            @Parameter(hidden = true) CurrentUser currentUser
    );

    @Operation(summary = "Pet 검색", description = "Public Tag로 Pet을 검색하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Pet 검색 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"Pet 검색이 완료되었습니다.",
                                "data":{
                                    "petId":20,
                                    "publicTag":"pet#TAG2",
                                    "nickname":"나나",
                                    "profileUrl":null,
                                    "verified":true,
                                    "relationship":"NONE"
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<PetSearchItemResponse>> searchPet(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "검색할 Pet 공개 태그") String publicTag
    );

    @Operation(summary = "내 Pet 상세 조회", description = "내 Pet 상세 정보를 조회하는 API입니다. helpfulReceivedCount는 삭제되지 않은 게시글·댓글이 받은 HELPFUL 합계입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Pet 상세 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"Pet 상세 정보가 조회되었습니다.",
                                "data":{
                                    "petId":10,
                                    "publicTag":"pet#TAG1",
                                    "nickname":"초코",
                                    "profileUrl":null,
                                    "verified":true,
                                    "version":0,
                                    "helpfulReceivedCount":12
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<PetResponse>> getMyPet(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "Pet ID") Long petId
    );

    @Operation(
            summary = "Pet 공개 프로필 조회",
            description = "로그인한 사용자가 공개 가능한 Pet의 프로필을 조회합니다. "
                    + "Pet이 없거나 ACTIVE·미삭제 상태가 아니거나, 소유자 계정이 비활성이거나, "
                    + "조회자와 소유자 사이에 양방향 Block이 있으면 PET_NOT_FOUND로 은닉합니다. "
                    + "자기 Pet 또는 조회자의 Active Pet이 없으면 relationship은 null입니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Pet 공개 프로필 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(ref = "#/components/schemas/ApiResponsePetPublicProfileResponse"),
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"Pet 공개 프로필이 조회되었습니다.",
                                "data":{
                                    "petId":20,
                                    "publicTag":"pet#TAG2",
                                    "nickname":"나나",
                                    "profileUrl":"https://storage.example/presigned...",
                                    "verified":true,
                                    "breedName":"푸들",
                                    "sex":"FEMALE",
                                    "neutered":true,
                                    "birthDate":"2023-01-01",
                                    "sizeCode":"SMALL",
                                    "bio":"산책을 좋아해요",
                                    "personalityTags":["활발함","사교적"],
                                    "helpfulReceivedCount":12,
                                    "relationship":"FRIEND"
                                },
                                "error":null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "공개 가능한 Pet이 없거나 양방향 Block 관계임 (PET_NOT_FOUND)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "인증 실패 (UNAUTHORIZED)"
    )
    ResponseEntity<ApiResponse<PetPublicProfileResponse>> getPublicProfile(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "Pet ID") Long petId
    );

    @Operation(
            summary = "Pet 프로필 이미지 최초 설정",
            description = "본인 소유의 Pet에 업로드 완료된 IMAGE Media를 최초 1회 연결합니다. "
                    + "최초 설정은 201을 반환하며, PetResponse.version을 함께 반환합니다."
    )
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = PetProfileImageRequest.class),
            examples = @ExampleObject("""
                    {"mediaId":123}
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Pet 프로필 이미지 최초 설정 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {"success":true,"message":"Pet 프로필 이미지가 설정되었습니다.","data":{"petId":10,"publicTag":"pet#TAG1","nickname":"초코","profileUrl":"https://...","status":"ACTIVE","version":1,"verified":false,"active":true,"helpfulReceivedCount":12},"error":null}
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "이미 프로필 이미지가 설정됨 (PET_PROFILE_IMAGE_ALREADY_SET)"
    )
    ResponseEntity<ApiResponse<PetResponse>> setInitialProfileImage(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "Pet ID") Long petId,
            PetProfileImageRequest request
    );

    @Operation(
            summary = "Pet 프로필 이미지 교체",
            description = "본인 소유의 Pet 프로필 이미지 link를 교체합니다. "
                    + "If-Match에 현재 PetResponse.version을 큰따옴표로 감싼 값(예: \"3\")으로 보내야 합니다. "
                    + "Media 검증은 같은 Media를 다시 지정하는 no-op에도 적용되며, 실제 교체 시 version이 1 증가하고 "
                    + "같은 Media를 다시 지정하면 version은 증가하지 않습니다."
    )
    @RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PetProfileImageRequest.class),
                    examples = @ExampleObject("""
                            {"mediaId":123}
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Pet 프로필 이미지 교체 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"Pet 프로필 이미지가 교체되었습니다.",
                                "data":{
                                    "petId":10,
                                    "publicTag":"pet#TAG1",
                                    "nickname":"초코",
                                    "profileUrl":"https://...",
                                    "status":"ACTIVE",
                                    "version":4,
                                    "verified":true,
                                    "active":true,
                                    "helpfulReceivedCount":12
                                },
                                "error":null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "If-Match 누락·형식 오류 또는 요청 body 검증 실패 (VALIDATION_FAILED)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Pet 또는 Media 소유권 없음 (PET_NOT_OWNED, MEDIA_NOT_OWNED)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Pet 또는 Media 없음 (PET_NOT_FOUND, MEDIA_NOT_FOUND)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "If-Match version이 현재 version과 다름 (CONCURRENT_UPDATE_CONFLICT)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "IMAGE가 아니거나 업로드 완료 상태가 아님 (INVALID_MEDIA_TYPE, MEDIA_NOT_UPLOADED)"
    )
    ResponseEntity<ApiResponse<PetResponse>> replaceProfileImage(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "Pet ID") Long petId,
            @Parameter(
                    name = "If-Match",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "현재 PetResponse.version을 큰따옴표로 감싼 strong ETag. 예: \"3\"",
                    example = "\"\\\"3\\\"\"",
                    schema = @Schema(type = "string")
            ) List<String> ifMatchValues,
            PetProfileImageRequest request
    );

    @Operation(
            summary = "Pet 프로필 이미지 제거",
            description = "본인 소유의 Pet에서 프로필 이미지 link만 해제합니다. "
                    + "If-Match에 현재 PetResponse.version을 큰따옴표로 감싼 값(예: \"3\")으로 보내야 합니다. "
                    + "body와 응답 body가 없으며, DELETE ETag도 반환하지 않습니다. "
                    + "이미 link가 없으면 no-op으로 version은 증가하지 않습니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "Pet 프로필 이미지 link 제거 성공 (응답 body·ETag 없음)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "If-Match 누락·형식 오류 (VALIDATION_FAILED)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Pet 소유권 없음 (PET_NOT_OWNED)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Pet 없음 (PET_NOT_FOUND)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "If-Match version이 현재 version과 다름 (CONCURRENT_UPDATE_CONFLICT)"
    )
    ResponseEntity<Void> deleteProfileImage(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "Pet ID") Long petId,
            @Parameter(
                    name = "If-Match",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "현재 PetResponse.version을 큰따옴표로 감싼 strong ETag. 예: \"3\"",
                    example = "\"\\\"3\\\"\"",
                    schema = @Schema(type = "string")
            ) List<String> ifMatchValues
    );

    @Operation(summary = "Pet 정보 수정", description = "Pet 정보를 부분 수정하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = PetUpdateRequest.class),
            examples = @ExampleObject("""
                    {
                        "nickname":"초코",
                        "bio":"산책을 아주 좋아해요"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Pet 정보 수정 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"Pet 정보가 수정되었습니다.",
                                "data":{
                                    "petId":10,
                                    "publicTag":"pet#TAG1",
                                    "nickname":"초코",
                                    "profileUrl":null,
                                    "verified":true,
                                    "version":1,
                                    "helpfulReceivedCount":12
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<PetResponse>> updatePet(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "Pet ID") Long petId,
            JsonNode requestBody
    );
}
