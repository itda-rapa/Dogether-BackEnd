package itda.route.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record RouteResponse(
        UUID requestId,
        String status,
        String activityType,
        String priorityType,
        BigDecimal speedKmh,
        Long startNodeId,
        List<Long> waypointNodeIds,
        Long destinationNodeId,
        JsonNode geoJson,
        BigDecimal totalDistanceMeters,
        BigDecimal ownerCaloriesKcal,
        BigDecimal petCaloriesKcal,
        BigDecimal averageSlope,
        BigDecimal durationMinutes,
        JsonNode nearbyFacilities,
        Instant departureAt,
        JsonNode environmentInfo,
        Instant savedAt,
        String errorCode,
        Instant createdAt,
        Instant completedAt
) {
}
