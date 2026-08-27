package itda.meetingverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.chat.domain.RoomOrigin;
import itda.chat.service.ChatRoomService;
import itda.block.dto.BlockCreateRequest;
import itda.block.service.BlockService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.service.MeetingCardBlockCleanupService;
import itda.meetingcard.service.MeetingCardService;
import itda.meetingverification.domain.MeetingVerificationApiStatus;
import itda.meetingverification.domain.MeetingVerificationMethod;
import itda.meetingverification.domain.MeetingVerificationStatus;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.dto.MeetingVerificationStatusResponse;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import itda.meetingverification.repository.MeetingRepository;
import itda.meetingverification.repository.MeetingVerificationRepository;
import itda.meetingverification.service.MeetingVerificationExpiryService;
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
import org.testcontainers.utility.DockerImageName;

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
            new PostgreSQLContainer(DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private MeetingVerificationService meetingVerificationService;
    @Autowired
    private MeetingVerificationExpiryService meetingVerificationExpiryService;
    @Autowired
    private MeetingCardService meetingCardService;
    @Autowired
    private MeetingCardBlockCleanupService meetingCardBlockCleanupService;
    @Autowired
    private BlockService blockService;
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
                truncate meeting_verification_requests, meeting_verifications, meetings,
                         meeting_participants, meeting_cards, card_drafts, chat_messages,
                         chat_room_participants, chat_rooms, pets, users, neighborhoods
                restart identity cascade
                """);
        insertNeighborhood();
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
        assertThat(result.codeRequired()).isFalse();
        assertThat(result.distanceMeters()).isNull();
        assertThat(result.meetingId()).isNull();

        assertThat(countOf("meetings")).isZero();
        assertThat(countOf("meeting_verifications")).isEqualTo(1);
        assertThat(countOf("meeting_verification_requests")).isEqualTo(1);
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
        assertThat(second.codeRequired()).isFalse();
        assertThat(second.distanceMeters()).isNotNull();
        assertThat(second.meetingId()).isNotNull();
        assertThat(second.verificationMethod()).isEqualTo(MeetingVerificationMethod.GPS);
        assertThat(second.confirmedAt()).isNotNull();

        assertThat(countOf("meetings")).isEqualTo(1);
        assertThat(countOf("meeting_verifications")).isEqualTo(2);
        assertThat(countOf("meeting_verification_requests")).isEqualTo(2);
    }

    @Test
    @DisplayName("LOW_ACCURACY 제출은 CODE_REQUIRED 로 저장되고 Meeting 은 0건이다")
    void lowAccuracyDoesNotCreateMeeting() {
        MeetingVerificationResult first = submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 60.0);

        assertThat(first.confirmed()).isFalse();
        assertThat(first.codeRequired()).isTrue();
        assertThat(first.distanceMeters()).isNull();
        assertThat(countOf("meetings")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_verifications where participant_pet_id = ?",
                String.class, PET_1)).isEqualTo(MeetingVerificationStatus.CODE_REQUIRED.name());

        // 상대가 ACCEPTABLE 이어도 한쪽 CODE_REQUIRED 면 GPS Meeting 을 만들지 않는다.
        MeetingVerificationResult second = submit(USER_2, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        assertThat(second.confirmed()).isFalse();
        assertThat(second.codeRequired()).isFalse();
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
    @DisplayName("양쪽 submitted_at 간격 초과는 MEETING_TIME_WINDOW_EXCEEDED 이고 Meeting 은 0건이다")
    void intervalExceededDoesNotCreateMeeting() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        // 상대 제출의 서버 수신시각(submitted_at)을 과거로 조작한다. captured_at 은 그대로 둔다.
        jdbcTemplate.update("""
                update meeting_verifications
                   set submitted_at = now() - interval '2 minutes'
                 where participant_pet_id = ?
                """, PET_1);

        assertThatThrownBy(() -> submit(USER_2, UUID.randomUUID(), 37.5665, 126.978, 24.5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        assertThat(countOf("meetings")).isZero();
    }

    @Test
    @DisplayName("내일 meetAt + 오늘 GPS 제출은 MEETING_TIME_WINDOW_EXCEEDED 이고 아무것도 저장하지 않는다")
    void meetAtTomorrowRejectsTodaySubmission() {
        long tomorrowCardId = meetingCardService.confirm(USER_1,
                new MeetingCardCreateRequest(roomId, null, MeetingCardType.WALK,
                        "내일공원", Instant.now().plus(1, ChronoUnit.DAYS))).cardId();

        assertThatThrownBy(() -> meetingVerificationService.submit(USER_1, tomorrowCardId,
                new MeetingVerificationSubmitCommand(
                        UUID.randomUUID(), 37.5665, 126.978, 24.5,
                        Instant.now().minus(10, ChronoUnit.SECONDS))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        assertThat(countOf("meetings")).isZero();
        assertThat(countOf("meeting_verifications")).isZero();
        assertThat(countOf("meeting_verification_requests")).isZero();
    }

    @Test
    @DisplayName("GPS 확정은 실제 계산 거리를 meetings.distance_meters 에 저장한다")
    void gpsMeetingStoresActualDistanceMeters() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        MeetingVerificationResult second = submit(USER_2, UUID.randomUUID(), 37.5666, 126.9781, 24.5);

        assertThat(second.confirmed()).isTrue();
        assertThat(second.distanceMeters()).isNotNull().isPositive();

        Double stored = jdbcTemplate.queryForObject(
                "select distance_meters from meetings where meeting_card_id = ?",
                Double.class, cardId);
        assertThat(stored).isEqualTo(second.distanceMeters());
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
        assertThat(countOf("meeting_verification_requests")).isEqualTo(1);
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
        assertThat(countOf("meeting_verification_requests")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications", Double.class))
                .isEqualTo(37.5700);
        assertThat(countOf("meetings")).isZero();
    }

    @Test
    @DisplayName("AAA → BBB 새 제출 → 늦은 AAA 재시도는 최신 BBB 를 유지하고 replay 로 수렴한다")
    void replacedRequestLateRetryReplaysPreservingLatestSubmission() {
        MeetingVerificationSubmitCommand aaa = new MeetingVerificationSubmitCommand(
                UUID.randomUUID(), 37.5665, 126.978, 24.5,
                Instant.now().minus(10, ChronoUnit.SECONDS));
        meetingVerificationService.submit(USER_1, cardId, aaa);

        submit(USER_1, UUID.randomUUID(), 37.5700, 126.9800, 12.0); // BBB

        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications where participant_pet_id = ?",
                Double.class, PET_1)).isEqualTo(37.5700);

        // 늦은 AAA 재시도는 새 Location 평가·replace·ledger 재기록 없이 replay 다.
        MeetingVerificationResult replay = meetingVerificationService.submit(USER_1, cardId, aaa);

        assertThat(replay.confirmed()).isFalse();
        assertThat(replay.codeRequired()).isFalse();
        // 최신 verification 은 BBB 위치를 유지한다.
        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications where participant_pet_id = ?",
                Double.class, PET_1)).isEqualTo(37.5700);
        assertThat(countOf("meeting_verifications")).isEqualTo(1);
        assertThat(countOf("meeting_verification_requests")).isEqualTo(2);
        assertThat(countOf("meetings")).isZero();
    }

    @Test
    @DisplayName("A SUBMITTED → B CODE_REQUIRED → 늦은 A 재시도는 CODE_REQUIRED 로 수렴한다")
    void submittedThenCodeRequiredLateSubmittedReplayConvergesToCodeRequired() {
        // A: ACCEPTABLE → SUBMITTED
        MeetingVerificationSubmitCommand aaa = new MeetingVerificationSubmitCommand(
                UUID.randomUUID(), 37.5665, 126.978, 24.5,
                Instant.now().minus(10, ChronoUnit.SECONDS));
        meetingVerificationService.submit(USER_1, cardId, aaa);

        // B: LOW_ACCURACY → CODE_REQUIRED
        MeetingVerificationResult lowAccuracy =
                submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 60.0);
        assertThat(lowAccuracy.codeRequired()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_verifications where participant_pet_id = ?",
                String.class, PET_1)).isEqualTo(MeetingVerificationStatus.CODE_REQUIRED.name());

        // 늦은 A 재시도는 과거 SUBMITTED ledger 를 보고 WAITING_COUNTERPART 로 되돌아가지 않는다.
        MeetingVerificationResult replay = meetingVerificationService.submit(USER_1, cardId, aaa);

        assertThat(replay.status()).isEqualTo(MeetingVerificationApiStatus.CODE_REQUIRED);
        assertThat(replay.codeRequired()).isTrue();
        assertThat(replay.confirmed()).isFalse();
        // 최신 verification 은 CODE_REQUIRED + raw null 유지 (SUBMITTED 부활 없음)
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_verifications where participant_pet_id = ?",
                String.class, PET_1)).isEqualTo(MeetingVerificationStatus.CODE_REQUIRED.name());
        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications where participant_pet_id = ?",
                Double.class, PET_1)).isNull();
        assertThat(countOf("meeting_verifications")).isEqualTo(1);
        assertThat(countOf("meeting_verification_requests")).isEqualTo(2);
        assertThat(countOf("meetings")).isZero();
    }

    // ── 권한·상태 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("카드 참여자가 아니면 404(existence hiding)이고 아무것도 저장되지 않는다")
    void nonParticipantIsRejected() {
        insertUser(3L);
        insertPet(33L, 3L);
        setActivePet(3L, 33L);

        assertThatThrownBy(() -> submit(3L, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

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
                        "다른공원", Instant.now())).cardId();

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
        assertThat(countOf("meeting_verification_requests")).isEqualTo(1);
        assertThat(countOf("meetings")).isZero();
    }

    @Test
    @DisplayName("카드 취소와 최종 GPS 제출이 Pair→Card 순서로 교착 없이 수렴한다")
    void cancelRacingGpsSubmissionDoesNotDeadlock() throws Exception {
        // 상대 SUBMITTED fixture 를 먼저 만든다.
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);

        List<Object> outcomes = runTwoConcurrently(
                () -> captureCancel(USER_1),
                () -> captureSubmit(USER_2, cardId, UUID.randomUUID()));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).noneMatch(this::isDeadlockFailure);
        String status = jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?", String.class, cardId);
        int meetingCount = countOf("meetings");
        // (GPS Meeting 1 + OPEN) 또는 (Meeting 0 + CANCELED) 만 허용. CONFIRMED+CANCELED 는 금지.
        if ("CANCELED".equals(status)) {
            assertThat(meetingCount).isZero();
        } else {
            assertThat(status).isEqualTo(MeetingCardStatus.OPEN.name());
            assertThat(meetingCount).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Block 과 GPS 제출이 경합해도 Pair→Card 순서로 교착 없이 수렴한다")
    void blockRacingGpsSubmissionDoesNotDeadlockAndHidesWhenBlockWins() throws Exception {
        List<Object> outcomes = runTwoConcurrently(
                () -> captureBlock(USER_1, PET_2),
                () -> captureSubmit(USER_2, cardId, UUID.randomUUID()));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).noneMatch(this::isDeadlockFailure);
        String cardStatus = jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?", String.class, cardId);
        if ("CANCELED".equals(cardStatus)) {
            assertThat(outcomes).anyMatch(outcome -> outcome instanceof MeetingVerificationResult
                    || (outcome instanceof BusinessException exception
                    && exception.getErrorCode() == ErrorCode.CHAT_ROOM_NOT_FOUND));
        }
    }

    // ── 확정용 Meeting DB 제약 ─────────────────────────────────────────────

    @Test
    @DisplayName("같은 카드의 meetings 두 번째 행은 uk_meeting_card 로 거부된다")
    void databaseRejectsDuplicateMeetingForCard() {
        long otherCardId = confirmAnotherCard();
        jdbcTemplate.update("""
                insert into meetings (meeting_card_id, verification_method, confirmed_at,
                                      distance_meters)
                values (?, 'GPS', now(), 42.7)
                """, otherCardId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meetings (meeting_card_id, verification_method, confirmed_at,
                                              distance_meters)
                        values (?, 'CODE', now(), null)
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
                // WIFI 는 허용 방식이 아니므로 방식 제약과 GPS/CODE 거리 제약을 함께
                // 위반한다. PostgreSQL 의 CHECK 평가 순서는 계약이 아니다.
                .satisfies(exception -> assertThat(exception.getMessage())
                        .containsAnyOf("ck_meeting_verification_method",
                                "ck_meeting_distance_by_method"));
    }

    @Test
    @DisplayName("GPS Meeting 은 distance_meters 가 없으면 ck_meeting_distance_by_method 로 거부된다")
    void databaseRejectsGpsMeetingWithoutDistance() {
        long otherCardId = confirmAnotherCard();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meetings (meeting_card_id, verification_method, confirmed_at,
                                              distance_meters)
                        values (?, 'GPS', now(), null)
                        """, otherCardId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_meeting_distance_by_method");
    }

    @Test
    @DisplayName("CODE Meeting 은 distance_meters 가 있으면 ck_meeting_distance_by_method 로 거부된다")
    void databaseRejectsCodeMeetingWithDistance() {
        long otherCardId = confirmAnotherCard();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meetings (meeting_card_id, verification_method, confirmed_at,
                                              distance_meters)
                        values (?, 'CODE', now(), 42.7)
                        """, otherCardId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_meeting_distance_by_method");
    }

    @Test
    @DisplayName("음수 distance_meters 는 ck_meeting_distance_non_negative 로 거부된다")
    void databaseRejectsNegativeDistanceMeters() {
        long otherCardId = confirmAnotherCard();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meetings (meeting_card_id, verification_method, confirmed_at,
                                              distance_meters)
                        values (?, 'GPS', now(), -1.0)
                        """, otherCardId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_meeting_distance_non_negative");
    }

    @Test
    @DisplayName("같은 (카드, Pet) 의 제출 두 번째 행은 uk_meeting_verification_pet 로 거부된다")
    void databaseRejectsDuplicateVerificationForPet() {
        submit(USER_1, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_verifications
                            (meeting_card_id, participant_pet_id, latitude, longitude,
                             accuracy_meters, captured_at, submitted_at, client_request_id)
                        values (?, ?, 37.5, 126.9, 10.0, now(), now(), ?)
                        """, cardId, PET_1, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_meeting_verification_pet");
    }

    @Test
    @DisplayName("같은 clientRequestId 두 번째 행은 request ledger 의 PK 로 거부된다")
    void databaseRejectsDuplicateClientRequestId() {
        UUID usedRequestId = UUID.randomUUID();
        long otherCardId = confirmAnotherCard();
        jdbcTemplate.update("""
                insert into meeting_verification_requests
                    (client_request_id, meeting_card_id, participant_pet_id, fingerprint, status)
                values (?, ?, ?, 'fp-a', 'SUBMITTED')
                """, usedRequestId, otherCardId, PET_1);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_verification_requests
                            (client_request_id, meeting_card_id, participant_pet_id, fingerprint, status)
                        values (?, ?, ?, 'fp-b', 'SUBMITTED')
                        """, usedRequestId, otherCardId, PET_2))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_meeting_verification_requests");
    }

    @Test
    @DisplayName("계약 밖 verification status 는 ck_meeting_verification_status 로 거부된다")
    void databaseRejectsUnknownVerificationStatus() {
        long otherCardId = confirmAnotherCard();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_verifications
                            (meeting_card_id, participant_pet_id, status, latitude, longitude,
                             accuracy_meters, captured_at, submitted_at, client_request_id)
                        values (?, ?, 'BOGUS', 37.5, 126.9, 10.0, now(), now(), ?)
                        """, otherCardId, PET_1, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                // BOGUS 는 허용 상태가 아니므로 상태 제약과 raw-location 상태 제약을
                // 함께 위반한다. 어느 CHECK 가 먼저 보고되는지에는 의존하지 않는다.
                .satisfies(exception -> assertThat(exception.getMessage())
                        .containsAnyOf("ck_meeting_verification_status",
                                "ck_meeting_verification_raw"));
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

    // ── raw scrub·만료·취소 정합성 ─────────────────────────────────────────

    @Test
    @DisplayName("GPS 확정은 양쪽 verification 을 ACCEPTED 로 전이하고 raw 좌표를 scrub 한다")
    void gpsConfirmationScrubsRawAndAcceptsBothSides() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        submit(USER_2, UUID.randomUUID(), 37.5666, 126.9781, 24.5);

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select status, latitude, longitude, accuracy_meters, captured_at
                  from meeting_verifications
                 order by participant_pet_id
                """);
        assertThat(rows).hasSize(2);
        rows.forEach(row -> {
            assertThat(row.get("status")).isEqualTo(MeetingVerificationStatus.ACCEPTED.name());
            assertThat(row.get("latitude")).isNull();
            assertThat(row.get("longitude")).isNull();
            assertThat(row.get("accuracy_meters")).isNull();
            assertThat(row.get("captured_at")).isNull();
        });
    }

    @Test
    @DisplayName("만료 worker 는 시간창 경과 SUBMITTED 를 EXPIRED 로 전이하고 raw 좌표를 scrub 한다")
    void expiryWorkerScrubsRaw() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        // meetAt 을 시간창보다 과거로 옮겨 만료 대상으로 만든다.
        jdbcTemplate.update("""
                update meeting_cards set meet_at = now() - interval '2 hours' where id = ?
                """, cardId);

        MeetingVerificationExpiryService.ExpiryResult result =
                meetingVerificationExpiryService.runOnce();

        assertThat(result.expired()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_verifications where participant_pet_id = ?",
                String.class, PET_1)).isEqualTo(MeetingVerificationStatus.EXPIRED.name());
        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications where participant_pet_id = ?",
                Double.class, PET_1)).isNull();
    }

    @Test
    @DisplayName("만료된 verification 은 새 requestId 로 부활하지 않고 deadline 으로 거절된다")
    void expiredVerificationCannotBeRevivedByNewSubmission() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        jdbcTemplate.update("""
                update meeting_cards set meet_at = now() - interval '2 hours' where id = ?
                """, cardId);
        meetingVerificationExpiryService.runOnce();

        // meetAt 이 deadline 밖이므로 서버 수신 deadline 검사가 409 로 거절한다.
        assertThatThrownBy(() -> submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_verifications where participant_pet_id = ?",
                String.class, PET_1)).isEqualTo(MeetingVerificationStatus.EXPIRED.name());
        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications where participant_pet_id = ?",
                Double.class, PET_1)).isNull();
        assertThat(countOf("meetings")).isZero();
    }

    @Test
    @DisplayName("만료 후 동일 clientRequestId·동일 payload 재시도는 EXPIRED 로 수렴하고 아무것도 쓰지 않는다")
    void expiredReplayConvergesToExpiredWithoutWrites() {
        MeetingVerificationSubmitCommand command = new MeetingVerificationSubmitCommand(
                UUID.randomUUID(), 37.5665, 126.978, 24.5,
                Instant.now().minus(10, ChronoUnit.SECONDS));
        meetingVerificationService.submit(USER_1, cardId, command);
        jdbcTemplate.update("""
                update meeting_cards set meet_at = now() - interval '2 hours' where id = ?
                """, cardId);
        meetingVerificationExpiryService.runOnce();

        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_verifications where participant_pet_id = ?",
                String.class, PET_1)).isEqualTo(MeetingVerificationStatus.EXPIRED.name());
        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications where participant_pet_id = ?",
                Double.class, PET_1)).isNull();

        long requestsBefore = countOf("meeting_verification_requests");
        long verificationsBefore = countOf("meeting_verifications");
        long meetingsBefore = countOf("meetings");

        MeetingVerificationResult replay =
                meetingVerificationService.submit(USER_1, cardId, command);

        assertThat(replay.status()).isEqualTo(MeetingVerificationApiStatus.EXPIRED);
        assertThat(replay.confirmed()).isFalse();
        assertThat(replay.codeRequired()).isFalse();
        assertThat(countOf("meeting_verification_requests")).isEqualTo(requestsBefore);
        assertThat(countOf("meeting_verifications")).isEqualTo(verificationsBefore);
        assertThat(countOf("meetings")).isEqualTo(meetingsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications where participant_pet_id = ?",
                Double.class, PET_1)).isNull();
    }

    @Test
    @DisplayName("만료 후 동일 clientRequestId + 다른 payload 는 409 REQUEST_CONFLICT 다")
    void expiredReplayWithDifferentPayloadConflicts() {
        UUID clientRequestId = UUID.randomUUID();
        submit(USER_1, clientRequestId, 37.5665, 126.978, 24.5);
        jdbcTemplate.update("""
                update meeting_cards set meet_at = now() - interval '2 hours' where id = ?
                """, cardId);
        meetingVerificationExpiryService.runOnce();

        assertThatThrownBy(() -> submit(USER_1, clientRequestId, 99.0, 126.978, 24.5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
    }

    @Test
    @DisplayName("GPS 확정 뒤 만료 worker 는 ACCEPTED raw 를 건드리지 않는다")
    void expiryDoesNotTouchAcceptedRawAfterGpsConfirm() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        submit(USER_2, UUID.randomUUID(), 37.5666, 126.9781, 24.5);
        jdbcTemplate.update("""
                update meeting_cards set meet_at = now() - interval '2 hours' where id = ?
                """, cardId);

        MeetingVerificationExpiryService.ExpiryResult result =
                meetingVerificationExpiryService.runOnce();

        assertThat(result.expired()).isZero();
        assertThat(jdbcTemplate.queryForList(
                "select status from meeting_verifications order by participant_pet_id", String.class))
                .allMatch(MeetingVerificationStatus.ACCEPTED.name()::equals);
        assertThat(countOf("meetings")).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 worker 와 최종 GPS submit 이 경합해도 deadlock 없이 일관되게 수렴한다")
    void expiryRacingFinalGpsSubmitConvergesWithoutDeadlock() throws Exception {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        jdbcTemplate.update("""
                update meeting_cards set meet_at = now() - interval '2 hours' where id = ?
                """, cardId);

        List<Object> outcomes = runTwoConcurrently(
                () -> captureExpiry(),
                () -> captureSubmit(USER_2, cardId, UUID.randomUUID()));

        assertThat(outcomes).hasSize(2);
        // meetAt 이 deadline 밖이므로 GPS 는 거절되고, 만료 worker 는 SUBMITTED 를 EXPIRED 로 만든다.
        // 어느 쪽이 먼저여도 결과는 동일하며 deadlock 이 없다.
        assertThat(countOf("meetings")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_verifications where participant_pet_id = ?",
                String.class, PET_1)).isEqualTo(MeetingVerificationStatus.EXPIRED.name());
        assertThat(jdbcTemplate.queryForObject(
                "select latitude from meeting_verifications where participant_pet_id = ?",
                Double.class, PET_1)).isNull();
    }

    @Test
    @DisplayName("확정 Meeting 이 있는 카드의 수동 취소는 409 MEETING_ALREADY_CONFIRMED 다")
    void confirmedMeetingBlocksManualCancel() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        submit(USER_2, UUID.randomUUID(), 37.5666, 126.9781, 24.5);

        assertThatThrownBy(() -> meetingCardService.cancel(USER_1, cardId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_ALREADY_CONFIRMED);

        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?", String.class, cardId))
                .isEqualTo(MeetingCardStatus.OPEN.name());
    }

    @Test
    @DisplayName("Block 자동 정리는 확정 Meeting 이 있는 카드를 취소하지 않는다")
    void blockCleanupSkipsConfirmedCard() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        submit(USER_2, UUID.randomUUID(), 37.5666, 126.9781, 24.5);

        int canceled = meetingCardBlockCleanupService.cancelOpenCardsBetweenUsers(
                USER_1, USER_2, PET_1);

        assertThat(canceled).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?", String.class, cardId))
                .isEqualTo(MeetingCardStatus.OPEN.name());
    }

    @Test
    @DisplayName("비참여 Pet 의 verification insert 는 composite FK 로 거부된다")
    void databaseRejectsNonParticipantVerification() {
        insertUser(3L);
        insertPet(33L, 3L);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_verifications
                            (meeting_card_id, participant_pet_id, latitude, longitude,
                             accuracy_meters, captured_at, submitted_at, client_request_id)
                        values (?, ?, 37.5, 126.9, 10.0, now(), now(), ?)
                        """, cardId, 33L, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_meeting_verification_participant");
    }

    @Test
    @DisplayName("비참여 Pet 의 request ledger insert 는 composite FK 로 거부된다")
    void databaseRejectsNonParticipantRequestLedger() {
        insertUser(3L);
        insertPet(33L, 3L);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_verification_requests
                            (client_request_id, meeting_card_id, participant_pet_id, fingerprint, status)
                        values (?, ?, ?, 'fp-a', 'SUBMITTED')
                        """, UUID.randomUUID(), cardId, 33L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_meeting_verification_request_participant");
    }

    @Test
    @DisplayName("GET status 는 단일 projection 으로 Meeting 존재 시 양쪽 제출 확정 불변식을 유지한다")
    void getStatusUsesSingleProjectionAndMaintainsInvariants() {
        submit(USER_1, UUID.randomUUID(), 37.5665, 126.978, 24.5);
        submit(USER_2, UUID.randomUUID(), 37.5666, 126.9781, 24.5);

        MeetingVerificationStatusResponse status =
                meetingVerificationService.getStatus(USER_1, cardId);

        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.GPS_CONFIRMED);
        assertThat(status.confirmed()).isTrue();
        assertThat(status.mySubmitted()).isTrue();
        assertThat(status.counterpartSubmitted()).isTrue();
        assertThat(status.distanceMeters()).isNotNull();
    }

    @Test
    @DisplayName("GET status projection 은 제출 전 NOT_SUBMITTED 를 반환한다")
    void getStatusNotSubmittedBeforeAnySubmission() {
        MeetingVerificationStatusResponse status =
                meetingVerificationService.getStatus(USER_1, cardId);

        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.NOT_SUBMITTED);
        assertThat(status.confirmed()).isFalse();
        assertThat(status.mySubmitted()).isFalse();
        assertThat(status.counterpartSubmitted()).isFalse();
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
        } catch (RuntimeException exception) {
            // deadlock/lock-timeout은 BusinessException이 아니라 Spring DataAccessException으로
            // 나오므로 outcomes에 담아 noneMatch(isDeadlockFailure)가 명시적으로 거부하게 한다.
            // 그 외 예상 밖 RuntimeException은 그대로 던져 테스트가 실패하도록 유지한다.
            if (isDeadlockFailure(exception)) {
                return exception;
            }
            throw exception;
        }
    }

    private Object captureCancel(long userId) {
        try {
            return meetingCardService.cancel(userId, cardId);
        } catch (BusinessException exception) {
            return exception;
        } catch (RuntimeException exception) {
            if (isDeadlockFailure(exception)) {
                return exception;
            }
            throw exception;
        }
    }

    private Object captureExpiry() {
        try {
            return meetingVerificationExpiryService.runOnce();
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private Object captureBlock(long userId, long targetPetId) {
        try {
            return blockService.block(userId, new BlockCreateRequest(targetPetId));
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private boolean isDeadlockFailure(Object outcome) {
        if (!(outcome instanceof Throwable throwable)) {
            return false;
        }
        String message = throwable.toString().toLowerCase(java.util.Locale.ROOT);
        return message.contains("deadlock") || message.contains("lock timeout");
    }

    private long confirmAnotherCard() {
        return meetingCardService.confirm(USER_1,
                new MeetingCardCreateRequest(roomId, null, MeetingCardType.WALK,
                        "다른공원", Instant.now().plus(2, ChronoUnit.DAYS))).cardId();
    }

    private MeetingCardCreateRequest request(long roomId) {
        // meetAt 을 현재 시각 근처로 두어 submit 헬퍼의 capturedAt(now-10s)이
        // 약속 시간창(±1h) 안에 들어가도록 한다.
        return new MeetingCardCreateRequest(roomId, null, MeetingCardType.WALK,
                "중앙공원", Instant.now());
    }

    private void insertNeighborhood() {
        jdbcTemplate.update("""
                insert into neighborhoods (code, sido_name, sigungu_name, eupmyeondong_name)
                values (?, '경기도', '성남시', '수내동')
                """, NEIGHBORHOOD);
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
