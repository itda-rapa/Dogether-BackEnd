package itda.meetingverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.chat.domain.RoomOrigin;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.service.MeetingCardService;
import itda.meetingverification.domain.MeetingVerificationMethod;
import itda.meetingverification.domain.MeetingVerificationStatus;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import itda.meetingverification.repository.MeetingRepository;
import itda.meetingverification.repository.MeetingVerificationRepository;
import itda.meetingverification.service.MeetingVerificationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * GPS 만남 위치 제출·양쪽 확인(#111)을 실제 PostgreSQL 에서 검증한다.
 *
 * <p>단위 테스트로 증명할 수 없는 것만 본다: GPS 확정 시 Meeting 정확히 1건, LOW_ACCURACY·
 * 거리 초과·시간 간격 초과 시 0건, clientRequestId 전역 경합 409, 카드 취소·GPS 제출 경합,
 * DB 제약. Location 의 좌표·freshness·accuracy 판정은 {@code app.location} 설정으로 실동작한다.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
        "app.meeting-verification.distance-limit-meters=100",
        "app.meeting-verification.submission-interval=30s"
})
class MeetingVerificationPostgreSqlIntegrationTest {

    private static final int WORKERS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MeetingVerificationService meetingVerificationService;
    @Autowired
    private MeetingCardService meetingCardService;
    @Autowired
    private ChatRoomService chatRoomService;
    @Autowired
    private MeetingVerificationRepository meetingVerificationRepository;
    @Autowired
    private MeetingRepository meetingRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final String NEIGHBORHOOD = "4113111500";

