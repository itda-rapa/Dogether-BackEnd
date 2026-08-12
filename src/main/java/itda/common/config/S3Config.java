package itda.common.config;

import itda.common.properties.S3Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@RequiredArgsConstructor
public class S3Config {
    private final S3Properties s3Properties;

    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(s3Properties.region()))
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(serviceConfiguration());
        if (hasText(s3Properties.endpoint())) {
            builder.endpointOverride(endpointUri(s3Properties.endpoint(), "endpoint"));
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .region(Region.of(s3Properties.region()))
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(serviceConfiguration());
        String presignEndpoint = hasText(s3Properties.presignEndpoint())
                ? s3Properties.presignEndpoint()
                : s3Properties.endpoint();
        if (hasText(presignEndpoint)) {
            builder.endpointOverride(endpointUri(presignEndpoint, "presign-endpoint"));
        }
        return builder.build();
    }

    private AwsCredentialsProvider credentialsProvider() {
        if (s3Properties.hasPartialStaticCredentials()) {
            throw new IllegalStateException(
                    "AWS S3 access-key and secret-key must be configured together");
        }
        if (s3Properties.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    s3Properties.accessKey(), s3Properties.secretKey()));
        }
        return DefaultCredentialsProvider.create();
    }

    private S3Configuration serviceConfiguration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(s3Properties.pathStyleEnabled())
                .build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private URI endpointUri(String value, String propertyName) {
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid app.s3." + propertyName + " URI", exception);
        }
        if (!endpoint.isAbsolute()
                || (!("http".equalsIgnoreCase(endpoint.getScheme()))
                && !("https".equalsIgnoreCase(endpoint.getScheme())))) {
            throw new IllegalStateException(
                    "app.s3." + propertyName + " must be an absolute HTTP(S) URI");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalStateException(
                    "app.s3." + propertyName + " must include a host");
        }
        if (endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalStateException(
                    "app.s3." + propertyName + " must not contain user-info, query, or fragment");
        }
        if ("http".equalsIgnoreCase(endpoint.getScheme())
                && !s3Properties.insecureEndpointAllowed()) {
            throw new IllegalStateException(
                    "app.s3." + propertyName
                            + " requires app.s3.allow-insecure-endpoint=true for HTTP");
        }
        return endpoint;
    }
}
