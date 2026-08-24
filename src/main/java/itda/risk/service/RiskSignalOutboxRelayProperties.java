package itda.risk.service;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.risk.outbox-relay")
public record RiskSignalOutboxRelayProperties(
        boolean enabled,
        @Min(1000) long delayMs,
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration lease,
        @NotNull Duration sendTimeout,
        @NotNull Duration maxBlock,
        @Min(1) @Max(100) int maxAttempts,
        @NotNull Duration baseBackoff,
        @NotNull Duration maxBackoff
) {
    @AssertTrue(message = "risk outbox relay durations must be positive and safely ordered")
    public boolean isDurationsValid() {
        return positiveMillis(lease)
                && positiveMillis(sendTimeout)
                && positiveMillis(maxBlock)
                && positiveMillis(baseBackoff)
                && positiveMillis(maxBackoff)
                && lease.compareTo(sendTimeout.plus(maxBlock).plusSeconds(5)) >= 0
                && maxBackoff.compareTo(baseBackoff) >= 0;
    }

    private static boolean positiveMillis(Duration duration) {
        return duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0;
    }
}
