package itda.route.dto;

import itda.route.domain.RouteActivityType;
import itda.route.domain.RoutePriorityType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RouteCreateRequest(
        @NotNull Long startNodeId,
        @Size(max = 20) List<@NotNull Long> waypointNodeIds,
        @NotNull Long destinationNodeId,
        @NotNull RouteActivityType activityType,
        @NotNull RoutePriorityType priorityType,
        @NotNull @DecimalMin("1.0") @DecimalMax("45.0") BigDecimal speedKmh,
        @NotNull @FutureOrPresent Instant departureAt
) {
}
