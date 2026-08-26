package itda.media.controller;

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
import itda.media.dto.downloaddto.PresignedUrlResponse;
import itda.media.dto.uploaddto.MediaInitRequest;
import itda.media.dto.uploaddto.MediaInitResponse;
import itda.media.dto.uploaddto.MediaResponse;
import itda.media.dto.uploaddto.MediaUploadedRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Media", description = "미디어 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface MediaSwaggerSupporter {

    @Operation(summary = "미디어 업로드 초기화", description = "미디어 업로드를 초기화하고 Presigned URL을 발급하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = MediaInitRequest.class),
            examples = @ExampleObject("""
                    {
                        "mediaType":"IMAGE",
                        "contentType":"image/png",
                        "fileSize":1048576
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "미디어 초기화 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"Media 객체가 초기화되었습니다.",
                                "data":{
                                    "id":1,
                                    "mediaType":"IMAGE",
                                    "contentType":"image/png",
                                    "path":"posts/1/uuid.png",
                                    "status":"INIT",
                                    "userId":1,
                                    "presignedUrl":"https://storage.example.com/upload",
                                    "presignedHeaders":{
                                        "Content-Type":"image/png",
                                        "Content-Length":"1048576"
                                    },
                                    "uploadId":"upload-001",
                                    "presignedUrlParts":[
                                        {
                                            "partNumber":1,
                                            "presignedUrl":"https://storage.example.com/upload/part-1",
                                            "headers":{
                                                "Content-Length":"5242880"
                                            }
                                        }
                                    ],
                                    "createdAt":"2026-08-26T00:00:00Z",
                                    "updatedAt":"2026-08-26T00:00:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    ApiResponse<MediaInitResponse> initMedia(
            @Parameter(hidden = true) CurrentUser user,
            MediaInitRequest request
    );

    @Operation(
            summary = "미디어 업로드 완료",
            description = "업로드를 완료한 뒤 Object Storage HEAD의 실제 크기와 MIME을 검증한다. "
                    + "검증에 성공한 Media만 COMPLETED 상태로 전이한다."
    )
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = MediaUploadedRequest.class),
            examples = @ExampleObject("""
                    {
                        "mediaId":1,
                        "parts":[
                            {
                                "partNumber":1,
                                "eTag":"etag-value"
                            }
                        ]
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "미디어 업로드 완료 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"성공적으로 업로드되었습니다.",
                                "data":{
                                    "id":1,
                                    "mediaType":"IMAGE",
                                    "contentType":"image/png",
                                    "path":"posts/1/uuid.png",
                                    "status":"COMPLETED",
                                    "userId":1,
                                    "fileSize":1048576,
                                    "attributes":{
                                        "parts":[
                                            {"partNumber":1,"eTag":"etag-value"}
                                        ]
                                    },
                                    "createdAt":"2026-08-26T00:00:00Z",
                                    "modifiedAt":"2026-08-26T00:01:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Media 소유자가 아님 (MEDIA_NOT_OWNED)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "INIT 상태가 아니거나 빈 multipart 완료 요청 (MEDIA_STATE_CONFLICT)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "실제 업로드된 객체를 확인할 수 없음 (MEDIA_NOT_UPLOADED), "
                    + "또는 VIDEO 실제 재생 길이가 5초를 초과함 (MEDIA_DURATION_INVALID)"
    )
    ApiResponse<MediaResponse> mediaUploaded(
            @Parameter(hidden = true) CurrentUser user,
            MediaUploadedRequest request
    );

    @Operation(summary = "미디어 다운로드 URL 발급", description = "미디어 다운로드용 Presigned URL을 발급하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "미디어 다운로드 URL 발급 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"다운로드용 PresignedURL을 발급했습니다.",
                                "data":{
                                    "presignedUrl":"https://storage.example.com/download",
                                    "media":{
                                        "mediaId":1,
                                        "mediaType":"IMAGE",
                                        "status":"UPLOADED",
                                        "url":"https://storage.example.com/media/1"
                                    }
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<PresignedUrlResponse>> getPresignedUrl(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "미디어 ID") Long id
    );
}
