package itda.risk.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import itda.risk.contract.RiskTopic;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.risk.consumer")
public record RiskSignalConsumerProperties(
        boolean enabled,
        @NotBlank String groupId,
        @Min(1) @Max(16) int concurrency,
        @Min(1) @Max(1000) int maxPollRecords,
        @Min(1) @Max(20) int maxAttempts,
        @NotNull Duration retryBackoff,
        @NotBlank String dltTopic,
        @NotNull Duration dltPublishTimeout,
        @NotNull Duration maxFutureSkew,
        @NotNull Duration maxAggregationRange
) {
    @AssertTrue(message = "risk consumer durations must be at least one millisecond")
    public boolean isDurationsValid() {
        return atLeastOneMillisecond(retryBackoff)
                && atLeastOneMillisecond(dltPublishTimeout)
                && atLeastOneMillisecond(maxFutureSkew)
                && atLeastOneMillisecond(maxAggregationRange);
    }

    @AssertTrue(message = "risk consumer DLT topic must differ from the source topic")
    public boolean isDltTopicSafe() {
        return dltTopic == null || !RiskTopic.RISK_SIGNAL_TOPIC.equals(dltTopic.trim());
    }

    private static boolean atLeastOneMillisecond(Duration duration) {
        return duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0;
    }
}
