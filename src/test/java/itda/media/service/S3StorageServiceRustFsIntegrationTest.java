package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.properties.S3Properties;
import itda.media.storage.ObjectNotFoundException;
import itda.media.storage.s3.S3ObjectStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Verifies the RustFS S3 protocol used by MediaService completion HEAD checks. */
@Tag("rustfs")
@Testcontainers
class S3StorageServiceRustFsIntegrationTest {

    private static final String ACCESS_KEY = "rustfsadmin";
    private static final String SECRET_KEY = "rustfsadmin";
    private static final String BUCKET = "dogether-test";
    private static final int RUSTFS_PORT = 9000;

    @Container
    static GenericContainer<?> rustfs = new GenericContainer<>(
            DockerImageName.parse("rustfs/rustfs:1.0.0-alpha.72")
    )
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
            .withExposedPorts(RUSTFS_PORT)
            .waitingFor(Wait.forHttp("/health").forPort(RUSTFS_PORT).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(1));

    private static S3Client s3Client;
    private static S3Presigner s3Presigner;
    private static S3ObjectStorage storage;

    @BeforeAll
    static void setUp() {
        URI endpoint = URI.create("http://%s:%d".formatted(
                rustfs.getHost(), rustfs.getMappedPort(RUSTFS_PORT)
        ));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
        );
        S3Configuration configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration)
                .build();
        s3Presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration)
                .build();
        s3Client.createBucket(builder -> builder.bucket(BUCKET));
        storage = new S3ObjectStorage(s3Client, s3Presigner, new S3Properties(
                ACCESS_KEY, SECRET_KEY, BUCKET, Region.AP_NORTHEAST_2.id(), 60L,
                endpoint.toString(), endpoint.toString(), true, true
        ));
    }

    @AfterAll
    static void tearDown() {
        if (s3Presigner != null) {
            s3Presigner.close();
        }
        if (s3Client != null) {
            s3Client.close();
        }
    }

    @Test
    void presignedUploadThenHeadReturnsActualSizeAndMimeType() throws Exception {
        byte[] original = "rustfs-head-verification".getBytes(StandardCharsets.UTF_8);
        String objectKey = "media/test/" + UUID.randomUUID() + ".png";
        var upload = storage.presignPut(objectKey, "image/png", original.length, Duration.ofMinutes(1));

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.url()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(original));
        // HttpClient calculates Content-Length from the byte publisher and rejects
        // callers setting it explicitly, while the resulting value still matches
        // the signed request.
        upload.headers().forEach((name, value) -> {
            if (!"content-length".equalsIgnoreCase(name)) {
                request.header(name, value);
            }
        });
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                request.build(), HttpResponse.BodyHandlers.discarding()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(storage.head(objectKey))
                .extracting(metadata -> metadata.size(), metadata -> metadata.contentType())
                .containsExactly((long) original.length, "image/png");

        storage.deleteAllVersions(objectKey);
        assertThatThrownBy(() -> storage.head(objectKey))
                .isInstanceOf(ObjectNotFoundException.class);
    }
}
