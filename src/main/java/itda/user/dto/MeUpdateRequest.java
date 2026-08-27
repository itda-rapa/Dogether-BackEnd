package itda.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * OpenAPI schema for PATCH /me. Runtime input is parsed from JsonNode so that
 * omitted properties remain distinguishable from explicit null values.
 */
@Schema(name = "MeUpdateRequest", description = "수정할 항목만 포함하는 내 정보 부분 수정 요청")
public record MeUpdateRequest(
        @Schema(description = "공백 제거 후 2~20자인 닉네임", example = "도기")
        String nickname,
        @Schema(description = "활성 동네 코드", example = "4113111500")
        String neighborhoodCode,
        @Schema(description = "사용자 체중(kg). null이면 사용자 체중 정보를 삭제합니다.",
                types = {"number", "null"}, example = "12.50", nullable = true,
                maximum = "500.00", minimum = "1.00")
        BigDecimal weightKg
) {
}
