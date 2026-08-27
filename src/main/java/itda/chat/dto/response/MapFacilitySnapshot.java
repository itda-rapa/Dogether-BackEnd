package itda.chat.dto.response;

import java.math.BigDecimal;

public record MapFacilitySnapshot(
        Integer facilityId,
        String name,
        String address,
        String telephone,
        String operatingHours,
        BigDecimal longitude,
        BigDecimal latitude,
        Double distanceMeters,
        Double averageDistanceMeters,
        Integer distanceParticipantCount,
        Integer distanceRank
) {
}
