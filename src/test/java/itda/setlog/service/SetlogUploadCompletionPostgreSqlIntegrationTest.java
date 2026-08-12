package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.common.constants.ErrorCode;
import itda.media.storage.ObjectMetadata;
import itda.setlog.domain.SetlogUploadStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration",
        "app.setlog-upload.require-version-id=true"
})
class SetlogUploadCompletionPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private SetlogUploadCompletionTransactionService transactions;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void finalizePersistsExactlyOneMediaUserSetlogAndCompleteLinks() {
        Fixture fixture = createFixture(Instant.now().plusSeconds(900));
        UUID requestId = UUID.randomUUID();
        Instant verifiedAt = Instant.now();

        var attempt = transactions.finalizeUpload(
                fixture.userId(), fixture.uploadId(), requestId,
                metadata("version-7"), verifiedAt
        );

        assertThat(attempt.failure()).isNull();
        assertThat(attempt.completed().replayed()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from media where user_id = ? and object_version_id = 'version-7'",
                Long.class, fixture.userId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from setlogs where author_pet_id = ? and is_seed = false",
                Long.class, fixture.petId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select status from setlog_uploads where id = ?",
                String.class, fixture.uploadId())).isEqualTo("COMPLETED");
    }

    @Test
    void mismatchIsCommittedAsRejectedWithoutMediaOrSetlog() {
        Fixture fixture = createFixture(Instant.now().plusSeconds(900));

        var attempt = transactions.finalizeUpload(
                fixture.userId(), fixture.uploadId(), UUID.randomUUID(),
                new ObjectMetadata(1025L, "video/mp4", "etag", Instant.now(), "version-7"),
                Instant.now()
        );

        assertThat(attempt.failure()).isEqualTo(ErrorCode.SETLOG_UPLOAD_METADATA_MISMATCH);
        assertThat(jdbcTemplate.queryForObject(
                "select status from setlog_uploads where id = ?",
                String.class, fixture.uploadId())).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from media where path = ?",
                Long.class, fixture.objectKey())).isZero();
    }

    @Test
    void missingVersionFailsClosedWithoutRejectingSessionOrCreatingArtifacts() {
        Fixture fixture = createFixture(Instant.now().plusSeconds(900));

        var attempt = transactions.finalizeUpload(
                fixture.userId(), fixture.uploadId(), UUID.randomUUID(),
                metadata(" "), Instant.now());

        assertThat(attempt.failure()).isEqualTo(ErrorCode.SETLOG_UPLOAD_VERSIONING_UNAVAILABLE);
        assertThat(jdbcTemplate.queryForObject(
                "select status from setlog_uploads where id = ?",
                String.class, fixture.uploadId())).isEqualTo("PRESIGNED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from media where path = ?",
                Long.class, fixture.objectKey())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from setlogs where author_pet_id = ? and is_seed = false",
                Long.class, fixture.petId())).isZero();
    }

    @Test
    void exactExpiryIsCommittedAndDoesNotCreateArtifacts() {
        Instant expiresAt = Instant.now().minusSeconds(1);
        Fixture fixture = createFixture(expiresAt);

        var prepared = transactions.prepare(
                fixture.userId(), fixture.uploadId(), UUID.randomUUID(), Instant.now()
        );

        assertThat(prepared.failure()).isEqualTo(ErrorCode.SETLOG_UPLOAD_EXPIRED);
        assertThat(jdbcTemplate.queryForObject(
                "select status from setlog_uploads where id = ?",
                String.class, fixture.uploadId())).isEqualTo("EXPIRED");
    }

    @Test
    void databaseCompletionCheckRejectsLinksOnNonCompletedStatus() {
        Fixture fixture = createFixture(Instant.now().plusSeconds(900));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbcTemplate.update("""
                update setlog_uploads
                   set completion_request_id = ?
                 where id = ?
                """, UUID.randomUUID(), fixture.uploadId())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void concurrentSameRequestCreatesOneMediaAndSetlogThenReplays() throws Exception {
        Fixture fixture = createFixture(Instant.now().plusSeconds(900));
        UUID requestId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<SetlogUploadCompletionTransactionService.CompletionAttempt> first =
                    CompletableFuture.supplyAsync(() -> transactions.finalizeUpload(
                            fixture.userId(), fixture.uploadId(), requestId,
                            metadata("version-7"), Instant.now()), executor);
            CompletableFuture<SetlogUploadCompletionTransactionService.CompletionAttempt> second =
                    CompletableFuture.supplyAsync(() -> transactions.finalizeUpload(
                            fixture.userId(), fixture.uploadId(), requestId,
                            metadata("version-7"), Instant.now()), executor);

            var attempts = java.util.List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(attempts).extracting(attempt -> attempt.completed().replayed())
                    .containsExactlyInAnyOrder(false, true);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from media where path = ?",
                    Long.class, fixture.objectKey())).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from setlogs where author_pet_id = ? and is_seed = false",
                    Long.class, fixture.petId())).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentDifferentRequestsCreateOneSetlogAndConflictTheOther() throws Exception {
        Fixture fixture = createFixture(Instant.now().plusSeconds(900));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.function.Function<UUID, CompletableFuture<Object>> submit = requestId ->
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            start.await(5, TimeUnit.SECONDS);
                            return transactions.finalizeUpload(
                                    fixture.userId(), fixture.uploadId(), requestId,
                                    metadata("version-7"), Instant.now());
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(interrupted);
                        } catch (itda.common.exception.BusinessException conflict) {
                            return conflict.getErrorCode();
                        }
                    }, executor);
            CompletableFuture<Object> first = submit.apply(UUID.randomUUID());
            CompletableFuture<Object> second = submit.apply(UUID.randomUUID());
            start.countDown();

            java.util.List<Object> results = java.util.List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results.stream()
                    .filter(SetlogUploadCompletionTransactionService.CompletionAttempt.class::isInstance)
                    .count()).isEqualTo(1L);
            assertThat(results).contains(ErrorCode.SETLOG_UPLOAD_IDEMPOTENCY_CONFLICT);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from media where path = ?",
                    Long.class, fixture.objectKey())).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from setlogs where author_pet_id = ? and is_seed = false",
                    Long.class, fixture.petId())).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ObjectMetadata metadata(String versionId) {
        return new ObjectMetadata(
                1024L, "video/mp4", "etag-7", Instant.now(), versionId
        );
    }

    private Fixture createFixture(Instant expiresAt) {
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
        Long petId = jdbcTemplate.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, '반려견', 'ACTIVE') returning id
                """, Long.class, userId, "반려견#" + unique.substring(0, 4).toUpperCase());
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", petId, userId);
        UUID uploadId = UUID.randomUUID();
        String objectKey = "setlogs/%d/%d/%s.mp4".formatted(userId, petId, uploadId);
        jdbcTemplate.update("""
                insert into setlog_uploads (
                    id, owner_user_id, pet_id, object_key, content_type,
                    expected_size, status, expires_at
                ) values (?, ?, ?, ?, 'video/mp4', 1024, 'PRESIGNED', ?)
                """, uploadId, userId, petId, objectKey,
                java.sql.Timestamp.from(expiresAt));
        return new Fixture(userId, petId, uploadId, objectKey);
    }

    private record Fixture(Long userId, Long petId, UUID uploadId, String objectKey) {
    }
}
