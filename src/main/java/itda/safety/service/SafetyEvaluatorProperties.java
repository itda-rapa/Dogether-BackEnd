package itda.safety.service;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.safety.evaluator")
public record SafetyEvaluatorProperties(
        boolean enabled,
        Integer threshold,
        Duration window,
        Integer policyVersion,
        @Min(1) @Max(1000) int batchSize,
        @Min(1000) long delayMs,
        @NotNull Duration lease,
        @Min(1) @Max(100) int maxAttempts,
        @NotNull Duration baseBackoff,
        @NotNull Duration maxBackoff
) {
    @AssertTrue(message = "enabled safety evaluator requires an explicit policy")
    public boolean isPolicyConfigured() {
        if (!enabled) {
            return true;
        }
        return threshold != null && threshold > 0
                && window != null && window.compareTo(Duration.ofMillis(1)) >= 0
                && window.compareTo(Duration.ofDays(90)) <= 0
                && policyVersion != null && policyVersion > 0;
    }

    @AssertTrue(message = "safety evaluator durations must be positive and safely ordered")
    public boolean isDurationsValid() {
        return positive(lease) && positive(baseBackoff) && positive(maxBackoff)
                && maxBackoff.compareTo(baseBackoff) >= 0;
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0;
    }
}
