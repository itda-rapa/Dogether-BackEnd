package itda.setlog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import itda.media.domain.MediaStatus;
import itda.pet.domain.PetStatus;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import itda.user.domain.AccountStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
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
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class SetlogRepositoryPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private SetlogRepository setlogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void feedExcludesBothBlockDirectionsAndPagesStableTiesIncludingNonSeed() {
        Long viewer = createUser();
        Long visibleAuthor = createUser();
        Long outboundBlockedAuthor = createUser();
        Long inboundBlockedAuthor = createUser();
        Long visiblePet = createPet(visibleAuthor);
        Long outboundPet = createPet(outboundBlockedAuthor);
        Long inboundPet = createPet(inboundBlockedAuthor);
        Instant sameCreatedAt = Instant.parse("2026-08-11T01:00:00Z");

        Long first = createSetlog(visiblePet, visibleAuthor, false, sameCreatedAt);
        Long second = createSetlog(visiblePet, visibleAuthor, false, sameCreatedAt);
        Long third = createSetlog(visiblePet, visibleAuthor, false, sameCreatedAt);
        createSetlog(outboundPet, outboundBlockedAuthor, false, sameCreatedAt);
        createSetlog(inboundPet, inboundBlockedAuthor, false, sameCreatedAt);
        Long deletedMediaSetlog = createSetlog(
                visiblePet, visibleAuthor, false, sameCreatedAt
        );
        jdbcTemplate.update("""
                update media
                   set deleted_at = ?
                 where id = (select media_id from setlogs where id = ?)
                """, sameCreatedAt.atOffset(ZoneOffset.UTC), deletedMediaSetlog);
        block(viewer, outboundBlockedAuthor);
        block(inboundBlockedAuthor, viewer);

        List<Setlog> firstPage = find(viewer, null, null, 2);
        Setlog boundary = firstPage.getLast();
        List<Setlog> secondPage = find(
                viewer, boundary.getCreatedAt(), boundary.getId(), 2
        );

        assertThat(firstPage).extracting(Setlog::getId)
                .containsExactly(third, second);
        assertThat(secondPage).extracting(Setlog::getId)
                .containsExactly(first);
        assertThat(firstPage).allMatch(setlog -> !setlog.isSeed());
        assertThat(firstPage).extracting(Setlog::getId)
                .doesNotHaveDuplicates();
        assertThat(secondPage).extracting(Setlog::getId)
                .doesNotHaveDuplicates();
    }

    private List<Setlog> find(
            Long viewer,
            Instant cursorCreatedAt,
            Long cursorId,
            int size
    ) {
        return setlogRepository.findVisibleFeed(
                viewer,
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED),
                PetStatus.ACTIVE,
                AccountStatus.ACTIVE,
                cursorCreatedAt,
                cursorId,
                PageRequest.of(0, size)
        );
    }

    private Long createUser() {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                insert into users (
                    email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code
                ) values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500')
                returning id
                """, Long.class,
                unique + "@example.com",
                "보호자#" + unique.substring(0, 8));
    }

    private Long createPet(Long ownerId) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, '반려견', 'ACTIVE')
                returning id
                """, Long.class,
                ownerId,
                "반려견#" + unique.substring(0, 4).toUpperCase());
    }

    private Long createSetlog(
            Long petId,
            Long authorUserId,
            boolean seed,
            Instant createdAt
    ) {
        String unique = unique();
        Long mediaId = jdbcTemplate.queryForObject("""
                insert into media (
                    media_type, path, status, user_id, file_size
                ) values ('VIDEO', ?, 'UPLOADED', ?, 1024)
                returning id
                """, Long.class, "setlogs/" + unique + ".mp4", authorUserId);
        return jdbcTemplate.queryForObject("""
                insert into setlogs (
                    author_pet_id, media_id, caption, status, is_seed,
                    created_at, updated_at
                ) values (?, ?, '같이 놀아요', 'VISIBLE', ?, ?, ?)
                returning id
                """, Long.class,
                petId,
                mediaId,
                seed,
                createdAt.atOffset(ZoneOffset.UTC),
                createdAt.atOffset(ZoneOffset.UTC));
    }

    private void block(Long blocker, Long blocked) {
        jdbcTemplate.update("""
                insert into user_blocks (blocker_user_id, blocked_user_id)
                values (?, ?)
                """, blocker, blocked);
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
