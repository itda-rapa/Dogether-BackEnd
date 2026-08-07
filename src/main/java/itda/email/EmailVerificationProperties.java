package itda.email;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.email-verification")
public record EmailVerificationProperties(
        String hmacSecret,
        Duration challengeTtl,
        Duration tokenTtl,
        Duration cooldown,
        Duration payloadTtl,
        boolean workerEnabled,
        String senderMode,
        String streamKey,
        String streamGroup,
        String streamConsumer,
        long streamMaxLength,
        int maxAttempts
) {
}
