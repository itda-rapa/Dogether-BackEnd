package itda.route.dto;

import itda.route.domain.RouteActivityType;
import itda.route.domain.RoutePriorityType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public record RoundTripRouteCreateRequest(
        @NotNull Long startNodeId,
        @NotNull @Min(500) @Max(50000) Long targetDistanceMeters,
        @NotNull RouteActivityType activityType,
        @NotNull RoutePriorityType priorityType,
        @NotNull @DecimalMin("1.0") @DecimalMax("45.0") BigDecimal speedKmh,
        @NotNull @FutureOrPresent Instant departureAt
) {
}
