package itda.meetingverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.chat.domain.RoomOrigin;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.service.MeetingCardService;
import itda.meetingverification.domain.MeetingVerificationApiStatus;
import itda.meetingverification.domain.MeetingVerificationMethod;
import itda.meetingverification.dto.ConfirmationCodeCreateResult;
import itda.meetingverification.dto.ConfirmationCodeResult;
import itda.meetingverification.dto.MeetingVerificationStatusResponse;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import itda.meetingverification.service.ConfirmationCodeProperties;
import itda.meetingverification.service.MeetingConfirmationCodeService;
import itda.meetingverification.service.MeetingVerificationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V45 {@code meeting_confirmation_codes} 의 참여자 DB 불변식과 CODE 확정 원자화를 실제
 * PostgreSQL 에서 검증한다.
 *
 * <p>발급자·검증자가 카드 참여 Pet 인지 composite FK 로, 검증자·발급자 동일 금지와 생성자 확인
 * 순서를 CHECK 로 강제하는지 본다. CODE Meeting 확정 시 미확정 SUBMITTED·CODE_REQUIRED
 * verification 이 ACCEPTED 로 전이되고 raw GPS 가 scrub 되는지 실제 DB 상태로 확인한다.
 * Flyway migration 의 FK/CHECK 는 H2(ddl-auto) 에서 만들어지지 않으므로 PostgreSQL 에서만
 * 검증한다.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class MeetingConfirmationCodePostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ChatRoomService chatRoomService;
    @Autowired
    private MeetingCardService meetingCardService;
    @Autowired
    private MeetingConfirmationCodeService confirmationCodeService;
    @Autowired
    private MeetingVerificationService meetingVerificationService;
    @Autowired
    private ConfirmationCodeProperties confirmationCodeProperties;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final long PET_3 = 33L;
    private static final String NEIGHBORHOOD = "4113111500";

    private long cardId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate meeting_confirmation_codes, meeting_verification_requests,
                         meeting_verifications, meetings, meeting_participants, meeting_cards,
                         card_drafts, chat_messages, chat_room_participants, chat_rooms, pets, users,
                         neighborhoods
                restart identity cascade
                """);
        insertNeighborhood();
        insertUser(USER_1);
        insertUser(USER_2);
        insertPet(PET_1, USER_1);
        insertPet(PET_2, USER_2);
        insertPet(PET_3, USER_2);
        setActivePet(USER_1, PET_1);
        setActivePet(USER_2, PET_2);

        long roomId = chatRoomService.ensureDirectRoom(PET_1, PET_2, RoomOrigin.GREETING).roomId();
        cardId = meetingCardService.confirm(USER_1,
                new MeetingCardCreateRequest(roomId, null, MeetingCardType.WALK,
                        "중앙공원", Instant.now())).cardId();
    }

    @Test
    @DisplayName("비참여 Pet issuer insert 는 composite FK 로 거절된다")
    void rejectsNonParticipantIssuer() {
        assertThatThrownBy(() -> insertCode(cardId, PET_3, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("비참여 Pet verifier insert 는 composite FK 로 거절된다")
    void rejectsNonParticipantVerifier() {
        assertThatThrownBy(() -> insertCode(cardId, PET_1, PET_3,
                Instant.now().plusSeconds(60), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("issuer == verifier 는 CHECK 로 거절된다")
    void rejectsIssuerEqualToVerifier() {
        assertThatThrownBy(() -> insertCode(cardId, PET_1, PET_1,
                Instant.now().plusSeconds(60), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("verifier 없이 issuerConfirmedAt 은 CHECK 로 거절된다")
    void rejectsIssuerConfirmationWithoutVerifier() {
        assertThatThrownBy(() -> insertCode(cardId, PET_1, null, null, Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CODE 확정 시 SUBMITTED·CODE_REQUIRED 를 ACCEPTED 로 전이하고 raw 를 scrub 한다")
    void codeConfirmAcceptsAndScrubsUnconfirmedVerifications() {
        insertVerification(1L, cardId, PET_1, "SUBMITTED", 37.5665, 126.978, 24.5, Instant.now());
        insertVerification(2L, cardId, PET_2, "CODE_REQUIRED", null, null, null, null);

        ConfirmationCodeCreateResult issued = confirmationCodeService.issue(USER_1, cardId);
        confirmationCodeService.verify(USER_2, cardId, issued.code());
        ConfirmationCodeResult confirmed = confirmationCodeService.confirm(USER_1, cardId);

        assertThat(confirmed.verificationMethod()).isEqualTo(MeetingVerificationMethod.CODE);
        assertThat(countOf("meetings")).isEqualTo(1);
        assertThat(queryStatus(1L)).isEqualTo("ACCEPTED");
        assertThat(queryStatus(2L)).isEqualTo("ACCEPTED");
        assertThat(queryRaw(1L, "latitude")).isNull();
        assertThat(queryRaw(1L, "longitude")).isNull();
        assertThat(queryRaw(1L, "accuracy_meters")).isNull();
        assertThat(queryRaw(1L, "captured_at")).isNull();
        assertThat(queryRaw(2L, "latitude")).isNull();

        MeetingVerificationStatusResponse status =
                meetingVerificationService.getStatus(USER_1, cardId);
        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.CODE_CONFIRMED);
        assertThat(status.confirmed()).isTrue();
        assertThat(status.codeRequired()).isFalse();
    }

    @Test
    @DisplayName("동시 wrong-code 요청은 maxAttempts 를 우회하지 못하고 코드를 무효화한다")
    void concurrentWrongCodesCannotBypassMaxAttempts() throws Exception {
        insertVerification(1L, cardId, PET_2, "CODE_REQUIRED", null, null, null, null);
        ConfirmationCodeCreateResult issued = confirmationCodeService.issue(USER_1, cardId);
        String wrongCode = issued.code().equals("0000") ? "9999" : "0000";

        List<Object> results = runConcurrently(10,
                i -> confirmationCodeService.verify(USER_2, cardId, wrongCode));
        List<ErrorCode> errors = errorCodes(results);

        assertThat(errors).hasSize(10)
                .containsOnly(ErrorCode.MEETING_CODE_MISMATCH,
                        ErrorCode.MEETING_CODE_ATTEMPTS_EXCEEDED,
                        ErrorCode.MEETING_CODE_NOT_AVAILABLE);
        assertThat(errors.stream()
                .filter(ErrorCode.MEETING_CODE_MISMATCH::equals)
                .count()).isEqualTo(confirmationCodeProperties.maxAttempts() - 1L);
        assertThat(errors.stream()
                .filter(ErrorCode.MEETING_CODE_ATTEMPTS_EXCEEDED::equals)
                .count()).isEqualTo(1);

        int failedAttempts = jdbcTemplate.queryForObject(
                "select failed_attempts from meeting_confirmation_codes where meeting_card_id = ?",
                Integer.class, cardId);
        assertThat(failedAttempts).isEqualTo(confirmationCodeProperties.maxAttempts());
        assertThat(jdbcTemplate.queryForObject(
                "select invalidated_at from meeting_confirmation_codes where meeting_card_id = ?",
                Object.class, cardId)).isNotNull();
        assertThatThrownBy(() -> confirmationCodeService.verify(USER_2, cardId, issued.code()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("verifier 완료 후 issuer confirm 동시 요청은 CODE Meeting 정확히 1건으로 수렴한다")
    void concurrentIssuerConfirmsCreateExactlyOneCodeMeeting() throws Exception {
        insertVerification(1L, cardId, PET_1, "SUBMITTED", 37.5665, 126.978, 24.5, Instant.now());
        insertVerification(2L, cardId, PET_2, "CODE_REQUIRED", null, null, null, null);
        ConfirmationCodeCreateResult issued = confirmationCodeService.issue(USER_1, cardId);
        confirmationCodeService.verify(USER_2, cardId, issued.code());

        List<Object> results = runConcurrently(2,
                i -> confirmationCodeService.confirm(USER_1, cardId));

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(ConfirmationCodeResult.class);
            ConfirmationCodeResult confirmed = (ConfirmationCodeResult) result;
            assertThat(confirmed.status()).isEqualTo("CONFIRMED");
            assertThat(confirmed.verificationMethod()).isEqualTo(MeetingVerificationMethod.CODE);
        });

        assertThat(countOf("meetings")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select verification_method from meetings where meeting_card_id = ?",
                String.class, cardId)).isEqualTo("CODE");
        assertThat(queryStatus(1L)).isEqualTo("ACCEPTED");
        assertThat(queryStatus(2L)).isEqualTo("ACCEPTED");
        assertThat(queryRaw(1L, "latitude")).isNull();
    }

    @Test
    @DisplayName("GPS 제출과 CODE confirm 동시 요청은 meetings 정확히 1건을 유지한다")
    void concurrentGpsSubmitAndCodeConfirmCreateExactlyOneMeeting() throws Exception {
        insertVerification(1L, cardId, PET_1, "SUBMITTED", 37.5665, 126.978, 24.5, Instant.now());
        insertVerification(2L, cardId, PET_2, "CODE_REQUIRED", null, null, null, null);
        ConfirmationCodeCreateResult issued = confirmationCodeService.issue(USER_1, cardId);
        confirmationCodeService.verify(USER_2, cardId, issued.code());

        List<Object> results = runConcurrently(2, i -> {
            if (i == 0) {
                return confirmationCodeService.confirm(USER_1, cardId);
            }
            return meetingVerificationService.submit(USER_1, cardId,
                    new MeetingVerificationSubmitCommand(
                            UUID.randomUUID(), 37.5665, 126.978, 24.5,
                            Instant.now().minus(10, ChronoUnit.SECONDS)));
        });

        assertThat(countOf("meetings")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select verification_method from meetings where meeting_card_id = ?",
                String.class, cardId)).isEqualTo("CODE");
        assertThat(results.stream()
                .filter(ConfirmationCodeResult.class::isInstance)
                .map(ConfirmationCodeResult.class::cast))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo("CONFIRMED");
                    assertThat(result.verificationMethod()).isEqualTo(MeetingVerificationMethod.CODE);
                });
        Object gpsOutcome = results.stream()
                .filter(result -> !(result instanceof ConfirmationCodeResult))
                .findFirst()
                .orElseThrow();
        if (gpsOutcome instanceof itda.meetingverification.dto.MeetingVerificationResult gpsResult) {
            assertThat(gpsResult.confirmed()).isFalse();
            assertThat(gpsResult.verificationMethod()).isNull();
        } else {
            assertThat(gpsOutcome).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) gpsOutcome).getErrorCode())
                    .isEqualTo(ErrorCode.MEETING_ALREADY_CONFIRMED);
        }
    }

    @Test
    @DisplayName("A/B 동시 재발급은 최초 issuer A만 성공하고 하나의 유효 cycle로 수렴한다")
    void concurrentReissueRejectsNonIssuerAndKeepsSingleIssuerCycle() throws Exception {
        insertVerification(1L, cardId, PET_2, "CODE_REQUIRED", null, null, null, null);
        confirmationCodeService.issue(USER_1, cardId);
        expireCurrentCode();

        List<Object> results = runConcurrently(2, i -> i == 0
                ? confirmationCodeService.issue(USER_1, cardId)
                : confirmationCodeService.issue(USER_2, cardId));

        assertThat(results.stream().filter(ConfirmationCodeCreateResult.class::isInstance))
                .singleElement();
        assertThat(results.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .map(BusinessException::getErrorCode))
                .containsExactly(ErrorCode.MEETING_CODE_REISSUE_ISSUER_FORBIDDEN);
        assertThat(countOf("meeting_confirmation_codes")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select issuer_pet_id from meeting_confirmation_codes where meeting_card_id = ?",
                Long.class, cardId)).isEqualTo(PET_1);
        assertThat(countOf("meetings")).isZero();
    }

    @Test
    @DisplayName("issuer A의 동시 재발급은 issuer를 유지하고 DB의 하나의 유효 cycle로 수렴한다")
    void concurrentIssuerReissuesConvergeToOneUsableCycle() throws Exception {
        insertVerification(1L, cardId, PET_2, "CODE_REQUIRED", null, null, null, null);
        confirmationCodeService.issue(USER_1, cardId);
        expireCurrentCode();

        List<Object> results = runConcurrently(2,
                i -> confirmationCodeService.issue(USER_1, cardId));

        assertThat(results).allSatisfy(result ->
                assertThat(result).isInstanceOf(ConfirmationCodeCreateResult.class));
        assertThat(countOf("meeting_confirmation_codes")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select issuer_pet_id from meeting_confirmation_codes where meeting_card_id = ?",
                Long.class, cardId)).isEqualTo(PET_1);
        String persistedHash = jdbcTemplate.queryForObject(
                "select code_hash from meeting_confirmation_codes where meeting_card_id = ?",
                String.class, cardId);
        assertThat(results.stream()
                .map(ConfirmationCodeCreateResult.class::cast)
                .filter(result -> passwordEncoder.matches(result.code(), persistedHash)))
                .hasSize(1);
    }

    @Test
    @DisplayName("A 재발급 후 B verify, A confirm은 CODE Meeting 하나를 생성한다")
    void reissuedCodeCompletesWithOriginalIssuer() {
        insertVerification(1L, cardId, PET_1, "SUBMITTED", 37.5665, 126.978, 24.5, Instant.now());
        insertVerification(2L, cardId, PET_2, "CODE_REQUIRED", null, null, null, null);
        confirmationCodeService.issue(USER_1, cardId);
        expireCurrentCode();

        ConfirmationCodeCreateResult reissued = confirmationCodeService.issue(USER_1, cardId);
        confirmationCodeService.verify(USER_2, cardId, reissued.code());
        ConfirmationCodeResult confirmed = confirmationCodeService.confirm(USER_1, cardId);

        assertThat(confirmed.verificationMethod()).isEqualTo(MeetingVerificationMethod.CODE);
        assertThat(countOf("meetings")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select issuer_pet_id from meeting_confirmation_codes where meeting_card_id = ?",
                Long.class, cardId)).isEqualTo(PET_1);
    }

    private List<Object> runConcurrently(int workers, java.util.function.IntFunction<Object> action)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return action.apply(index);
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent workers did not become ready");
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<ErrorCode> errorCodes(List<Object> results) {
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(BusinessException.class));
        return results.stream()
                .map(BusinessException.class::cast)
                .map(BusinessException::getErrorCode)
                .toList();
    }

    private void expireCurrentCode() {
        jdbcTemplate.update("""
                update meeting_confirmation_codes
                   set expires_at = now() - interval '1 second'
                 where meeting_card_id = ?
                """, cardId);
    }

    private void insertCode(long cardId, long issuerPetId, Long verifierPetId,
                            Instant verifierConfirmedAt, Instant issuerConfirmedAt) {
        jdbcTemplate.update("""
                insert into meeting_confirmation_codes
                    (meeting_card_id, issuer_pet_id, code_hash, expires_at,
                     verifier_pet_id, verifier_confirmed_at, issuer_confirmed_at)
                values (?, ?, 'hash', ?, ?, ?, ?)
                """, cardId, issuerPetId, Timestamp.from(Instant.now().plusSeconds(300)),
                verifierPetId,
                verifierConfirmedAt == null ? null : Timestamp.from(verifierConfirmedAt),
                issuerConfirmedAt == null ? null : Timestamp.from(issuerConfirmedAt));
    }

    private void insertVerification(long id, long cardId, long petId, String status,
                                    Double latitude, Double longitude, Double accuracy,
                                    Instant capturedAt) {
        jdbcTemplate.update("""
                insert into meeting_verifications
                    (id, meeting_card_id, participant_pet_id, status, latitude, longitude,
                     accuracy_meters, captured_at, submitted_at, client_request_id,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now(), ?, now(), now())
                """, id, cardId, petId, status, latitude, longitude, accuracy,
                capturedAt == null ? null : Timestamp.from(capturedAt), UUID.randomUUID());
    }

    private String queryStatus(long verificationId) {
        return jdbcTemplate.queryForObject(
                "select status from meeting_verifications where id = ?",
                String.class, verificationId);
    }

    private Object queryRaw(long verificationId, String column) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from meeting_verifications where id = ?",
                Object.class, verificationId);
    }

    private int countOf(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void insertNeighborhood() {
        jdbcTemplate.update("""
                insert into neighborhoods (code, sido_name, sigungu_name, eupmyeondong_name)
                values ('4113111500', '경기도', '성남시', '수내동')
                """);
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
                petId, ownerUserId,
                "pet" + petId + "#" + String.format("%04d", petId), "펫" + petId);
    }

    private void setActivePet(long userId, long petId) {
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", petId, userId);
    }
}
