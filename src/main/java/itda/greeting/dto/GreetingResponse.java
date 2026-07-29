package itda.greeting.dto;

import itda.greeting.domain.GreetingStatus;
import java.time.Instant;

public record GreetingResponse(
        Long greetingId,
        Long roomId,
        GreetingStatus status,
        String fixedMessage,
        Instant expiresAt,
        Instant createdAt
) {
}