    private long roomId;
    private long cardId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate meeting_verifications, meetings, meeting_participants,
                         meeting_cards, card_drafts, chat_messages, chat_room_participants,
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
        cardId = meetingCardService.confirm(USER_1, request(roomId)).cardId();
    }

    // ── 제출 저장·GPS 확정 ─────────────────────────────────────────────────

    @Test
    @DisplayName("첫 ACCEPTABLE 제출은 Verification 1행·Meeting 0행·confirmed=false")
    void firstAcceptableSubmitStoresVerificationOnly() {
        UUID clientRequestId = UUID.randomUUID();

        MeetingVerificationResult result = submit(USER_1, clientRequestId);

        assertThat(result.cardId()).isEqualTo(cardId);
        assertThat(result.submittedPetId()).isEqualTo(PET_1);
        assertThat(result.counterpartSubmitted()).isFalse();
        assertThat(result.confirmed()).isFalse();
        assertThat(result.meetingId()).isNull();

        assertThat(countOf("meetings")).isZero();
        assertThat(countOf("meeting_verifications")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select status from meeting_verifications where id = ?
                """, String.class, jdbcTemplate.queryForObject(
                "select min(id) from meeting_verifications", Long.class)))
                .isEqualTo(MeetingVerificationStatus.SUBMITTED.name());
    }

    @Test
    @DisplayName("양쪽 ACCEPTABLE 제출은 GPS Meeting 1건을 만들고 confirmed=true")
    void bothAcceptableSubmissionsConfirmGpsMeeting() {
        submit(USER_1, UUID.randomUUID());
        MeetingVerificationResult second = submit(USER_2, UUID.randomUUID());

        assertThat(second.counterpartSubmitted()).isTrue();
        assertThat(second.confirmed()).isTrue();
        assertThat(second.meetingId()).isNotNull();
        assertThat(second.verificationMethod()).isEqualTo(MeetingVerificationMethod.GPS);
        assertThat(second.confirmedAt()).isNotNull();

        assertThat(countOf("meetings")).isEqualTo(1);
        assertThat(countOf("meeting_verifications")).isEqualTo(2);
    }

    @Test
    @DisplayName("LOW_ACCURACY 제출은 CODE_REQUIRED 로 저장되고 Meeting 은 0건이다")
    void lowAccuracyDoesNotCreateMeeting() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 60.0);

        assertThat(countOf("meetings")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_verifications where participant_pet_id = ?",
                String.class, PET_1)).isEqualTo(MeetingVerificationStatus.CODE_REQUIRED.name());

        // 상대가 ACCEPTABLE 이어도 한쪽 CODE_REQUIRED 면 GPS Meeting 을 만들지 않는다.
        MeetingVerificationResult second = submit(USER_2, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        assertThat(second.confirmed()).isFalse();
        assertThat(countOf("meetings")).isZero();
    }

    @Test
    @DisplayName("거리 초과는 MEETING_DISTANCE_EXCEEDED 이고 Meeting 은 0건이다")
    void distanceExceededDoesNotCreateMeeting() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);

        assertThatThrownBy(() -> submit(USER_2, UUID.randomUUID(), 37.9000, 127.1000, 24.5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_DISTANCE_EXCEEDED);

        assertThat(countOf("meetings")).isZero();
    }

    @Test
    @DisplayName("제출 시각 간격 초과는 MEETING_TIME_WINDOW_EXCEEDED 이고 Meeting 은 0건이다")
    void intervalExceededDoesNotCreateMeeting() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5,
                Instant.now().minus(2, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> submit(USER_2, UUID.randomUUID(), 37.5665, 126.978, 24.5,
                Instant.now().minus(10, ChronoUnit.SECONDS)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        assertThat(countOf("meetings")).isZero();
    }

    // ── clientRequestId 멱등·충돌 ─────────────────────────────────────────

    @Test
    @DisplayName("같은 card/pet/payload/clientRequestId 재요청은 멱등이다")
    void sameClientRequestIdWithSameCardPetAndPayloadIsIdempotent() {
        MeetingVerificationSubmitCommand command = new MeetingVerificationSubmitCommand(
                UUID.randomUUID(), 37.5665, 126.978, 24.5,
                Instant.now().minus(10, ChronoUnit.SECONDS));

        MeetingVerificationResult first =
                meetingVerificationService.submit(USER_1, cardId, command);
        MeetingVerificationResult second =
                meetingVerificationService.submit(USER_1, cardId, command);

        assertThat(second.cardId()).isEqualTo(first.cardId());
        assertThat(second.submittedPetId()).isEqualTo(first.submittedPetId());
        assertThat(countOf("meeting_verifications")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 clientRequestId 를 다른 카드에서 재사용하면 409 다")
    void sameClientRequestIdOnAnotherCardConflicts() {
        UUID clientRequestId = UUID.randomUUID();
        submit(USER_1, clientRequestId);
        long otherCardId = confirmAnotherCard();

        assertThatThrownBy(() -> meetingVerificationService.submit(USER_1, otherCardId,
                new MeetingVerificationSubmitCommand(
                        clientRequestId, 37.5665, 126.978, 24.5,
                        Instant.now().minus(10, ChronoUnit.SECONDS))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        assertThat(countOf("meeting_verifications")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 clientRequestId 를 다른 Pet 이 쓰면 409 다")
    void sameClientRequestIdByAnotherPetConflicts() {
        UUID clientRequestId = UUID.randomUUID();
        submit(USER_1, clientRequestId);

        assertThatThrownBy(() -> submit(USER_2, clientRequestId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
    }

    @Test
    @DisplayName("같은 clientRequestId + 같은 card/pet 이지만 payload 가 다르면 409 다")
    void sameClientRequestIdWithDifferentPayloadConflicts() {
        UUID clientRequestId = UUID.randomUUID();
        submit(USER_1, clientRequestId);

        assertThatThrownBy(() -> meetingVerificationService.submit(USER_1, cardId,
                new MeetingVerificationSubmitCommand(
                        clientRequestId, 99.0, 126.978, 24.5,
                        Instant.now().minus(10, ChronoUnit.SECONDS))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
    }

    @Test
    @DisplayName("같은 Pet 의 새 clientRequestId 재제출은 기존 Verification 행을 최신 값으로 대체한다")
    void newClientRequestIdFromSamePetReplacesSubmission() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);

        UUID replacement = UUID.randomUUID();
        MeetingVerificationResult result = submit(USER_1, replacement, 37.5700, 126.9800, 12.0);

        assertThat(result.confirmed()).isFalse();
        assertThat(countOf("meeting_verifications")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications", Double.class))
                .isEqualTo(37.5700);
        assertThat(countOf("meetings")).isZero();
    }

    // ── 권한·상태 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("카드 참여자가 아니면 403 이고 아무것도 저장되지 않는다")
    void nonParticipantIsRejected() {
        insertUser(3L);
        insertPet(33L, 3L);
        setActivePet(3L, 33L);

        assertThatThrownBy(() -> submit(3L, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_NOT_PARTICIPANT);

        assertThat(countOf("meetings")).isZero();
        assertThat(countOf("meeting_verifications")).isZero();
    }

    @Test
    @DisplayName("취소된 카드에는 제출할 수 없고 409 다")
    void canceledCardIsRejected() {
        meetingCardService.cancel(USER_1, cardId);

        assertThatThrownBy(() -> submit(USER_1, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_OPEN);

        assertThat(countOf("meetings")).isZero();
        assertThat(countOf("meeting_verifications")).isZero();
    }

    @Test
    @DisplayName("Active Pet 이 없으면 제출 전에 막힌다")
    void submitRequiresActivePet() {
        jdbcTemplate.update("update users set active_pet_id = null where id = ?", USER_1);

        assertThatThrownBy(() -> submit(USER_1, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);

        assertThat(countOf("meetings")).isZero();
    }

    // ── 동시성 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("양쪽 Pet 이 동시에 GPS 제출해도 Meeting 은 정확히 1건·Verification 은 2행이다")
    void concurrentGpsSubmissionsCreateSingleMeeting() throws Exception {
        List<Object> outcomes = runConcurrently(i -> {
            long actor = (i % 2 == 0) ? USER_1 : USER_2;
            return submit(actor, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        });

        assertThat(outcomes).hasSize(WORKERS);
        assertThat(outcomes).allMatch(o -> o instanceof MeetingVerificationResult);
        assertThat(countOf("meetings")).isEqualTo(1);
        assertThat(countOf("meeting_verifications")).isEqualTo(2);
    }

    @Test
    @DisplayName("서로 다른 카드·다른 Pet 조합이 같은 clientRequestId 를 동시에 제출하면 하나만 성공하고 나머지는 409 다")
    void concurrentSameClientRequestIdAcrossDifferentPairsYieldsOneConflict() throws Exception {
        insertUser(3L);
        insertUser(4L);
        insertPet(33L, 3L);
        insertPet(44L, 4L);
        setActivePet(3L, 33L);
        setActivePet(4L, 44L);
        long otherRoomId = chatRoomService.ensureDirectRoom(33L, 44L, RoomOrigin.GREETING).roomId();
        long otherCardId = meetingCardService.confirm(3L,
                new MeetingCardCreateRequest(otherRoomId, null, MeetingCardType.WALK,
                        "다른공원", Instant.now().plus(2, ChronoUnit.DAYS))).cardId();

        UUID sharedRequestId = UUID.randomUUID();
        List<Object> outcomes = runTwoConcurrently(
                () -> captureSubmit(USER_1, cardId, sharedRequestId),
                () -> captureSubmit(3L, otherCardId, sharedRequestId));

        assertThat(outcomes).hasSize(2);
        long successes = outcomes.stream()
                .filter(o -> o instanceof MeetingVerificationResult)
                .count();
        List<BusinessException> conflicts = outcomes.stream()
                .filter(o -> o instanceof BusinessException)
                .map(o -> (BusinessException) o)
                .toList();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
        assertThat(outcomes).noneMatch(o -> o instanceof DataIntegrityViolationException);

        assertThat(countOf("meeting_verifications")).isEqualTo(1);
        assertThat(countOf("meetings")).isZero();
    }

    @Test
    @DisplayName("카드 취소와 GPS 제출이 경합하면 한쪽만 정상 진행된다")
    void cancelRacingGpsSubmissionIsResolved() throws Exception {
        List<Object> outcomes = runTwoConcurrently(
                () -> captureCancel(USER_1),
                () -> captureSubmit(USER_2, cardId, UUID.randomUUID()));

        // 두 결과는 각각 취소 성공 또는 제출 결과/오류다. 어느 쪽이든 meetings 는 0 또는 1이고
        // 카드 상태는 CANCELED 또는 OPEN 으로 정합해야 한다. 경합은 pair lock / 행 잠금으로 직렬화된다.
        assertThat(outcomes).hasSize(2);
        String status = jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?", String.class, cardId);
        assertThat(status).isIn(MeetingCardStatus.OPEN.name(), MeetingCardStatus.CANCELED.name());
        assertThat(countOf("meetings")).isLessThanOrEqualTo(1);
    }

    // ── 확정용 Meeting DB 제약 ─────────────────────────────────────────────

    @Test
    @DisplayName("같은 카드의 meetings 두 번째 행은 uk_meeting_card 로 거부된다")
    void databaseRejectsDuplicateMeetingForCard() {
        long otherCardId = confirmAnotherCard();
        jdbcTemplate.update("""
                insert into meetings (meeting_card_id, verification_method, confirmed_at)
                values (?, 'GPS', now())
                """, otherCardId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meetings (meeting_card_id, verification_method, confirmed_at)
                        values (?, 'CODE', now())
                        """, otherCardId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_meeting_card");
    }

    @Test
    @DisplayName("meetings 의 verification_method 는 GPS/CODE 만 허용된다")
    void databaseRejectsUnknownVerificationMethod() {
        long otherCardId = confirmAnotherCard();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meetings (meeting_card_id, verification_method, confirmed_at)
                        values (?, 'WIFI', now())
                        """, otherCardId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_meeting_verification_method");
    }

    @Test
    @DisplayName("같은 (카드, Pet) 의 제출 두 번째 행은 uk_meeting_verification_pet 로 거부된다")
    void databaseRejectsDuplicateVerificationForPet() {
        submit(USER_1, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_verifications
                            (meeting_card_id, participant_pet_id, latitude, longitude,
                             accuracy_meters, captured_at, client_request_id)
                        values (?, ?, 37.5, 126.9, 10.0, now(), ?)
                        """, cardId, PET_1, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_meeting_verification_pet");
    }

    @Test
    @DisplayName("같은 clientRequestId 두 번째 행은 uk_meeting_verification_client_request 로 거부된다")
    void databaseRejectsDuplicateClientRequestId() {
        submit(USER_1, UUID.randomUUID());

        UUID usedRequestId = UUID.randomUUID();
        long otherCardId = confirmAnotherCard();
        jdbcTemplate.update("""
                insert into meeting_verifications
                    (meeting_card_id, participant_pet_id, latitude, longitude,
                     accuracy_meters, captured_at, client_request_id)
                values (?, ?, 37.5, 126.9, 10.0, now(), ?)
                """, otherCardId, PET_1, usedRequestId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_verifications
                            (meeting_card_id, participant_pet_id, latitude, longitude,
                             accuracy_meters, captured_at, client_request_id)
                        values (?, ?, 37.6, 127.0, 10.0, now(), ?)
                        """, otherCardId, PET_2, usedRequestId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_meeting_verification_client_request");
    }

    @Test
    @DisplayName("계약 밖 verification status 는 ck_meeting_verification_status 로 거부된다")
    void databaseRejectsUnknownVerificationStatus() {
        long otherCardId = confirmAnotherCard();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_verifications
                            (meeting_card_id, participant_pet_id, status, latitude, longitude,
                             accuracy_meters, captured_at, client_request_id)
                        values (?, ?, 'BOGUS', 37.5, 126.9, 10.0, now(), ?)
                        """, otherCardId, PET_1, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_meeting_verification_status");
    }

    // ── 기존 MeetingCard lifecycle 회귀 ────────────────────────────────────

    @Test
    @DisplayName("위치 제출은 카드 상태를 바꾸지 않고, 카드 확정·취소도 그대로 동작한다")
    void cardLifecycleIsUnaffected() {
        submit(USER_1, UUID.randomUUID());

        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?",
                String.class, cardId)).isEqualTo(MeetingCardStatus.OPEN.name());

        meetingCardService.cancel(USER_2, cardId);

        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?",
                String.class, cardId)).isEqualTo(MeetingCardStatus.CANCELED.name());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private MeetingVerificationResult submit(long userId, UUID clientRequestId) {
        return submit(userId, clientRequestId, 37.5665, 126.978, 24.5);
    }

    private MeetingVerificationResult submit(long userId, UUID clientRequestId,
                                             double latitude, double longitude,
                                             double accuracyMeters) {
        return submit(userId, clientRequestId, latitude, longitude, accuracyMeters,
                Instant.now().minus(10, ChronoUnit.SECONDS));
    }

    private MeetingVerificationResult submit(long userId, UUID clientRequestId,
                                             double latitude, double longitude,
                                             double accuracyMeters, Instant capturedAt) {
        return meetingVerificationService.submit(userId, cardId,
                new MeetingVerificationSubmitCommand(
                        clientRequestId, latitude, longitude, accuracyMeters, capturedAt));
    }

    private Object captureSubmit(long userId, long targetCardId, UUID clientRequestId) {
        try {
            return meetingVerificationService.submit(userId, targetCardId,
                    new MeetingVerificationSubmitCommand(
                            clientRequestId, 37.5665, 126.978, 24.5,
                            Instant.now().minus(10, ChronoUnit.SECONDS)));
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private Object captureCancel(long userId) {
        try {
            return meetingCardService.cancel(userId, cardId);
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private long confirmAnotherCard() {
        return meetingCardService.confirm(USER_1,
                new MeetingCardCreateRequest(roomId, null, MeetingCardType.WALK,
                        "다른공원", Instant.now().plus(2, ChronoUnit.DAYS))).cardId();
    }

    private MeetingCardCreateRequest request(long roomId) {
        return new MeetingCardCreateRequest(roomId, null, MeetingCardType.WALK,
                "중앙공원", Instant.now().plus(1, ChronoUnit.DAYS));
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

    private List<Object> runConcurrently(java.util.function.IntFunction<Object> action)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < WORKERS; i++) {
                final int index = i;
                Callable<Object> task = () -> {
                    start.await();
                    return action.apply(index);
                };
                futures.add(executor.submit(task));
            }
            start.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<Object> runTwoConcurrently(Callable<Object> first, Callable<Object> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> {
                start.await();
                return first.call();
            }));
            futures.add(executor.submit(() -> {
                start.await();
                return second.call();
            }));
            start.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
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
