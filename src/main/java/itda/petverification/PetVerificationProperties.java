package itda.petverification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.pet-verification")
public record PetVerificationProperties(
        String hmacSecret,
        String serviceKey,
        Duration tokenTtl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
