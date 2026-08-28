package itda.route.dto;

import java.util.UUID;

public record RouteAcceptedResponse(UUID requestId, String status) {
}

