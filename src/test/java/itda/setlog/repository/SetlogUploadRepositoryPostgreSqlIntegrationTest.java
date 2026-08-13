package itda.setlog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.setlog.domain.SetlogUpload;
import itda.setlog.domain.SetlogUploadStatus;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
class SetlogUploadRepositoryPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private SetlogUploadRepository uploadRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PetRepository petRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long petId;

    @BeforeEach
    void fixtures() {
        jdbcTemplate.update("""
                insert into neighborhoods (code, sido_name, sigungu_name, eupmyeondong_name)
                values ('4113111500', '경기도', '성남시', '수내동')
                on conflict (code) do nothing
                """);
        String unique = UUID.randomUUID().toString().replace("-", "");
        userId = jdbcTemplate.queryForObject("""
                insert into users (
                    email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code
                ) values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500')
                returning id
                """, Long.class, unique + "@example.com", "보호자#" + unique.substring(0, 8));
        petId = jdbcTemplate.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, '반려견', 'ACTIVE') returning id
                """, Long.class, userId, "반려견#" + unique.substring(0, 4).toUpperCase());
    }

    @Test
    void savesFlushesAndReloadsPresignedSessionWithAuditing() {
        User user = userRepository.findById(userId).orElseThrow();
        Pet pet = petRepository.findById(petId).orElseThrow();
        UUID uploadId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-12T01:15:00Z");

        uploadRepository.saveAndFlush(SetlogUpload.presigned(
                uploadId, user, pet, "setlogs/%d/%d/%s.mp4".formatted(userId, petId, uploadId),
                "video/mp4", 209715200L, expiresAt
        ));
        entityManager.clear();

        SetlogUpload reloaded = uploadRepository.findById(uploadId).orElseThrow();
        assertThat(reloaded.getOwner().getId()).isEqualTo(userId);
        assertThat(reloaded.getPet().getId()).isEqualTo(petId);
        assertThat(reloaded.getExpectedSize()).isEqualTo(209715200L);
        assertThat(reloaded.getContentType()).isEqualTo("video/mp4");
        assertThat(reloaded.getStatus()).isEqualTo(SetlogUploadStatus.PRESIGNED);
        assertThat(reloaded.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void databaseRejectsUnknownOwnerForeignKey() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into setlog_uploads (
                    id, owner_user_id, pet_id, object_key, content_type,
                    expected_size, status, expires_at
                ) values (?, ?, ?, ?, 'video/mp4', 1, 'PRESIGNED', now())
                """, UUID.randomUUID(), Long.MAX_VALUE, petId, "setlogs/invalid/" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsVerifiedObjectMetadataIncludingVersionId() {
        Long mediaId = jdbcTemplate.queryForObject("""
                insert into media (
                    media_type, path, status, user_id, file_size,
                    content_type, etag, object_version_id,
                    storage_last_modified, verified_at,
                    created_at, updated_at
                ) values (
                    'VIDEO', ?, 'COMPLETED', ?, 1024,
                    'video/mp4', 'etag-7', 'version-7',
                    '2026-08-12T01:00:00Z', '2026-08-12T01:01:00Z',
                    now(), now()
                ) returning id
                """, Long.class, "setlogs/%d/%d/video.mp4".formatted(userId, petId), userId);

        var row = jdbcTemplate.queryForMap("""
                select content_type, etag, object_version_id,
                       storage_last_modified, verified_at
                  from media
                 where id = ?
                """, mediaId);

        assertThat(row)
                .containsEntry("content_type", "video/mp4")
                .containsEntry("etag", "etag-7")
                .containsEntry("object_version_id", "version-7");
        assertThat(row.get("storage_last_modified")).isNotNull();
        assertThat(row.get("verified_at")).isNotNull();
    }
}
