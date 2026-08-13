package itda.pet.controller;

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
import itda.pet.dto.PetCreateRequest;
import itda.pet.dto.PetCreateResponse;
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
                                        "verified":true
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

    @Operation(summary = "내 Pet 목록 조회", description = "내 Pet 목록을 조회하는 API")
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
                                        "verified":true
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

    @Operation(summary = "내 Pet 상세 조회", description = "내 Pet 상세 정보를 조회하는 API")
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
                                    "verified":true
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
            summary = "Pet 프로필 이미지 최초 설정",
            description = "본인 소유의 Pet에 업로드 완료된 IMAGE Media를 최초 1회 연결합니다."
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
                            {"success":true,"message":"Pet 프로필 이미지가 설정되었습니다.","data":{"petId":10,"publicTag":"pet#TAG1","nickname":"초코","profileUrl":"https://...","verified":false},"error":null}
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
                                    "verified":true
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
