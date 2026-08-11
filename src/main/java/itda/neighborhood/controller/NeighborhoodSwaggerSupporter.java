package itda.neighborhood.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.neighborhood.dto.NeighborhoodResponse;
import java.util.List;
import org.springframework.http.MediaType;

@Tag(name = "Neighborhood", description = "동네 관련 API")
public interface NeighborhoodSwaggerSupporter {

    @Operation(summary = "동네 목록 조회", description = "가입 가능한 동네 목록을 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "동네 목록 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"가입 가능한 동네 목록을 조회했습니다.",
                                "data":[
                                    {
                                        "code":"SEOUL_GANGNAM",
                                        "name":"서울 강남구"
                                    }
                                ],
                                "error":null
                            }
                            """)
            )
    )
    ApiResponse<List<NeighborhoodResponse>> listNeighborhoods();
}
