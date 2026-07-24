package itda.common.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.media")
public record MediaProperties(
        Duration uploadUrlTtl,
        Duration viewUrlTtl,
        long maxUploadBytes
) {
}
