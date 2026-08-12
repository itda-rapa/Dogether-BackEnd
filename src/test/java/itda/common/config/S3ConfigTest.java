package itda.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.properties.S3Properties;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

class S3ConfigTest {

    @Test
    void awsDefaultsDoNotOverrideEndpointOrEnablePathStyle() {
        S3Config config = new S3Config(new S3Properties(
                "test-access",
                "test-secret",
                "dogether-prod",
                "ap-northeast-2",
                600L,
                null,
                null,
                false
        ));

        try (var client = config.s3Client(); var presigner = config.s3Presigner()) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
            URI signedUrl = presignGet(presigner, "dogether-prod", "sample.mp4");
            assertThat(signedUrl.getHost())
                    .isEqualTo("dogether-prod.s3.ap-northeast-2.amazonaws.com");
            assertThat(signedUrl.getPath()).isEqualTo("/sample.mp4");
        }
    }

    @Test
    void rustFsUsesSeparateInternalAndExternalEndpointsWithPathStyle() {
        URI internalEndpoint = URI.create("http://rustfs:9000");
        URI externalEndpoint = URI.create("http://localhost:9000");
        S3Config config = new S3Config(new S3Properties(
                "rustfsadmin",
                "rustfsadmin",
                "dogether-local",
                "ap-northeast-2",
                600L,
                internalEndpoint.toString(),
                externalEndpoint.toString(),
                true,
                true
        ));

        try (var client = config.s3Client(); var presigner = config.s3Presigner()) {
            assertThat(client.serviceClientConfiguration().endpointOverride())
                    .contains(internalEndpoint);
            URI signedUrl = presignGet(presigner, "dogether-local", "sample.mp4");
            assertThat(signedUrl.getScheme()).isEqualTo("http");
            assertThat(signedUrl.getHost()).isEqualTo("localhost");
            assertThat(signedUrl.getPort()).isEqualTo(9000);
            assertThat(signedUrl.getPath()).isEqualTo("/dogether-local/sample.mp4");
        }
    }

    @Test
    void presignerFallsBackToStorageEndpoint() {
        URI endpoint = URI.create("http://localhost:9000");
        S3Config config = new S3Config(new S3Properties(
                "access",
                "secret",
                "dogether-local",
                "ap-northeast-2",
                600L,
                endpoint.toString(),
                null,
                true,
                true
        ));

        try (var presigner = config.s3Presigner()) {
            URI signedUrl = presignGet(presigner, "dogether-local", "sample.mp4");
            assertThat(signedUrl.getHost()).isEqualTo(endpoint.getHost());
            assertThat(signedUrl.getPort()).isEqualTo(endpoint.getPort());
        }
    }

    @Test
    void noStaticCredentialsCanBuildClientsForDefaultCredentialChain() {
        S3Config config = new S3Config(properties(
                null, null, null, null, false));

        assertThatCode(() -> {
            try (var client = config.s3Client(); var presigner = config.s3Presigner()) {
                assertThat(client).isNotNull();
                assertThat(presigner).isNotNull();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void partialStaticCredentialsAreRejected() {
        S3Config accessOnly = new S3Config(properties(
                "access", null, null, null, false));
        S3Config secretOnly = new S3Config(properties(
                null, "secret", null, null, false));

        assertThatThrownBy(accessOnly::s3Client)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
        assertThatThrownBy(secretOnly::s3Presigner)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-uri",
            "ftp://storage.example.com",
            "://bad",
            "https:/storage"
    })
    void malformedOrNonHttpEndpointsAreRejected(String endpoint) {
        S3Config config = new S3Config(properties(
                "access", "secret", endpoint, null, true));

        assertThatThrownBy(config::s3Client)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.s3.endpoint");
    }

    @Test
    void httpEndpointRequiresExplicitOptIn() {
        S3Config config = new S3Config(properties(
                "access", "secret", "http://localhost:9000", null, false));

        assertThatThrownBy(config::s3Client)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-insecure-endpoint=true");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:password@storage.example.com",
            "https://storage.example.com?token=secret",
            "https://storage.example.com#fragment"
    })
    void endpointRejectsUserInfoQueryAndFragment(String endpoint) {
        S3Config config = new S3Config(properties(
                "access", "secret", endpoint, null, false));

        assertThatThrownBy(config::s3Client)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not contain");
    }

    @Test
    void configurationPropertiesBindAndValidate() {
        new ApplicationContextRunner()
                .withUserConfiguration(S3PropertiesTestConfiguration.class)
                .withPropertyValues(
                        "app.s3.bucket=dogether-test",
                        "app.s3.region=ap-northeast-2",
                        "app.s3.presigned-url-expiration-seconds=600",
                        "app.s3.endpoint=http://localhost:9000",
                        "app.s3.path-style-access-enabled=true",
                        "app.s3.allow-insecure-endpoint=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    S3Properties bound = context.getBean(S3Properties.class);
                    assertThat(bound.bucket()).isEqualTo("dogether-test");
                    assertThat(bound.endpoint()).isEqualTo("http://localhost:9000");
                    assertThat(bound.pathStyleEnabled()).isTrue();
                    assertThat(bound.insecureEndpointAllowed()).isTrue();
                });

        new ApplicationContextRunner()
                .withUserConfiguration(S3PropertiesTestConfiguration.class)
                .withPropertyValues(
                        "app.s3.bucket= ",
                        "app.s3.region=ap-northeast-2",
                        "app.s3.presigned-url-expiration-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void presignedExpirationAllowsSevenDaysAndRejectsOneSecondMore() {
        new ApplicationContextRunner()
                .withUserConfiguration(S3PropertiesTestConfiguration.class)
                .withPropertyValues(
                        "app.s3.bucket=dogether-test",
                        "app.s3.region=ap-northeast-2",
                        "app.s3.presigned-url-expiration-seconds=604800")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(S3Properties.class)
                            .presignedUrlExpirationSeconds()).isEqualTo(604800L);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(S3PropertiesTestConfiguration.class)
                .withPropertyValues(
                        "app.s3.bucket=dogether-test",
                        "app.s3.region=ap-northeast-2",
                        "app.s3.presigned-url-expiration-seconds=604801")
                .run(context -> assertThat(context).hasFailed());
    }

    private static S3Properties properties(
            String accessKey,
            String secretKey,
            String endpoint,
            String presignEndpoint,
            boolean allowInsecure
    ) {
        return new S3Properties(
                accessKey, secretKey, "bucket", "ap-northeast-2", 600L,
                endpoint, presignEndpoint, true, allowInsecure);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(S3Properties.class)
    static class S3PropertiesTestConfiguration {
    }

    private static URI presignGet(
            software.amazon.awssdk.services.s3.presigner.S3Presigner presigner,
            String bucket,
            String key
    ) {
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .build())
                        .build())
                .url()
                .toString());
    }
}
