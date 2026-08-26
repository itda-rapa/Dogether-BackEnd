package itda.meetingsuggestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.meetingsuggestion.service.MeetingSuggestionScanClaimService.ClaimedScan;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Scan 생성·claim·lease·소유권 fence 와 DB invariant 를 실제 PostgreSQL 에서 검증한다.
 *
 * <p>Block/leftAt 선별 의미는 {@code MeetingCardRepository.findVisibleCards} 와 같은
 * User-pair Block + leftAt IS NULL 정책을 그대로 쓴다.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
class MeetingSuggestionScanPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired private MeetingSuggestionScanClaimService claims;
    @Autowired private JdbcTemplate jdbc;

    private static final LocalDate SOURCE_DATE = LocalDate.of(2026, 8, 24);
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 8, 25);
    private static final String NEIGHBORHOOD = "4113111500";

    @BeforeEach
    void clearTables() {
        jdbc.execute("""
                truncate meeting_suggestions, meeting_suggestion_scans,
                         meeting_participants, meeting_cards, card_drafts,
                         chat_messages, chat_room_participants, chat_rooms,
                         user_blocks, pets, users, neighborhoods
                restart identity cascade
                """);
        jdbc.update("insert into neighborhoods (code, sido_name) values (?, '서울특별시')", NEIGHBORHOOD);
    }

    // ── Scan 생성 대상 선별 ───────────────────────────────────────────────────

    @Test
    @DisplayName("DIRECT + 양쪽 participant + Block 없음 방에만 Scan 을 만든다")
    void createsScansOnlyForEligibleDirectRooms() {
        for (long user = 1; user <= 8; user++) {
            insertUser(user);
        }
        insertPet(11L, 1L);
        insertPet(22L, 2L);
        insertPet(33L, 3L);
        insertPet(44L, 4L);
        insertPet(55L, 5L);
        insertPet(66L, 6L);
        insertPet(77L, 7L);
        insertPet(88L, 8L);

        // 적격: user1/pet11 ↔ user2/pet22, 양쪽 participant
        long eligible = directRoom(11L, 22L);
        participant(eligible, 11L, null);
        participant(eligible, 22L, null);

        // Block: user3/pet33 ↔ user4/pet44
        long blocked = directRoom(33L, 44L);
        participant(blocked, 33L, null);
        participant(blocked, 44L, null);
        blockBetween(3L, 4L);

        // leftAt: pet66 이 나감
        long left = directRoom(55L, 66L);
        participant(left, 55L, null);
        participant(left, 66L, Instant.now());

        // 참가자 부족: pet88 의 participant 없음
        long missingParticipant = directRoom(77L, 88L);
        participant(missingParticipant, 77L, null);

        // GROUP 은 대상이 아니다
        groupRoom();

        assertThat(claims.createScans(SOURCE_DATE, REFERENCE_DATE)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select room_id from meeting_suggestion_scans", Long.class)).isEqualTo(eligible);
        assertThat(countScans()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 room+sourceDate 는 한 번만 만들어진다")
    void scanCreationIsIdempotent() {
        insertUser(1L);
        insertUser(2L);
        insertPet(11L, 1L);
        insertPet(22L, 2L);
        long room = directRoom(11L, 22L);
        participant(room, 11L, null);
        participant(room, 22L, null);

        assertThat(claims.createScans(SOURCE_DATE, REFERENCE_DATE)).isEqualTo(1);
        assertThat(claims.createScans(SOURCE_DATE, REFERENCE_DATE)).isZero();

        assertThat(countScans()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select reference_date from meeting_suggestion_scans", Date.class).toLocalDate())
                .isEqualTo(REFERENCE_DATE);
    }

    @Test
    @DisplayName("동시 생성에서도 room+sourceDate Scan 은 하나다")
    void concurrentCreationCreatesOneScan() throws Exception {
        insertUser(1L);
        insertUser(2L);
        insertPet(11L, 1L);
        insertPet(22L, 2L);
        long room = directRoom(11L, 22L);
        participant(room, 11L, null);
        participant(room, 22L, null);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> createAfter(start));
            Future<Integer> second = executor.submit(() -> createAfter(start));
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS))
                    .isEqualTo(1);
        }
        assertThat(countScans()).isEqualTo(1);
    }

    // ── claim / lease / fence ─────────────────────────────────────────────────

    @Test
    @DisplayName("동시 worker 중 하나만 claim 한다 (SKIP LOCKED)")
    void concurrentWorkersClaimEachScanOnlyOnce() throws Exception {
        long room = givenDirectRoom();
        insertScan(room);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<ClaimedScan>> first = executor.submit(() -> claimAfter(start));
            Future<List<ClaimedScan>> second = executor.submit(() -> claimAfter(start));
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).size()
                    + second.get(10, TimeUnit.SECONDS).size()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "select attempts from meeting_suggestion_scans where id = ?",
                Integer.class, scanId())).isEqualTo(1);
    }

    @Test
    @DisplayName("lease 만료 PROCESSING 은 새 token 으로 재선점되고 옛 worker 는 fence 된다")
    void staleClaimIsRecoveredAndOldWorkerIsFenced() {
        insertScan(givenDirectRoom());
        ClaimedScan first = claims.claim(1, Duration.ofMinutes(1)).getFirst();
        jdbc.update("update meeting_suggestion_scans set claimed_at = now() - interval '2 minutes' where id = ?",
                first.id());

        ClaimedScan recovered = claims.claim(1, Duration.ofMinutes(1)).getFirst();

        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(recovered.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(claims.markCompleted(first)).isFalse();
        assertThat(claims.markRetryable(first, Instant.now(), "old worker")).isFalse();
        assertThat(claims.markFinal(first, "old worker")).isFalse();
        assertThat(claims.markCompleted(recovered)).isTrue();
        assertThat(statusOf(first.id())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("FAILED_RETRYABLE 은 nextRetryAt 전에 claim 되지 않고 FAILED_FINAL 은 종결이다")
    void retryIsNotClaimedBeforeDueAndFinalIsTerminal() {
        insertScan(givenDirectRoom());
        ClaimedScan first = claims.claim(1, Duration.ofMinutes(1)).getFirst();
        Instant retryAt = Instant.now().plusSeconds(60);

        assertThat(claims.markRetryable(first, retryAt, "TimeoutException")).isTrue();
        assertThat(claims.claim(1, Duration.ofMinutes(1))).isEmpty();
        assertThat(statusOf(first.id())).isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject(
                "select claim_token is null and claimed_at is null from meeting_suggestion_scans where id = ?",
                Boolean.class, first.id())).isTrue();

        jdbc.update("update meeting_suggestion_scans set next_retry_at = now() - interval '1 second' where id = ?",
                first.id());
        ClaimedScan retry = claims.claim(1, Duration.ofMinutes(1)).getFirst();
        assertThat(retry.attempts()).isEqualTo(2);
        assertThat(claims.markFinal(retry, "retry limit exceeded")).isTrue();

        assertThat(claims.claim(1, Duration.ofMinutes(1))).isEmpty();
        assertThat(statusOf(first.id())).isEqualTo("FAILED_FINAL");
        assertThat(jdbc.queryForObject(
                "select completed_at is not null from meeting_suggestion_scans where id = ?",
                Boolean.class, first.id())).isTrue();
    }

    @Test
    @DisplayName("COMPLETED 는 더 이상 claim 되지 않는다")
    void completedIsNotClaimable() {
        insertScan(givenDirectRoom());
        ClaimedScan scan = claims.claim(1, Duration.ofMinutes(1)).getFirst();
        assertThat(claims.markCompleted(scan)).isTrue();

        assertThat(claims.claim(1, Duration.ofMinutes(1))).isEmpty();
    }

    // ── DB invariant ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("DB UNIQUE (room_id, source_date) 가 최종 방어선이다")
    void databaseRejectsDuplicateScan() {
        long room = givenDirectRoom();
        insertScan(room);

        assertThatThrownBy(() -> insertScan(room))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_meeting_suggestion_scan");

        assertThat(countScans()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB UNIQUE fingerprint 가 Suggestion 최종 방어선이다")
    void databaseRejectsDuplicateFingerprint() {
        insertScan(givenDirectRoom());
        long scanId = scanId();
        insertSuggestion(scanId, "fp-1");

        assertThatThrownBy(() -> insertSuggestion(scanId, "fp-1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_meeting_suggestion_fingerprint");
    }

    @Test
    @DisplayName("계약 밖 Scan 상태는 거부된다")
    void databaseRejectsUnknownScanStatus() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into meeting_suggestion_scans
                    (room_id, source_date, reference_date, status, next_retry_at)
                values (?, ?, ?, 'NO_IDEA', now())
                """, givenDirectRoom(), Date.valueOf(SOURCE_DATE), Date.valueOf(REFERENCE_DATE)))
                .hasMessageContaining("ck_meeting_suggestion_scan_status");
    }

    @Test
    @DisplayName("V41 이 scheduler TEXT 조회 partial index 를 만든다")
    void v41CreatesSchedulerTextIndex() {
        List<String> indexes = jdbc.queryForList("""
                select indexname from pg_indexes
                 where schemaname = current_schema()
                   and tablename = 'chat_messages'
                """, String.class);

        assertThat(indexes).contains("idx_chat_message_scheduler_text");
        // 기존 인덱스는 삭제하지 않는다.
        assertThat(indexes).contains("idx_chat_message_room_id");
    }

    @Test
    @DisplayName("V41 과 최신 dev V40 이 함께 적용된다 (migration 순서)")
    void v41AndV40ApplyTogether() {
        List<String> applied = jdbc.queryForList("""
                select version from flyway_schema_history order by installed_rank
                """, String.class);

        assertThat(applied).contains("40", "41");
        assertThat(applied.indexOf("40")).isLessThan(applied.indexOf("41"));
    }

    @Test
    @DisplayName("V41 이 card_drafts fallback_reason CHECK 에 INVALID_REQUEST 를 허용한다")
    void cardDraftAcceptsInvalidRequestFallback() {
        insertUser(1L);
        insertPet(11L, 1L);
        long room = directRoom(11L, 22L);
        jdbc.update("""
                insert into card_drafts (room_id, requested_by_pet_id, fallback_reason)
                values (?, 11, 'INVALID_REQUEST')
                """, room);

        assertThat(jdbc.queryForObject(
                "select fallback_reason from card_drafts", String.class))
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("방 삭제 시 Scan/Suggestion 이 함께 지워져 기존 room cleanup 정책과 충돌하지 않는다")
    void roomDeletionCascadesToScanAndSuggestion() {
        long room = givenDirectRoom();
        insertScan(room);
        insertSuggestion(scanId(), "fp-cascade");

        jdbc.update("delete from chat_rooms where id = ?", room);

        assertThat(countScans()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from meeting_suggestions", Long.class)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long directRoom(long petLowId, long petHighId) {
        return insertRoom("DIRECT", petLowId, petHighId);
    }

    private long groupRoom() {
        return insertRoom("GROUP", null, null);
    }

    private long givenDirectRoom() {
        return directRoom(1L, 2L);
    }

    private long insertRoom(String type, Long petLowId, Long petHighId) {
        jdbc.update("""
                insert into chat_rooms (type, status, origin, pet_low_id, pet_high_id)
                values (CAST(? AS VARCHAR), 'ACTIVE', 'GREETING', ?, ?)
                """, type, petLowId, petHighId);
        return jdbc.queryForObject("select max(id) from chat_rooms", Long.class);
    }

    private void participant(long roomId, long petId, Instant leftAt) {
        jdbc.update("""
                insert into chat_room_participants (room_id, pet_id, left_at)
                values (?, ?, ?)
                """, roomId, petId, leftAt == null ? null : java.sql.Timestamp.from(leftAt));
    }

    /** userA 와 userB 사이 차단. 두 user 와 각 소유 pet 은 호출 전에 준비돼 있어야 한다. */
    private void blockBetween(long userA, long userB) {
        jdbc.update("""
                insert into user_blocks (blocker_user_id, blocked_user_id)
                values (?, ?)
                """, userA, userB);
    }

    private void insertUser(long userId) {
        jdbc.update("""
                insert into users (id, email, password_hash, nickname, public_tag, neighborhood_code)
                values (?, ?, 'encoded', ?, ?, ?)
                """, userId, "user" + userId + "@test.com", "사용자" + userId,
                "user" + userId + "#" + String.format("%04d", userId), NEIGHBORHOOD);
    }

    private void insertPet(long petId, long ownerUserId) {
        jdbc.update("""
                insert into pets (id, owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, ?, 'ACTIVE')
                """, petId, ownerUserId,
                "pet" + petId + "#" + String.format("%04d", petId), "펫" + petId);
    }

    private void insertScan(long roomId) {
        // next_retry_at 를 과거로 넣는다. DB 서버 시계가 JVM 보다 앞서 있어도
        // claim 조건(next_retry_at <= JVM now)이 즉시 성립하게 하려는 것이다.
        jdbc.update("""
                insert into meeting_suggestion_scans (room_id, source_date, reference_date, next_retry_at)
                values (?, ?, ?, now() - interval '1 second')
                """, roomId, Date.valueOf(SOURCE_DATE), Date.valueOf(REFERENCE_DATE));
    }

    private void insertSuggestion(long scanId, String fingerprint) {
        jdbc.update("""
                insert into meeting_suggestions (scan_id, fingerprint, card_type, meet_date, meet_time, place_text)
                values (?, ?, 'WALK', '2026-08-26', '19:00', '중앙공원')
                """, scanId, fingerprint);
    }

    private long scanId() {
        return jdbc.queryForObject("select max(id) from meeting_suggestion_scans", Long.class);
    }

    private String statusOf(long scanId) {
        return jdbc.queryForObject(
                "select status from meeting_suggestion_scans where id = ?", String.class, scanId);
    }

    private long countScans() {
        return jdbc.queryForObject("select count(*) from meeting_suggestion_scans", Long.class);
    }

    private int createAfter(CountDownLatch start) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent creation start timed out");
        }
        return claims.createScans(SOURCE_DATE, REFERENCE_DATE);
    }

    private List<ClaimedScan> claimAfter(CountDownLatch start) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent claim start timed out");
        }
        return claims.claim(1, Duration.ofMinutes(1));
    }
}
