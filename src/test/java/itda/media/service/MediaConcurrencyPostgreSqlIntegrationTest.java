package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

import itda.media.old.domain.MediaAsset;
import itda.media.old.domain.MediaPurpose;
import itda.media.domain.MediaStatus;
import itda.media.old.repository.MediaAssetRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class MediaConcurrencyPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private S3StorageService storageService;

    @Test
    void mediaCompleteAndDeleteAreSerializedByRowLock() throws Exception {
        MediaAsset mediaAsset = createPendingMedia(
                Instant.now().plus(Duration.ofMinutes(10))
        );
        Long userId = mediaAsset.getOwner().getId();
        Long mediaAssetId = mediaAsset.getId();
        CountDownLatch headStarted = new CountDownLatch(1);
        CountDownLatch releaseHead = new CountDownLatch(1);
        CountDownLatch deletionStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        reset(storageService);
        given(storageService.head(anyString())).willAnswer(invocation -> {
            headStarted.countDown();
            if (!releaseHead.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("S3 head release timed out");
            }
            return HeadObjectResponse.builder()
                    .contentLength(1024L)
                    .contentType("image/jpeg")
                    .build();
        });
        given(storageService.createViewUrl(
                anyString(),
                any(Duration.class)
        )).willReturn("https://example.test/view");

        try {
            Future<?> completeFuture = executor.submit(
                    () -> mediaService.complete(userId, mediaAssetId)
            );
            assertThat(headStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> deleteFuture = executor.submit(() -> {
                deletionStarted.countDown();
                mediaService.requestDeletion(userId, mediaAssetId);
            });
            assertThat(deletionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() ->
                    deleteFuture.get(250, TimeUnit.MILLISECONDS)
            ).isInstanceOf(TimeoutException.class);

            releaseHead.countDown();
            completeFuture.get(5, TimeUnit.SECONDS);
            deleteFuture.get(5, TimeUnit.SECONDS);

            assertThat(mediaAssetRepository.findById(mediaAssetId))
                    .get()
                    .extracting(MediaAsset::getStatus)
                    .isEqualTo(MediaStatus.DELETE_REQUESTED);
        } finally {
            releaseHead.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void expiryBatchRechecksStatusAfterWaitingForRowLock() throws Exception {
        MediaAsset mediaAsset = createPendingMedia(
                Instant.now().minus(Duration.ofMinutes(1))
        );
        Long mediaAssetId = mediaAsset.getId();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseUploader = new CountDownLatch(1);
        CountDownLatch batchStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> uploadFuture = executor.submit(() ->
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {
                                MediaAsset locked = mediaAssetRepository
                                        .findByIdForUpdate(mediaAssetId)
                                        .orElseThrow();
                                rowLocked.countDown();
                                await(releaseUploader);
                                locked.markUploaded();
                            })
            );
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<List<MediaAsset>> batchFuture = executor.submit(() -> {
                batchStarted.countDown();
                return new TransactionTemplate(transactionManager).execute(
                        status -> mediaAssetRepository
                                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                                        MediaStatus.PENDING,
                                        Instant.now()
                                )
                );
            });
            assertThat(batchStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() ->
                    batchFuture.get(250, TimeUnit.MILLISECONDS)
            ).isInstanceOf(TimeoutException.class);

            releaseUploader.countDown();
            uploadFuture.get(5, TimeUnit.SECONDS);
            List<MediaAsset> batchSelection =
                    batchFuture.get(5, TimeUnit.SECONDS);

            assertThat(batchSelection)
                    .noneMatch(asset -> asset.getId().equals(mediaAssetId));
            assertThat(mediaAssetRepository.findById(mediaAssetId))
                    .get()
                    .extracting(MediaAsset::getStatus)
                    .isEqualTo(MediaStatus.UPLOADED);
        } finally {
            releaseUploader.countDown();
            executor.shutdownNow();
        }
    }

    private MediaAsset createPendingMedia(Instant expiresAt) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        User user = userRepository.saveAndFlush(User.register(
                unique + "@example.com",
                "encoded",
                "사용자",
                "사용자#" + unique.substring(0, 8),
                "4113111500"
        ));
        return mediaAssetRepository.saveAndFlush(MediaAsset.pending(
                user,
                MediaPurpose.PROFILE,
                "media/test/" + unique,
                "image/jpeg",
                1024L,
                expiresAt
        ));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
    }
}
