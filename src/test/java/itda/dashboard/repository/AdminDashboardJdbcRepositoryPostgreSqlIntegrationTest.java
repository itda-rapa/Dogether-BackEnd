package itda.dashboard.repository;

import static org.assertj.core.api.Assertions.assertThat;

import itda.dashboard.dto.AdminDashboardResponse.RecentItemSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
        "app.safety.evaluator.enabled=false"
})
class AdminDashboardJdbcRepositoryPostgreSqlIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant FROM = LocalDate.of(2026, 8, 24)
            .atStartOfDay(SEOUL).toInstant();
    private static final Instant TO = LocalDate.of(2026, 8, 25)
            .atStartOfDay(SEOUL).toInstant();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private AdminDashboardJdbcRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                truncate table risk_signal_events, safety_review_cases, storage_delete_jobs
                restart identity cascade
                """);
        jdbc.execute("truncate table neighborhoods restart identity cascade");
        jdbc.update("""
                insert into neighborhoods (code, sido_name, sigungu_name, eupmyeondong_name)
                values ('DASHBOARD', '서울특별시', '테스트구', '테스트동')
                """);
    }

    @Test
    void countsOnlyConfiguredStatusesInsideInclusiveExclusiveKstBoundary() {
        long beforeUser = insertUser("USER", "ACTIVE", FROM.minusSeconds(1));
        long fromUser = insertUser("USER", "ACTIVE", FROM);
        long beforeToUser = insertUser("USER", "SUSPENDED", TO.minusSeconds(1));
        insertUser("USER", "ACTIVE", TO);
        long withdrawnUser = insertUser("USER", "WITHDRAWN", FROM.plusSeconds(1));
        insertUser("ADMIN", "ACTIVE", FROM.plusSeconds(1));

        long beforePet = insertPet(beforeUser, "ACTIVE", FROM.minusSeconds(1));
        long fromPet = insertPet(fromUser, "ACTIVE", FROM);
        insertPet(beforeToUser, "SUSPENDED", TO.minusSeconds(1));
        long deletedPet = insertPet(fromUser, "DELETED", FROM.plusSeconds(1));
        insertPet(fromUser, "ACTIVE", TO);
        long withdrawnOwnerPet = insertPet(withdrawnUser, "ACTIVE", FROM.plusSeconds(2));

        insertSetlog(fromPet, "VISIBLE", false, FROM);
        insertSetlog(beforePet, "VISIBLE", true, FROM.plusSeconds(1));
        insertSetlog(beforePet, "DELETED_BY_AUTHOR", false, TO.minusSeconds(1));
        insertSetlog(beforePet, "VISIBLE", false, TO);

        insertBoardPost(fromUser, fromPet, "PUBLISHED", FROM);
        insertBoardPost(fromUser, fromPet, "DELETED", TO.minusSeconds(1));
        insertBoardPost(fromUser, fromPet, "PUBLISHED", TO);
        insertBoardPost(withdrawnUser, withdrawnOwnerPet, "PUBLISHED", FROM.plusSeconds(2));
        insertBoardPost(fromUser, deletedPet, "PUBLISHED", TO.minusSeconds(1));

        insertReport(fromUser, fromPet, beforeUser, beforePet, "OPEN", FROM);
        insertReport(beforeUser, beforePet, fromUser, fromPet, "ACTIONED", TO.minusSeconds(1));
        insertReport(beforeToUser, insertPet(beforeToUser, "ACTIVE", FROM.minusSeconds(1)),
                fromUser, fromPet, "OPEN", TO);

        insertRisk("USER_BLOCK", "USER_BLOCKED", 101, 201, FROM);
        insertRisk("GREETING", "GREETING_EXPIRED", 101, 202, TO.minusSeconds(1));
        insertRisk("USER_BLOCK", "USER_BLOCKED", 102, 201, FROM.plusSeconds(1));
        insertRisk("USER_BLOCK", "USER_BLOCKED", 103, 201, TO);

        insertSafetyCase("OPEN", FROM);
        insertSafetyCase("REVIEWING", FROM.plusSeconds(1));
        insertSafetyCase("DISMISSED", FROM.plusSeconds(2));
        insertSafetyCase("WARNING_RECORDED", FROM.plusSeconds(3));

        insertStorageJob("PENDING", 1);
        insertStorageJob("RETRY", 2);
        insertStorageJob("RETRY", 3);
        insertStorageJob("FAILED", 4);
        insertStorageJob("FAILED", 5);
        insertStorageJob("FAILED", 6);
        insertStorageJob("PROCESSING", 7);
        insertStorageJob("COMPLETED", 8);

        var counts = repository.findCounts(FROM, TO);

        assertThat(counts.usersTotal()).isEqualTo(4);
        assertThat(counts.usersNew()).isEqualTo(2);
        assertThat(counts.petsTotal()).isEqualTo(5);
        assertThat(counts.petsNew()).isEqualTo(2);
        assertThat(counts.setlogsTotal()).isEqualTo(2);
        assertThat(counts.setlogsNew()).isOne();
        assertThat(counts.boardPostsTotal()).isEqualTo(4);
        assertThat(counts.boardPostsNew()).isEqualTo(3);
        assertThat(counts.reportsCreated()).isEqualTo(2);
        assertThat(counts.reportsOpen()).isEqualTo(2);
        assertThat(counts.detectedUsers()).isEqualTo(2);
        assertThat(counts.openCases()).isEqualTo(2);
        assertThat(counts.cleanupPending()).isOne();
        assertThat(counts.cleanupRetry()).isEqualTo(2);
        assertThat(counts.cleanupFailed()).isEqualTo(3);

        assertThat(repository.findSignalCounts(FROM, TO))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "USER_BLOCKED", 2L,
                        "GREETING_EXPIRED", 1L));
    }

    @Test
    void returnsEverySupportedSignalTypeIncludingZeroCount() {
        insertRisk("USER_BLOCK", "USER_BLOCKED", 101, 201, FROM);

        assertThat(repository.findSignalCounts(FROM, TO))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "USER_BLOCKED", 1L,
                        "GREETING_EXPIRED", 0L));
    }

    @Test
    void returnsAtMostTenRecentItemsWithDeterministicTieBreaking() {
        long reporterUser = insertUser("USER", "ACTIVE", FROM);
        long reportedUser = insertUser("USER", "ACTIVE", FROM);
        long reporterPet = insertPet(reporterUser, "ACTIVE", FROM);
        long reportedPet = insertPet(reportedUser, "ACTIVE", FROM);
        Instant sameCreatedAt = FROM.plusSeconds(3600);

        for (int index = 0; index < 6; index++) {
            insertReport(reporterUser, reporterPet, reportedUser, reportedPet,
                    "ACTIONED", sameCreatedAt);
            insertSafetyCase("DISMISSED", sameCreatedAt);
        }

        var items = repository.findRecentItems();

        assertThat(items).hasSize(10);
        assertThat(items.subList(0, 6))
                .extracting(item -> item.source())
                .containsOnly(RecentItemSource.REPORT);
        assertThat(items.subList(0, 6))
                .extracting(item -> item.id())
                .containsExactly(6L, 5L, 4L, 3L, 2L, 1L);
        assertThat(items.subList(6, 10))
                .extracting(item -> item.source())
                .containsOnly(RecentItemSource.SAFETY_CASE);
        assertThat(items.subList(6, 10))
                .extracting(item -> item.id())
                .containsExactly(6L, 5L, 4L, 3L);
        assertThat(items)
                .extracting(item -> item.createdAt())
                .containsOnly(sameCreatedAt);
    }

    @Test
    void dashboardAggregateSqlProducesAnExecutablePostgreSqlPlanBeforeAddingIndexes() {
        insertUser("USER", "ACTIVE", FROM);
        insertRisk("USER_BLOCK", "USER_BLOCKED", 101, 201, FROM);

        assertExplainCompletes("""
                select signal_type, count(*) from risk_signal_events
                 where occurred_at >= timestamptz '2026-08-23 15:00:00+00'
                   and occurred_at < timestamptz '2026-08-24 15:00:00+00'
                 group by signal_type
                """);
        assertExplainCompletes("""
                select count(*) filter (where status = 'PENDING'),
                       count(*) filter (where status = 'RETRY'),
                       count(*) filter (where status = 'FAILED')
                  from storage_delete_jobs
                """);

        var plan = jdbc.query(
                "explain (analyze, buffers, format text) "
                        + AdminDashboardJdbcRepository.COUNTS_SQL,
                (resultSet, rowNumber) -> resultSet.getString(1),
                Timestamp.from(FROM), Timestamp.from(TO), Timestamp.from(FROM), Timestamp.from(TO),
                Timestamp.from(FROM), Timestamp.from(TO), Timestamp.from(FROM), Timestamp.from(TO),
                Timestamp.from(FROM), Timestamp.from(TO), Timestamp.from(FROM), Timestamp.from(TO));

        assertThat(plan).anyMatch(line -> line.contains("users"));
        assertThat(plan).anyMatch(line -> line.contains("storage_delete_jobs"));
        assertThat(plan).anyMatch(line -> line.contains("Planning Time"));
        assertThat(plan).anyMatch(line -> line.contains("Execution Time"));
    }

    private void assertExplainCompletes(String query) {
        var plan = jdbc.queryForList(
                "explain (analyze, buffers, format text) " + query, String.class);

        assertThat(plan).anyMatch(line -> line.contains("Planning Time"));
        assertThat(plan).anyMatch(line -> line.contains("Execution Time"));
    }

    private long insertUser(String role, String status, Instant createdAt) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbc.queryForObject("""
                insert into users (
                    email, password_hash, nickname, public_tag, role,
                    account_status, neighborhood_code, withdrawn_at, created_at, updated_at
                ) values (?, 'encoded', '사용자', ?, ?, ?, 'DASHBOARD',
                          case when cast(? as text) = 'WITHDRAWN'
                               then cast(? as timestamptz) else null end, ?, ?)
                returning id
                """, Long.class, unique + "@example.com", "사용자#" + unique.substring(0, 8),
                role, status, status, Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private long insertPet(long ownerId, String status, Instant createdAt) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbc.queryForObject("""
                insert into pets (
                    owner_user_id, public_tag, nickname, status, deleted_at, created_at, updated_at
                ) values (?, ?, '반려견', ?,
                          case when cast(? as text) = 'DELETED'
                               then cast(? as timestamptz) else null end, ?, ?)
                returning id
                """, Long.class, ownerId,
                "반려견#" + unique.substring(0, 4).toUpperCase(), status, status,
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertSetlog(long petId, String status, boolean seed, Instant createdAt) {
        String unique = UUID.randomUUID().toString();
        Long mediaId = jdbc.queryForObject("""
                insert into media (media_type, path, status, file_size, created_at, updated_at)
                values ('IMAGE', ?, 'COMPLETED', 1, ?, ?)
                returning id
                """, Long.class, "dashboard/" + unique,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        jdbc.update("""
                insert into setlogs (author_pet_id, media_id, status, is_seed, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """, petId, mediaId, status, seed,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertBoardPost(long userId, long petId, String status, Instant createdAt) {
        Long boardId = jdbc.queryForObject("""
                insert into boards (name) values (?) returning id
                """, Long.class,
                "게시판-" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.update("""
                insert into board_posts (
                    board_id, author_user_id, author_pet_id, neighborhood_code,
                    title, content, status, deleted_at, created_at, updated_at
                ) values (?, ?, ?, 'DASHBOARD', '제목', '내용', ?,
                          case when cast(? as text) = 'DELETED'
                               then cast(? as timestamptz) else null end, ?, ?)
                """, boardId, userId, petId, status, status, Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertReport(
            long reporterUser, long reporterPet, long reportedUser, long reportedPet,
            String status, Instant createdAt
    ) {
        Long roomId = jdbc.queryForObject("""
                insert into chat_rooms (type, status, origin, created_at, updated_at)
                values ('GROUP', 'ACTIVE', 'FRIEND', ?, ?)
                returning id
                """, Long.class, Timestamp.from(createdAt), Timestamp.from(createdAt));
        jdbc.update("""
                insert into reports (
                    reporter_user_id, reporter_pet_id, reported_user_id, reported_pet_id,
                    room_id, reason_code, status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, 'SPAM', ?, ?, ?)
                """, reporterUser, reporterPet, reportedUser, reportedPet, roomId, status,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertRisk(
            String sourceType, String signalType, long actor, long target, Instant occurredAt
    ) {
        jdbc.update("""
                insert into risk_signal_events (
                    event_id, schema_version, source_type, source_id, signal_type,
                    actor_user_id, target_user_id, score, score_policy_version,
                    occurred_at, metadata
                ) values (?, 1, ?, ?, ?, ?, ?, 10, 1, ?, '{}'::jsonb)
                """, UUID.randomUUID(), sourceType, Math.abs(UUID.randomUUID().getLeastSignificantBits()) + 1,
                signalType, actor, target, Timestamp.from(occurredAt));
    }

    private void insertSafetyCase(String status, Instant createdAt) {
        long subject = Math.abs(UUID.randomUUID().getLeastSignificantBits()) + 1;
        jdbc.update("""
                insert into safety_review_cases (
                    subject_user_id, status, total_score, signal_count, primary_signal_type,
                    evaluation_policy_version, first_detected_at, last_detected_at,
                    last_evaluated_event_id, evaluated_at, created_at, updated_at
                ) values (?, ?, 30, 1, 'USER_BLOCKED', 1, ?, ?, ?, ?, ?, ?)
                """, subject, status, Timestamp.from(createdAt), Timestamp.from(createdAt),
                subject, Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertStorageJob(String status, int suffix) {
        Instant now = FROM.plusSeconds(suffix);
        UUID claimToken = status.equals("PROCESSING") ? UUID.randomUUID() : null;
        Timestamp claimedAt = status.equals("PROCESSING") ? Timestamp.from(now) : null;
        Timestamp completedAt = status.equals("COMPLETED") ? Timestamp.from(now) : null;
        jdbc.update("""
                insert into storage_delete_jobs (
                    object_key, reason, status, next_retry_at, claim_token,
                    claimed_at, completed_at, created_at, updated_at
                ) values (?, 'SETLOG_DELETED', ?, ?, ?, ?, ?, ?, ?)
                """, "dashboard/object-" + suffix, status, Timestamp.from(now), claimToken,
                claimedAt, completedAt, Timestamp.from(now), Timestamp.from(now));
    }
}
