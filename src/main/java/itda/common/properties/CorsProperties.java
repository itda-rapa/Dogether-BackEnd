package itda.common.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.cors")
@Validated
public record CorsProperties(
        @NotEmpty List<@NotBlank String> allowedOrigins
) {
}
