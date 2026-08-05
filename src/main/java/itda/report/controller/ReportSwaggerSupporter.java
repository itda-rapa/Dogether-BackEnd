package itda.report.controller;

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
import itda.report.dto.ReportCreateRequest;
import itda.report.dto.ReportResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Report", description = "신고 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface ReportSwaggerSupporter {

    @Operation(summary = "신고 접수", description = "신고를 접수하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ReportCreateRequest.class),
            examples = @ExampleObject("""
                    {
                        "roomId":1,
                        "reasonCode":"SPAM",
                        "detail":"반복적인 광고 메시지를 보냅니다."
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "신고 접수 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"신고가 접수되었습니다.",
                                "data":{
                                    "reportId":1,
                                    "roomId":1,
                                    "reasonCode":"SPAM",
                                    "status":"RECEIVED",
                                    "createdAt":"2026-08-05T00:00:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<ReportResponse>> createReport(
            @Parameter(hidden = true) CurrentUser currentUser,
            ReportCreateRequest request
    );
}
