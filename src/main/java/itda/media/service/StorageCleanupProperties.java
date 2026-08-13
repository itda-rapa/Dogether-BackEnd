package itda.media.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.storage-cleanup")
public record StorageCleanupProperties(
        @Min(1000) long delayMs,
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration lease,
        @Min(1) @Max(100) int maxAttempts,
        @NotNull Duration maxUploadDuration,
        @NotNull Duration uploadSettleGrace,
        @NotNull Duration baseBackoff,
        @NotNull Duration maxBackoff
) {
    @AssertTrue(message = "storage cleanup durations must be positive and ordered")
    public boolean isDurationsValid() {
        return positive(lease)
                && positive(uploadSettleGrace)
                && positive(maxUploadDuration)
                && positive(baseBackoff)
                && positive(maxBackoff)
                && uploadSettleGrace.compareTo(maxUploadDuration) >= 0
                && !maxBackoff.minus(baseBackoff).isNegative()
                && lease.compareTo(Duration.ofSeconds(30)) >= 0;
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
