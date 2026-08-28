package itda.map.dto;

import java.math.BigDecimal;

public record NearbyMapPlaceRequest(
        MapPlaceType type,
        BigDecimal longitude,
        BigDecimal latitude,
        Integer radiusMeters
) {
}
