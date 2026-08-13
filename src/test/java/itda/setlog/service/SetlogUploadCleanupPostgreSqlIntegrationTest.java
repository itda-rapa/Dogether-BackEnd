package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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
        "app.storage-cleanup.enabled=false",
        "app.storage-cleanup.max-upload-duration=1m",
        "app.storage-cleanup.upload-settle-grace=2m"
})
class SetlogUploadCleanupPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private SetlogUploadCleanupService cleanup;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void expiredPresignedTransitionsAndCreatesGraceDelayedOutboxInSameTransaction() {
        Fixture fixture = fixture(Instant.now().minusSeconds(1));

        assertThat(cleanup.enqueueExpired(100)).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject("select status from setlog_uploads where id = ?",
                String.class, fixture.uploadId())).isEqualTo("EXPIRED");
        Timestamp due = jdbc.queryForObject(
                "select next_retry_at from storage_delete_jobs where object_key = ?",
                Timestamp.class, fixture.objectKey());
        assertThat(due.toInstant()).isCloseTo(fixture.expiresAt().plusSeconds(120),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void outboxInsertFailureRollsBackPresignedTransition() {
        Fixture fixture = fixture(Instant.now().minusSeconds(1));
        jdbc.execute("""
                create or replace function reject_cleanup_job() returns trigger language plpgsql as $$
                begin
                  if new.object_key like 'rollback/%' then
                    raise exception 'forced outbox failure';
                  end if;
                  return new;
                end $$
                """);
        jdbc.execute("""
                create trigger reject_cleanup_job before insert on storage_delete_jobs
                for each row execute function reject_cleanup_job()
                """);
        jdbc.update("update setlog_uploads set object_key = ? where id = ?",
                "rollback/" + fixture.uploadId() + ".mp4", fixture.uploadId());

        assertThatThrownBy(() -> cleanup.enqueueExpired(100)).isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("select status from setlog_uploads where id = ?",
                String.class, fixture.uploadId())).isEqualTo("PRESIGNED");
        jdbc.execute("drop trigger reject_cleanup_job on storage_delete_jobs");
        jdbc.execute("drop function reject_cleanup_job()");
    }

    @Test
    void completedUploadEnqueuesSurplusCleanupWithOnlyVerifiedVersionRetained() {
        Fixture fixture = fixture(Instant.now().minusSeconds(121));
        Instant completedAt = Instant.now().minusSeconds(180);
        Long mediaId = jdbc.queryForObject("""
                insert into media (media_type, path, status, user_id, file_size,
                    content_type, object_version_id)
                select 'VIDEO', object_key, 'COMPLETED', owner_user_id, expected_size,
                       content_type, 'A-verified'
                  from setlog_uploads where id = ? returning id
                """, Long.class, fixture.uploadId());
        Long setlogId = jdbc.queryForObject("""
                insert into setlogs (author_pet_id, media_id, status,
                    reaction_cute_count, reaction_like_count, is_seed)
                select pet_id, ?, 'VISIBLE', 0, 0, false
                  from setlog_uploads where id = ? returning id
                """, Long.class, mediaId, fixture.uploadId());
        jdbc.update("""
                update setlog_uploads
                   set status='COMPLETED', completed_at=?, completion_request_id=?,
                       media_id=?, setlog_id=?
                 where id=?
                """, Timestamp.from(completedAt), UUID.randomUUID(), mediaId, setlogId,
                fixture.uploadId());

        assertThat(cleanup.enqueueExpired(100)).isGreaterThanOrEqualTo(1);

        var job = jdbc.queryForMap("""
                select object_version_id, reason from storage_delete_jobs
                 where object_key=? and reason='UPLOAD_SURPLUS_VERSIONS'
                """, fixture.objectKey());
        assertThat(job)
                .containsEntry("object_version_id", "A-verified")
                .containsEntry("reason", "UPLOAD_SURPLUS_VERSIONS");
        assertThat(jdbc.queryForObject("select status from setlog_uploads where id=?",
                String.class, fixture.uploadId())).isEqualTo("COMPLETED");
    }

    @Test
    void repositoryExcludesVersionlessCompletedButStillSelectsExpiredCandidate() {
        Fixture completed = fixture(Instant.now().minusSeconds(121));
        Long mediaId = jdbc.queryForObject("""
                insert into media (media_type, path, status, user_id, file_size, content_type)
                select 'VIDEO', object_key, 'COMPLETED', owner_user_id, expected_size, content_type
                  from setlog_uploads where id=? returning id
                """, Long.class, completed.uploadId());
        Long setlogId = jdbc.queryForObject("""
                insert into setlogs (author_pet_id, media_id, status,
                    reaction_cute_count, reaction_like_count, is_seed)
                select pet_id, ?, 'VISIBLE', 0, 0, false from setlog_uploads where id=? returning id
                """, Long.class, mediaId, completed.uploadId());
        jdbc.update("""
                update setlog_uploads set status='COMPLETED', completed_at=?,
                    completion_request_id=?, media_id=?, setlog_id=? where id=?
                """, Timestamp.from(Instant.now().minusSeconds(180)), UUID.randomUUID(),
                mediaId, setlogId, completed.uploadId());
        Fixture expired = fixture(Instant.now().minusSeconds(121));
        jdbc.update("update setlog_uploads set status='EXPIRED' where id=?", expired.uploadId());

        assertThat(cleanup.enqueueExpired(100)).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject("""
                select count(*) from storage_delete_jobs
                 where object_key=? and reason='UPLOAD_SURPLUS_VERSIONS'
                """, Long.class, completed.objectKey())).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from storage_delete_jobs
                 where object_key=? and reason='UPLOAD_EXPIRED'
                """, Long.class, expired.objectKey())).isEqualTo(1L);
    }

    private Fixture fixture(Instant expiresAt) {
        jdbc.update("""
                insert into neighborhoods (code, sido_name, sigungu_name, eupmyeondong_name)
                values ('4113111500', '경기도', '성남시', '수내동')
                on conflict (code) do nothing
                """);
        String unique = UUID.randomUUID().toString().replace("-", "");
        Long userId = jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code)
                values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500') returning id
                """, Long.class, unique + "@example.com", "보호자#" + unique.substring(0, 8));
        Long petId = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, '반려견', 'ACTIVE') returning id
                """, Long.class, userId, "반려견#" + unique.substring(0, 4).toUpperCase());
        UUID uploadId = UUID.randomUUID();
        String key = "setlogs/cleanup/" + uploadId + ".mp4";
        jdbc.update("""
                insert into setlog_uploads (id, owner_user_id, pet_id, object_key,
                    content_type, expected_size, status, expires_at)
                values (?, ?, ?, ?, 'video/mp4', 1024, 'PRESIGNED', ?)
                """, uploadId, userId, petId, key, Timestamp.from(expiresAt));
        return new Fixture(uploadId, key, expiresAt);
    }

    private record Fixture(UUID uploadId, String objectKey, Instant expiresAt) {}
}
