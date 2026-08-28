package itda.chat.dto.request;

import itda.map.domain.CulturalFacilityCategory;
import java.math.BigDecimal;
import jakarta.validation.constraints.NotNull;

public record CreateChatMapMessageRequest(
        @NotNull Long triggerMessageId,
        @NotNull CulturalFacilityCategory category,
        @NotNull BigDecimal longitude,
        @NotNull BigDecimal latitude
) {
}
