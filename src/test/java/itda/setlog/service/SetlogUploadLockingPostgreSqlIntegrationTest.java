package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import itda.media.storage.ObjectStorage;
import itda.media.storage.PresignedUpload;
import itda.pet.service.ActivePetSelectionService;
import itda.setlog.dto.SetlogUploadCreateRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
class SetlogUploadLockingPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired private SetlogUploadSessionService uploadService;
    @Autowired private ActivePetSelectionService activePetSelectionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ObjectStorage objectStorage;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdownExecutor() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void uploadAndActivePetChangeSerializeOnUserThenPetLocks() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch uploadReachedPresigner = new CountDownLatch(1);
        CountDownLatch allowUploadToFinish = new CountDownLatch(1);
        CountDownLatch selectionStarted = new CountDownLatch(1);
        Instant expiresAt = Instant.parse("2026-08-12T01:15:00Z");
        given(objectStorage.presignPut(any(), any(), any(Long.class), any()))
                .willAnswer(invocation -> {
                    uploadReachedPresigner.countDown();
                    if (!allowUploadToFinish.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("upload release latch timed out");
                    }
                    return new PresignedUpload(
                            "https://storage.example/upload",
                            Map.of("Content-Type", "video/mp4"),
                            expiresAt
                    );
                });

        CompletableFuture<Void> upload = CompletableFuture.runAsync(() ->
                uploadService.create(
                        fixture.userId(),
                        new SetlogUploadCreateRequest(
                                fixture.firstPetId(), "walk.mp4", "video/mp4", 1024L
                        )
                ), executor);
        assertThat(uploadReachedPresigner.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> selection = CompletableFuture.runAsync(() -> {
            selectionStarted.countDown();
            activePetSelectionService.selectActivePet(fixture.userId(), fixture.secondPetId());
        }, executor);
        assertThat(selectionStarted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> selection.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        allowUploadToFinish.countDown();
        upload.get(10, TimeUnit.SECONDS);
        selection.get(10, TimeUnit.SECONDS);

        Long persistedUploadPet = jdbcTemplate.queryForObject(
                "select pet_id from setlog_uploads where owner_user_id = ?",
                Long.class,
                fixture.userId()
        );
        Long finalActivePet = jdbcTemplate.queryForObject(
                "select active_pet_id from users where id = ?",
                Long.class,
                fixture.userId()
        );
        assertThat(persistedUploadPet).isEqualTo(fixture.firstPetId());
        assertThat(finalActivePet).isEqualTo(fixture.secondPetId());
    }

    private Fixture createFixture() {
        jdbcTemplate.update("""
                insert into neighborhoods (code, sido_name, sigungu_name, eupmyeondong_name)
                values ('4113111500', '경기도', '성남시', '수내동')
                on conflict (code) do nothing
                """);
        String unique = UUID.randomUUID().toString().replace("-", "");
        Long userId = jdbcTemplate.queryForObject("""
                insert into users (
                    email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code
                ) values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500')
                returning id
                """, Long.class, unique + "@example.com", "보호자#" + unique.substring(0, 8));
        Long firstPetId = createPet(userId, unique.substring(0, 4));
        Long secondPetId = createPet(userId, unique.substring(4, 8));
        jdbcTemplate.update(
                "update users set active_pet_id = ? where id = ?",
                firstPetId,
                userId
        );
        return new Fixture(userId, firstPetId, secondPetId);
    }

    private Long createPet(Long userId, String tag) {
        return jdbcTemplate.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, '반려견', 'ACTIVE') returning id
                """, Long.class, userId, "반려견#" + tag.toUpperCase());
    }

    private record Fixture(Long userId, Long firstPetId, Long secondPetId) {
    }
}
