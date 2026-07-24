package itda.common.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.hibernate.validator.constraints.time.DurationMin;

@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank @Size(min = 32) String secret,
        @NotNull @DurationMin(seconds = 1) Duration accessTtl,
        @NotNull @DurationMin(seconds = 1) Duration refreshTtl
) {
}
