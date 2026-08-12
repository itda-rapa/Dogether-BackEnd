package itda.common.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
        String accessKey,
        String secretKey,
        @NotBlank String bucket,
        @NotBlank String region,
        @NotNull @Positive @Max(604800) Long presignedUrlExpirationSeconds,
        String endpoint,
        String presignEndpoint,
        Boolean pathStyleAccessEnabled,
        Boolean allowInsecureEndpoint
) {
    @ConstructorBinding
    public S3Properties {
    }

    public S3Properties(
            String accessKey,
            String secretKey,
            String bucket,
            String region,
            Long presignedUrlExpirationSeconds
    ) {
        this(accessKey, secretKey, bucket, region, presignedUrlExpirationSeconds,
                null, null, false, false);
    }

    public S3Properties(
            String accessKey,
            String secretKey,
            String bucket,
            String region,
            Long presignedUrlExpirationSeconds,
            String endpoint,
            String presignEndpoint,
            Boolean pathStyleAccessEnabled
    ) {
        this(accessKey, secretKey, bucket, region, presignedUrlExpirationSeconds,
                endpoint, presignEndpoint, pathStyleAccessEnabled, false);
    }

    public boolean hasStaticCredentials() {
        return hasText(accessKey) && hasText(secretKey);
    }

    public boolean hasPartialStaticCredentials() {
        return hasText(accessKey) != hasText(secretKey);
    }

    public boolean pathStyleEnabled() {
        return Boolean.TRUE.equals(pathStyleAccessEnabled);
    }

    public boolean insecureEndpointAllowed() {
        return Boolean.TRUE.equals(allowInsecureEndpoint);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
