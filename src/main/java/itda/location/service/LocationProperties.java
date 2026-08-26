package itda.location.service;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.location")
@Validated
public record LocationProperties(
        @NotNull Duration maxCaptureAge,
        @NotNull Duration futureTolerance,
        @Positive double qualityThresholdMeters
) {
    @AssertTrue(message = "location durations must not be negative and maxCaptureAge must be positive")
    public boolean isDurationPolicyValid() {
        return maxCaptureAge != null
                && !maxCaptureAge.isZero()
                && !maxCaptureAge.isNegative()
                && futureTolerance != null
                && !futureTolerance.isNegative();
    }
}
