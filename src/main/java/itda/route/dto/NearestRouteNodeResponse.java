package itda.route.dto;

import java.math.BigDecimal;

public record NearestRouteNodeResponse(
        Long nodeId,
        BigDecimal longitude,
        BigDecimal latitude
) {
}

