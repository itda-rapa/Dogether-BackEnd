package itda.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(type = "object") JsonNode geoJson,
        BigDecimal totalDistanceMeters,
        BigDecimal ownerCaloriesKcal,
        BigDecimal petCaloriesKcal,
        BigDecimal averageSlope,
        BigDecimal durationMinutes,
        @Schema(type = "object") JsonNode nearbyFacilities,
        Instant departureAt,
        @Schema(type = "object") JsonNode environmentInfo,
        Instant savedAt,
        String errorCode,
        Instant createdAt,
        Instant completedAt
) {
}
