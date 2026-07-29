//package itda.media.service;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.assertj.core.api.Assertions.assertThatThrownBy;
//
//import itda.common.properties.S3Properties;
//import java.net.URI;
//import java.net.http.HttpClient;
//import java.net.http.HttpRequest;
//import java.net.http.HttpResponse;
//import java.nio.charset.StandardCharsets;
//import java.time.Duration;
//import java.util.UUID;
//import org.junit.jupiter.api.AfterAll;
//import org.junit.jupiter.api.BeforeAll;
//import org.junit.jupiter.api.Tag;
//import org.junit.jupiter.api.Test;
//import org.testcontainers.containers.GenericContainer;
//import org.testcontainers.containers.wait.strategy.Wait;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//import org.testcontainers.utility.DockerImageName;
//import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
//import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
//import software.amazon.awssdk.regions.Region;
//import software.amazon.awssdk.services.s3.S3Client;
//import software.amazon.awssdk.services.s3.S3Configuration;
//import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
//import software.amazon.awssdk.services.s3.model.S3Exception;
//import software.amazon.awssdk.services.s3.presigner.S3Presigner;
//
//@Tag("rustfs")
//@Testcontainers
//class S3StorageServiceRustFsIntegrationTest {
//
//    private static final String ACCESS_KEY = "rustfsadmin";
//    private static final String SECRET_KEY = "rustfsadmin";
//    private static final String BUCKET = "dogether-test";
//    private static final int RUSTFS_PORT = 9000;
//
//    @Container
//    static GenericContainer<?> rustfs = new GenericContainer<>(
//            DockerImageName.parse("rustfs/rustfs:1.0.0-alpha.72")
//    )
//            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
//            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
//            .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
//            .withExposedPorts(RUSTFS_PORT)
//            .waitingFor(
//                    Wait.forHttp("/health")
//                            .forPort(RUSTFS_PORT)
//                            .forStatusCode(200)
//            )
//            .withStartupTimeout(Duration.ofMinutes(1));
//
//    private static S3Client s3Client;
//    private static S3Presigner s3Presigner;
//    private static S3StorageService storageService;
//
//    @BeforeAll
//    static void setUp() {
//        URI endpoint = URI.create(
//                "http://%s:%d".formatted(
//                        rustfs.getHost(),
//                        rustfs.getMappedPort(RUSTFS_PORT)
//                )
//        );
//        StaticCredentialsProvider credentials =
//                StaticCredentialsProvider.create(
//                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
//                );
//        S3Configuration s3Configuration = S3Configuration.builder()
//                .pathStyleAccessEnabled(true)
//                .build();
//
//        s3Client = S3Client.builder()
//                .endpointOverride(endpoint)
//                .region(Region.AP_NORTHEAST_2)
//                .credentialsProvider(credentials)
//                .serviceConfiguration(s3Configuration)
//                .build();
//        s3Presigner = S3Presigner.builder()
//                .endpointOverride(endpoint)
//                .region(Region.AP_NORTHEAST_2)
//                .credentialsProvider(credentials)
//                .serviceConfiguration(s3Configuration)
//                .build();
//
//        s3Client.createBucket(builder -> builder.bucket(BUCKET));
//
//        S3Properties properties = new S3Properties(
//                BUCKET,
//                Region.AP_NORTHEAST_2.id(),
//                endpoint.toString(),
//                endpoint.toString(),
//                true
//        );
//        storageService = new S3StorageService(
//                s3Client,
//                s3Presigner,
//                properties
//        );
//    }
//
//    @AfterAll
//    static void tearDown() {
//        if (s3Presigner != null) {
//            s3Presigner.close();
//        }
//        if (s3Client != null) {
//            s3Client.close();
//        }
//    }
//
//    @Test
//    void presignedUploadHeadDownloadAndDeleteWork() throws Exception {
//        byte[] original = "rustfs-integration-test"
//                .getBytes(StandardCharsets.UTF_8);
//        String objectKey = "media/test/" + UUID.randomUUID();
//        HttpClient httpClient = HttpClient.newHttpClient();
//
//        String uploadUrl = storageService.createUploadUrl(
//                objectKey,
//                "image/png",
//                original.length,
//                Duration.ofMinutes(1)
//        );
//        HttpResponse<Void> uploadResponse = httpClient.send(
//                HttpRequest.newBuilder(URI.create(uploadUrl))
//                        .header("Content-Type", "image/png")
//                        .PUT(HttpRequest.BodyPublishers.ofByteArray(original))
//                        .build(),
//                HttpResponse.BodyHandlers.discarding()
//        );
//
//        assertThat(uploadResponse.statusCode()).isEqualTo(200);
//
//        HeadObjectResponse head = storageService.head(objectKey);
//
//        assertThat(head.contentLength()).isEqualTo(original.length);
//        assertThat(head.contentType()).isEqualTo("image/png");
//
//        String viewUrl = storageService.createViewUrl(
//                objectKey,
//                Duration.ofMinutes(1)
//        );
//        HttpResponse<byte[]> downloadResponse = httpClient.send(
//                HttpRequest.newBuilder(URI.create(viewUrl))
//                        .GET()
//                        .build(),
//                HttpResponse.BodyHandlers.ofByteArray()
//        );
//
//        assertThat(downloadResponse.statusCode()).isEqualTo(200);
//        assertThat(downloadResponse.body()).isEqualTo(original);
//
//        storageService.delete(objectKey);
//
//        assertThatThrownBy(() -> storageService.head(objectKey))
//                .isInstanceOf(S3Exception.class)
//                .satisfies(exception ->
//                        assertThat(((S3Exception) exception).statusCode())
//                                .isEqualTo(404)
//                );
//    }
//}
