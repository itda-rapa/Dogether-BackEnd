package itda.common.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.hibernate.validator.constraints.time.DurationMin;

@ConfigurationProperties(prefix = "app.media")
@Validated
public record MediaProperties(
        @NotNull @DurationMin(seconds = 1) Duration uploadUrlTtl,
        @NotNull @DurationMin(seconds = 1) Duration viewUrlTtl,
        @NotNull @DurationMin(seconds = 1) Duration deleteGrace,
        @Min(1) long maxUploadBytes
) {
}
