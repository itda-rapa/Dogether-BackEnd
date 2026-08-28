package itda.route.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record RouteShareRequest(
        @NotBlank @Size(max = 64) String clientMessageId,
        @NotNull UUID routeId
) {
}

