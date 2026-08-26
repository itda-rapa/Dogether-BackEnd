package itda.setlog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import itda.media.domain.MediaStatus;
import itda.pet.domain.PetStatus;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import itda.user.domain.AccountStatus;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class SetlogRepositoryPostgreSqlIntegrationTest {

    /**
     * V39_2 migration이 postgis/pgrouting extension을 요구하므로 두 extension을 모두 제공하는
     * 이미지를 사용한다. postgres:16-alpine에서는 Flyway 초기화 단계에서 실패한다.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private SetlogRepository setlogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void createNeighborhoodFixture() {
        jdbcTemplate.update("""
                insert into neighborhoods (
                    code, sido_name, sigungu_name, eupmyeondong_name
                ) values ('4113111500', '경기도', '성남시', '수내동')
                on conflict (code) do nothing
                """);
    }

    @Test
    void feedExcludesBothBlockDirectionsAndPagesStableTiesIncludingNonSeed() {
        Long viewer = createUser();
        Long visibleAuthor = createUser();
        Long outboundBlockedAuthor = createUser();
        Long inboundBlockedAuthor = createUser();
        Long visiblePet = createPet(visibleAuthor);
        Long outboundPet = createPet(outboundBlockedAuthor);
        Long inboundPet = createPet(inboundBlockedAuthor);
        Instant sameCreatedAt = Instant.parse("2099-08-11T01:00:00Z");

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

    @Test
    void firstPageExcludesInactiveAuthorsPetsMediaAndSetlogs() {
        Long viewer = createUser();
        Instant createdAt = Instant.parse("2099-08-11T02:00:00Z");

        Long visibleAuthor = createUser();
        Long visiblePet = createPet(visibleAuthor);
        Long visible = createSetlog(
                visiblePet, visibleAuthor, false, createdAt
        );
        updateMediaStatus(visible, MediaStatus.COMPLETED);

        Long suspendedAuthor = createUser();
        Long suspendedAuthorPet = createPet(suspendedAuthor);
        Long suspendedAuthorSetlog = createSetlog(
                suspendedAuthorPet, suspendedAuthor, false, createdAt
        );
        jdbcTemplate.update(
                "update users set account_status = 'SUSPENDED' where id = ?",
                suspendedAuthor
        );

        Long suspendedPet = createPet(visibleAuthor);
        Long suspendedPetSetlog = createSetlog(
                suspendedPet, visibleAuthor, false, createdAt
        );
        jdbcTemplate.update(
                "update pets set status = 'SUSPENDED' where id = ?",
                suspendedPet
        );

        Long deletedPet = createPet(visibleAuthor);
        Long deletedPetSetlog = createSetlog(
                deletedPet, visibleAuthor, false, createdAt
        );
        jdbcTemplate.update(
                "update pets set status = 'DELETED', deleted_at = ? where id = ?",
                createdAt.atOffset(ZoneOffset.UTC),
                deletedPet
        );

        Long initMediaSetlog = createSetlog(
                visiblePet, visibleAuthor, false, createdAt
        );
        updateMediaStatus(initMediaSetlog, MediaStatus.INIT);
        Long failedMediaSetlog = createSetlog(
                visiblePet, visibleAuthor, false, createdAt
        );
        updateMediaStatus(failedMediaSetlog, MediaStatus.FAILED);

        Long deletedSetlog = createSetlog(
                visiblePet, visibleAuthor, false, createdAt
        );
        jdbcTemplate.update(
                "update setlogs set status = 'DELETED_BY_AUTHOR' where id = ?",
                deletedSetlog
        );

        List<Long> resultIds = find(viewer, null, null, 100).stream()
                .map(Setlog::getId)
                .toList();

        assertThat(resultIds)
                .contains(visible)
                .doesNotContain(
                        suspendedAuthorSetlog,
                        suspendedPetSetlog,
                        deletedPetSetlog,
                        initMediaSetlog,
                        failedMediaSetlog,
                        deletedSetlog
                );
    }

    @Test
    void interactableLookupAcceptsSeedAndUserSetlogs() {
        Long author = createUser();
        Long pet = createPet(author);
        Instant createdAt = Instant.parse("2099-08-11T03:00:00Z");
        Long seed = createSetlog(pet, author, true, createdAt);
        Long user = createSetlog(pet, author, false, createdAt);

        assertThat(setlogRepository.findInteractableById(
                seed,
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED)
        )).isPresent().get().extracting(Setlog::isSeed).isEqualTo(true);
        assertThat(setlogRepository.findInteractableByIdForUpdate(
                user,
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED)
        )).isPresent().get().extracting(Setlog::isSeed).isEqualTo(false);
    }

    @Test
    void interactableLookupExcludesDeletedOrInactiveGraph() {
        Long author = createUser();
        Long pet = createPet(author);
        Instant now = Instant.parse("2099-08-11T04:00:00Z");
        Long deletedSetlog = createSetlog(pet, author, false, now);
        Long deletedMedia = createSetlog(pet, author, false, now);
        Long inactiveMedia = createSetlog(pet, author, false, now);
        Long deletedPet = createPet(author);
        Long deletedPetSetlog = createSetlog(deletedPet, author, false, now);
        Long suspendedAuthor = createUser();
        Long suspendedPet = createPet(suspendedAuthor);
        Long suspendedAuthorSetlog = createSetlog(
                suspendedPet, suspendedAuthor, false, now
        );
        jdbcTemplate.update(
                "update setlogs set status = 'DELETED_BY_AUTHOR' where id = ?",
                deletedSetlog
        );
        jdbcTemplate.update("""
                update media set deleted_at = ?
                 where id = (select media_id from setlogs where id = ?)
                """, now.atOffset(ZoneOffset.UTC), deletedMedia);
        updateMediaStatus(inactiveMedia, MediaStatus.FAILED);
        jdbcTemplate.update(
                "update pets set status = 'DELETED', deleted_at = ? where id = ?",
                now.atOffset(ZoneOffset.UTC), deletedPet
        );
        jdbcTemplate.update(
                "update users set account_status = 'SUSPENDED' where id = ?",
                suspendedAuthor
        );

        assertThat(List.of(
                deletedSetlog,
                deletedMedia,
                inactiveMedia,
                deletedPetSetlog,
                suspendedAuthorSetlog
        )).allSatisfy(setlogId -> assertThat(
                setlogRepository.findInteractableById(
                        setlogId,
                        SetlogStatus.VISIBLE,
                        List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED)
                )
        ).isEmpty());
    }

    @Test
    void detailLookupExcludesBothBlockDirections() {
        Long viewer = createUser();
        Long author = createUser();
        Long authorPet = createPet(author);
        Instant createdAt = Instant.parse("2099-08-11T05:00:00Z");
        Long setlogId = createSetlog(authorPet, author, false, createdAt);

        assertThat(findDetail(setlogId, viewer)).isPresent();

        block(viewer, author);
        assertThat(findDetail(setlogId, viewer)).isEmpty();

        jdbcTemplate.update(
                "delete from user_blocks where blocker_user_id = ? and blocked_user_id = ?",
                viewer, author
        );
        assertThat(findDetail(setlogId, viewer)).isPresent();

        block(author, viewer);
        assertThat(findDetail(setlogId, viewer)).isEmpty();
    }

    @Test
    void detailLookupExcludesDeletedInvisibleAndInactiveGraph() {
        Long viewer = createUser();
        Instant createdAt = Instant.parse("2099-08-11T06:00:00Z");

        Long author = createUser();
        Long authorPet = createPet(author);
        Long visible = createSetlog(authorPet, author, false, createdAt);
        updateMediaStatus(visible, MediaStatus.COMPLETED);

        Long deletedSetlog = createSetlog(authorPet, author, false, createdAt);
        jdbcTemplate.update(
                "update setlogs set status = 'DELETED_BY_AUTHOR' where id = ?",
                deletedSetlog
        );

        Long deletedMediaSetlog = createSetlog(authorPet, author, false, createdAt);
        jdbcTemplate.update("""
                update media set deleted_at = ?
                 where id = (select media_id from setlogs where id = ?)
                """, createdAt.atOffset(ZoneOffset.UTC), deletedMediaSetlog);

        Long failedMediaSetlog = createSetlog(authorPet, author, false, createdAt);
        updateMediaStatus(failedMediaSetlog, MediaStatus.FAILED);

        Long suspendedPet = createPet(author);
        Long suspendedPetSetlog = createSetlog(suspendedPet, author, false, createdAt);
        jdbcTemplate.update(
                "update pets set status = 'SUSPENDED' where id = ?", suspendedPet
        );

        Long deletedPet = createPet(author);
        Long deletedPetSetlog = createSetlog(deletedPet, author, false, createdAt);
        jdbcTemplate.update(
                "update pets set status = 'DELETED', deleted_at = ? where id = ?",
                createdAt.atOffset(ZoneOffset.UTC), deletedPet
        );

        Long suspendedAuthor = createUser();
        Long suspendedAuthorPet = createPet(suspendedAuthor);
        Long suspendedAuthorSetlog = createSetlog(
                suspendedAuthorPet, suspendedAuthor, false, createdAt
        );
        jdbcTemplate.update(
                "update users set account_status = 'SUSPENDED' where id = ?",
                suspendedAuthor
        );

        assertThat(findDetail(visible, viewer)).isPresent();
        assertThat(List.of(
                deletedSetlog,
                deletedMediaSetlog,
                failedMediaSetlog,
                suspendedPetSetlog,
                deletedPetSetlog,
                suspendedAuthorSetlog
        )).allSatisfy(hiddenSetlogId ->
                assertThat(findDetail(hiddenSetlogId, viewer)).isEmpty()
        );
    }

    /**
     * 상세 응답 조립에 필요한 Media·작성자 Pet·작성자 User를 모두 fetch join으로 채우는지
     * 실제 statement 수로 검증한다. 하나라도 lazy로 남으면 추가 select가 발생한다.
     */
    @Test
    void detailLookupLoadsMediaAndAuthorGraphInSingleStatement() {
        Long viewer = createUser();
        Long author = createUser();
        Long authorPet = createPet(author);
        Long setlogId = createSetlog(
                authorPet, author, false, Instant.parse("2099-08-11T07:00:00Z")
        );
        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Setlog setlog = findDetail(setlogId, viewer).orElseThrow();
        String mediaPath = setlog.getMedia().getPath();
        MediaStatus mediaStatus = setlog.getMedia().getStatus();
        String authorNickname = setlog.getAuthorPet().getOwner().getNickname();

        assertThat(mediaPath).isNotBlank();
        assertThat(mediaStatus).isEqualTo(MediaStatus.UPLOADED);
        assertThat(authorNickname).isNotBlank();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    private Optional<Setlog> findDetail(Long setlogId, Long viewer) {
        return setlogRepository.findVisibleDetailById(
                setlogId,
                viewer,
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED),
                PetStatus.ACTIVE,
                AccountStatus.ACTIVE
        );
    }

    private List<Setlog> find(
            Long viewer,
            Instant cursorCreatedAt,
            Long cursorId,
            int size
    ) {
        if (cursorCreatedAt == null) {
            return setlogRepository.findVisibleFeedFirstPage(
                    viewer,
                    SetlogStatus.VISIBLE,
                    List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED),
                    PetStatus.ACTIVE,
                    AccountStatus.ACTIVE,
                    PageRequest.of(0, size)
            );
        }
        return setlogRepository.findVisibleFeedAfter(
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

    private void updateMediaStatus(Long setlogId, MediaStatus status) {
        jdbcTemplate.update("""
                update media
                   set status = ?
                 where id = (select media_id from setlogs where id = ?)
                """, status.name(), setlogId);
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
