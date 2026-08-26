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
                                    "mediaId":1,
                                    "uploadId":"upload-001",
                                    "parts":[
                                        {
                                            "partNumber":1,
                                            "url":"https://storage.example.com/upload"
                                        }
                                    ]
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

    @Operation(summary = "미디어 업로드 완료", description = "업로드된 미디어 파트를 완료 처리하는 API")
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
                                    "mediaId":1,
                                    "mediaType":"IMAGE",
                                    "status":"UPLOADED",
                                    "url":"https://storage.example.com/media/1"
                                },
                                "error":null
                            }
                            """)
            )
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
