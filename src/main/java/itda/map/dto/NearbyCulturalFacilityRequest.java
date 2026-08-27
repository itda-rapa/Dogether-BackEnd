package itda.map.dto;

import itda.map.domain.CulturalFacilityCategory;
import java.math.BigDecimal;

public record NearbyCulturalFacilityRequest(
        CulturalFacilityCategory category,
        BigDecimal longitude,
        BigDecimal latitude
) {
}
