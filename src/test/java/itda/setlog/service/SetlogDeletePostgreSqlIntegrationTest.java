package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.testcontainers.utility.DockerImageName;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration",
        "app.storage-cleanup.enabled=false"
})
class SetlogDeletePostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired private SetlogDeleteService service;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void deleteAtomicallyHidesSetlogAndMediaAndCreatesVersionedJob() {
        Fixture fixture = fixture();

        service.delete(fixture.userId(), fixture.setlogId());

        assertThat(jdbc.queryForObject("select status from setlogs where id = ?",
                String.class, fixture.setlogId())).isEqualTo("DELETED_BY_AUTHOR");
        assertThat(jdbc.queryForObject("select deleted_at is not null from media where id = ?",
                Boolean.class, fixture.mediaId())).isTrue();
        assertThat(jdbc.queryForMap("""
                select object_key, object_version_id, reason, status
                  from storage_delete_jobs where object_key = ?
                """, fixture.objectKey()))
                .containsEntry("object_key", fixture.objectKey())
                .containsEntry("object_version_id", "version-9")
                .containsEntry("reason", "SETLOG_DELETED")
                .containsEntry("status", "PENDING");
        Instant nextRetryAt = jdbc.queryForObject(
                "select next_retry_at from storage_delete_jobs where object_key = ?",
                java.sql.Timestamp.class, fixture.objectKey()).toInstant();
        assertThat(nextRetryAt).isAfter(fixture.expiresAt());
        assertThat(jdbc.queryForObject("""
                select count(*) from setlogs setlog
                  join media on media.id = setlog.media_id
                 where setlog.id = ? and setlog.status = 'VISIBLE'
                   and media.deleted_at is null
                """, Long.class, fixture.setlogId())).isZero();
    }

    private Fixture fixture() {
        jdbc.update("""
                insert into neighborhoods (code, sido_name, sigungu_name, eupmyeondong_name)
                values ('4113111500', '경기도', '성남시', '수내동')
                on conflict (code) do nothing
                """);
        String unique = UUID.randomUUID().toString().replace("-", "");
        Long userId = jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code)
                values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500')
                returning id
                """, Long.class, unique + "@example.com", "보호자#" + unique.substring(0, 8));
        Long petId = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, '반려견', 'ACTIVE') returning id
                """, Long.class, userId, "반려견#" + unique.substring(0, 4).toUpperCase());
        String objectKey = "setlogs/%d/%s.mp4".formatted(userId, UUID.randomUUID());
        Long mediaId = jdbc.queryForObject("""
                insert into media (media_type, path, status, user_id, file_size,
                    content_type, object_version_id)
                values ('VIDEO', ?, 'COMPLETED', ?, 1024, 'video/mp4', 'version-9')
                returning id
                """, Long.class, objectKey, userId);
        Long setlogId = jdbc.queryForObject("""
                insert into setlogs (author_pet_id, media_id, status,
                    reaction_cute_count, reaction_like_count, is_seed)
                values (?, ?, 'VISIBLE', 0, 0, false) returning id
                """, Long.class, petId, mediaId);
        UUID uploadId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into setlog_uploads (id, owner_user_id, pet_id, object_key,
                    content_type, expected_size, status, expires_at, completed_at,
                    completion_request_id, media_id, setlog_id)
                values (?, ?, ?, ?, 'video/mp4', 1024, 'COMPLETED', ?, ?, ?, ?, ?)
                """, uploadId, userId, petId, objectKey,
                java.sql.Timestamp.from(now.minusSeconds(1)),
                java.sql.Timestamp.from(now.minusSeconds(2)), UUID.randomUUID(), mediaId, setlogId);
        return new Fixture(userId, mediaId, setlogId, objectKey, now.minusSeconds(1));
    }

    private record Fixture(Long userId, Long mediaId, Long setlogId, String objectKey,
                           Instant expiresAt) {}
}
