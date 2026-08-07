package itda.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.redis")
@Validated
public record RedisProperties(
        String host,
        String port
) {
}
