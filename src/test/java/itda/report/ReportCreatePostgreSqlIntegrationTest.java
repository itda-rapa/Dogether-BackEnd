package itda.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.chat.domain.RoomOrigin;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.report.domain.ReportReason;
import itda.report.domain.ReportStatus;
import itda.report.dto.ReportCreateRequest;
import itda.report.dto.ReportResponse;
import itda.report.service.ReportService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
class ReportCreatePostgreSqlIntegrationTest {

    private static final int WORKERS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ReportService reportService;

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final String NEIGHBORHOOD = "4113111500";

    private long roomId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate reports, chat_messages, chat_room_participants,
                         chat_rooms, pets, users
                restart identity cascade
                """);
        insertUser(USER_1);
        insertUser(USER_2);
        insertPet(PET_1, USER_1);
        insertPet(PET_2, USER_2);
        setActivePet(USER_1, PET_1);
        setActivePet(USER_2, PET_2);

        roomId = chatRoomService.ensureDirectRoom(PET_1, PET_2, RoomOrigin.GREETING).roomId();
    }

    private void insertUser(long userId) {
        jdbcTemplate.update("""
                        insert into users (
                            id, email, password_hash, nickname, public_tag,
                            role, account_status, neighborhood_code
                        ) values (?, ?, 'encoded', ?, ?, 'USER', 'ACTIVE', ?)
                        """,
                userId,
                "user" + userId + "@test.com",
                "사용자" + userId,
                "user" + userId + "#" + String.format("%04d", userId),
                NEIGHBORHOOD);
    }

    private void insertPet(long petId, long ownerUserId) {
        jdbcTemplate.update("""
                        insert into pets (id, owner_user_id, public_tag, nickname, status)
                        values (?, ?, ?, ?, 'ACTIVE')
                        """,
                petId,
                ownerUserId,
                "pet" + petId + "#" + String.format("%04d", petId),
                "펫" + petId);
    }

    private void setActivePet(long userId, long petId) {
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", petId, userId);
    }

    private ReportCreateRequest request(ReportReason reason, String detail) {
        return new ReportCreateRequest(roomId, reason, detail);
    }

    // ── 정상 신고 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 신고 → 201, status=OPEN")
    void createReportReturnsCreatedWithOpenStatus() {
        ReportService.CreateReportResult result =
                reportService.createReport(USER_1, request(ReportReason.HARASSMENT, null));

        assertThat(result.created()).isTrue();
        ReportResponse report = result.report();
        assertThat(report.reportId()).isNotNull();
        assertThat(report.roomId()).isEqualTo(roomId);
        assertThat(report.reasonCode()).isEqualTo(ReportReason.HARASSMENT);
        assertThat(report.status()).isEqualTo(ReportStatus.OPEN);
        assertThat(report.createdAt()).isNotNull();
        assertThat(report.reporterUserId()).isEqualTo(USER_1);
        assertThat(report.reportedUserId()).isEqualTo(USER_2);
    }

    @Test
    @DisplayName("피신고자가 방의 상대 참가자로 정확히 저장됨")
    void reportedUserIsRoomCounterpart() {
        ReportService.CreateReportResult result =
                reportService.createReport(USER_1, request(ReportReason.SPAM, null));

        assertThat(result.report().reportedUserId()).isEqualTo(USER_2);
    }

    // ── 멱등 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("동일 reporter·room 재신고 → 200, 같은 reportId, 새 행 생기지 않음")
    void resubmitReturnsExistingReport() {
        ReportService.CreateReportResult first =
                reportService.createReport(USER_1, request(ReportReason.HARASSMENT, "first detail"));
        assertThat(first.created()).isTrue();

        ReportService.CreateReportResult second =
                reportService.createReport(USER_1, request(ReportReason.SPAM, "different"));
        assertThat(second.created()).isFalse();
        assertThat(second.report().reportId()).isEqualTo(first.report().reportId());

        assertThat(countOf("reports")).isEqualTo(1);
    }

    @Test
    @DisplayName("재신고의 reasonCode·detail이 무시되고 최초 내용 유지")
    void resubmitIgnoresNewReasonAndDetail() {
        ReportService.CreateReportResult first =
                reportService.createReport(USER_1, request(ReportReason.HARASSMENT, "original detail"));

        reportService.createReport(USER_1, request(ReportReason.SPAM, "different detail"));

        ReportResponse existing = jdbcTemplate.queryForObject(
                "select reason_code, detail from reports where id = ?",
                (rs, rowNum) -> new ReportResponse(
                        null, null, null, null,
                        ReportReason.valueOf(rs.getString("reason_code")),
                        rs.getString("detail"),
                        null, null, null, null, null),
                first.report().reportId());

        assertThat(existing.reasonCode()).isEqualTo(ReportReason.HARASSMENT);
        assertThat(existing.detail()).isEqualTo("original detail");
    }

    // ── 동시성 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("동시에 같은 reporter·room으로 신고해도 행이 하나만 생김")
    void concurrentSameReporterRoomResultsInOneRow() throws Exception {
        List<ReportService.CreateReportResult> results = runConcurrently(
                (Callable<ReportService.CreateReportResult>)
                        () -> reportService.createReport(USER_1, request(ReportReason.SPAM, null)));

        assertThat(results).hasSize(WORKERS);
        long createdCount = results.stream().filter(ReportService.CreateReportResult::created).count();
        assertThat(createdCount).isEqualTo(1);

        // All must return the same reportId
        Long firstId = results.get(0).report().reportId();
        assertThat(results).allMatch(r -> r.report().reportId().equals(firstId));

        assertThat(countOf("reports")).isEqualTo(1);
    }

    // ── 검증 실패 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("reasonCode=OTHER + detail 공백 → 400")
    void otherReasonWithoutDetailThrowsValidationFailed() {
        org.junit.jupiter.api.Assertions.assertThrows(
                itda.common.exception.BusinessException.class,
                () -> reportService.createReport(USER_1, request(ReportReason.OTHER, null)));
    }

    @Test
    @DisplayName("reasonCode=OTHER + detail 정상 → 201")
    void otherReasonWithDetailSucceeds() {
        ReportService.CreateReportResult result =
                reportService.createReport(USER_1, request(ReportReason.OTHER, "정당한 상세 사유"));

        assertThat(result.created()).isTrue();
        assertThat(result.report().reasonCode()).isEqualTo(ReportReason.OTHER);
        assertThat(result.report().detail()).isEqualTo("정당한 상세 사유");
    }

    @Test
    @DisplayName("detail 501자 → 400")
    void detailTooLongThrowsValidationFailed() {
        String longDetail = "a".repeat(501);
        org.junit.jupiter.api.Assertions.assertThrows(
                itda.common.exception.BusinessException.class,
                () -> reportService.createReport(USER_1, request(ReportReason.SPAM, longDetail)));
    }

    @Test
    @DisplayName("detail 500자 → 201")
    void detailMaxLengthSucceeds() {
        String maxDetail = "a".repeat(500);
        ReportService.CreateReportResult result =
                reportService.createReport(USER_1, request(ReportReason.SPAM, maxDetail));

        assertThat(result.created()).isTrue();
    }

    // ── 참가자 검증 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("방 참가자가 아니면 → 404 CHAT_ROOM_NOT_FOUND")
    void nonParticipantReturnsNotFound() {
        insertUser(3L);
        insertPet(33L, 3L);
        setActivePet(3L, 33L);

        org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> reportService.createReport(3L, request(ReportReason.SPAM, null)));
    }

    @Test
    @DisplayName("참가자 테이블에 잘못 들어간 DIRECT pair 외 Pet은 신고할 수 없음")
    void participantOutsideDirectPairReturnsNotFound() {
        insertUser(3L);
        insertPet(33L, 3L);
        setActivePet(3L, 33L);
        jdbcTemplate.update("""
                        insert into chat_room_participants (room_id, pet_id)
                        values (?, ?)
                        """,
                roomId, 33L);

        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> reportService.createReport(3L, request(ReportReason.SPAM, null)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
        assertThat(countOf("reports")).isZero();
    }

    @Test
    @DisplayName("방을 떠난 Pet이면 → 404 CHAT_ROOM_NOT_FOUND")
    void petThatLeftRoomReturnsNotFound() {
        jdbcTemplate.update("""
                        update chat_room_participants
                           set left_at = now()
                         where room_id = ? and pet_id = ?
                        """,
                roomId, PET_1);

        org.junit.jupiter.api.Assertions.assertThrows(
                itda.common.exception.BusinessException.class,
                () -> reportService.createReport(USER_1, request(ReportReason.SPAM, null)));
    }

    @Test
    @DisplayName("존재하지 않는 방 → 404 CHAT_ROOM_NOT_FOUND")
    void nonexistentRoomReturnsNotFound() {
        ReportCreateRequest req = new ReportCreateRequest(9999L, ReportReason.SPAM, null);
        org.junit.jupiter.api.Assertions.assertThrows(
                itda.common.exception.BusinessException.class,
                () -> reportService.createReport(USER_1, req));
    }

    @Test
    @DisplayName("Active Pet 없으면 → 403 ACTIVE_PET_REQUIRED")
    void noActivePetReturnsForbidden() {
        jdbcTemplate.update("update users set active_pet_id = null where id = ?", USER_1);

        org.junit.jupiter.api.Assertions.assertThrows(
                itda.common.exception.BusinessException.class,
                () -> reportService.createReport(USER_1, request(ReportReason.SPAM, null)));
    }

    // ── 자기 신고 불가 ────────────────────────────────────────────────────

    @Test
    @DisplayName("자기 자신 신고 불가 (reporter_user_id <> reported_user_id)")
    void selfReportNotAllowed() {
        // Create a room where both participants share the same user
        // PET_1 belongs to USER_1, insert PET_3 also belonging to USER_1
        insertPet(33L, USER_1);
        setActivePet(USER_1, PET_1); // still uses PET_1 for report

        long selfRoomId = chatRoomService.ensureDirectRoom(PET_1, 33L, RoomOrigin.GREETING).roomId();

        ReportCreateRequest req = new ReportCreateRequest(selfRoomId, ReportReason.SPAM, null);
        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> reportService.createReport(USER_1, req));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REPORT_SELF_FORBIDDEN);
    }

    // ── DB 최종 방어선 ────────────────────────────────────────────────────

    @Test
    @DisplayName("DB가 동일 사용자 신고를 ck_report_self로 거부한다")
    void databaseRejectsSelfReport() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into reports (
                            reporter_user_id, reporter_pet_id,
                            reported_user_id, reported_pet_id,
                            room_id, reason_code
                        ) values (?, ?, ?, ?, ?, 'SPAM')
                        """,
                USER_1, PET_1, USER_1, PET_1, roomId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_report_self");
    }

    @Test
    @DisplayName("DB가 상세 사유 없는 OTHER 신고를 ck_report_other_detail로 거부한다")
    void databaseRejectsOtherWithoutDetail() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into reports (
                            reporter_user_id, reporter_pet_id,
                            reported_user_id, reported_pet_id,
                            room_id, reason_code, detail
                        ) values (?, ?, ?, ?, ?, 'OTHER', null)
                        """,
                USER_1, PET_1, USER_2, PET_2, roomId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_report_other_detail");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private <T> List<T> runConcurrently(Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < WORKERS; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.call();
                }));
            }
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int countOf(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
