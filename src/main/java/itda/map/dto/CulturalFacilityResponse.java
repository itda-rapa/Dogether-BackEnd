package itda.map.dto;

import itda.map.domain.CulturalFacilityCategory;
import itda.map.repository.NearbyCulturalFacilityRow;
import java.math.BigDecimal;

public record CulturalFacilityResponse(
        Integer facilityId,
        CulturalFacilityCategory category,
        String name,
        String address,
        String telephone,
        String homepage,
        String operatingHours,
        BigDecimal longitude,
        BigDecimal latitude,
        Double distanceMeters
) {
    public static CulturalFacilityResponse from(
            NearbyCulturalFacilityRow row,
            CulturalFacilityCategory category
    ) {
        return new CulturalFacilityResponse(
                row.getFacilityId(), category, row.getName(), row.getAddress(),
                row.getTelephone(), row.getHomepage(), row.getOperatingHours(),
                row.getLongitude(), row.getLatitude(), row.getDistanceMeters());
    }
}
