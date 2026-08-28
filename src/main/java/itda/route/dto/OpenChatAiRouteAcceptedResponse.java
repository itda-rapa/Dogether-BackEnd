package itda.route.dto;

import itda.route.domain.RouteActivityType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OpenChatAiRouteAcceptedResponse(
        UUID requestId,
        String status,
        String routeMode,
        RouteActivityType activityType,
        String start,
        List<String> waypoints,
        String destination,
        BigDecimal targetDistanceKm
) {
}
