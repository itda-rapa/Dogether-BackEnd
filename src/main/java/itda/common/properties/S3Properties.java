package itda.common.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        Long presignedUrlExpirationSeconds
) {
}
