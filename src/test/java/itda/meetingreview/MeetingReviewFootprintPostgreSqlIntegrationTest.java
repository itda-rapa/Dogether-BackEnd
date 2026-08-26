package itda.meetingreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.chat.domain.RoomOrigin;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.service.MeetingCardService;
import itda.meetingreview.domain.MeetingReview;
import itda.meetingreview.dto.FootprintListResponse;
import itda.meetingreview.dto.MeetingReviewSubmitCommand;
import itda.meetingreview.dto.MeetingReviewSubmitResult;
import itda.meetingreview.repository.FootprintRepository;
import itda.meetingreview.repository.MeetingReviewRepository;
import itda.meetingreview.service.FootprintQueryService;
import itda.meetingreview.service.MeetingReviewService;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 만남 후기·발자국(#150)을 실제 PostgreSQL 에서 검증한다.
 *
 * <p>단위 테스트로 증명할 수 없는 것만 본다: 확정 Meeting 없음/취소 카드/비참여 Pet 거부,
 * GPS·CODE 확정 Meeting 모두 후기 가능, (meeting, pet) 중복 409, 같은 Pet·같은 KST 날짜의
 * 다른 Meeting 후기 두 건 → 후기 2건·발자국 1건, 동시 후기·동시 일일 발자국 경합 수렴,
 * 후기/신규 발자국 저장 실패 시 원자적 롤백, Active Pet 변경·비활성 회귀, DB 제약(Unique/FK),
 * API 계약.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class MeetingReviewFootprintPostgreSqlIntegrationTest {

    private static final int WORKERS = 8;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private MeetingReviewService meetingReviewService;
    @Autowired
    private FootprintQueryService footprintQueryService;
    @Autowired
    private MeetingCardService meetingCardService;
    @Autowired
    private ChatRoomService chatRoomService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private MeetingReviewRepository meetingReviewRepository;
    @MockitoSpyBean
    private FootprintRepository footprintRepository;
    @MockitoSpyBean
    private ActivePetQueryService activePetQueryService;

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final String NEIGHBORHOOD = "4113111500";
    private static final String PLACE_TAG = "공원";

    private long roomId;
    private long cardId;

    @BeforeEach
    void setUp() {
        reset(meetingReviewRepository, footprintRepository, activePetQueryService);
        jdbcTemplate.execute("""
                truncate footprints, meeting_reviews, meetings, meeting_verifications,
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

    // ── 권한·상태 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("확정 Meeting 이 없으면 404 이고 아무것도 저장되지 않는다")
    void missingMeetingIsRejected() {
        assertThatThrownBy(() -> submit(USER_1, 99999L, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_NOT_FOUND);

        assertThat(countOf("meeting_reviews")).isZero();
        assertThat(countOf("footprints")).isZero();
    }

    @Test
    @DisplayName("취소 카드는 확정 Meeting 이 없어 404 다")
    void canceledCardWithoutMeetingIsRejected() {
        meetingCardService.cancel(USER_1, cardId);

        assertThatThrownBy(() -> submit(USER_1, 99999L, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_NOT_FOUND);
    }

    @Test
    @DisplayName("확정 Meeting 이 있는데 카드가 취소되면 409 로 방어한다")
    void canceledCardWithMeetingIsRejected() {
        // meetingCardService.cancel 은 확정 Meeting 이 있는 카드의 수동 취소를 금지하므로
        // (MEETING_ALREADY_CONFIRMED), "확정 Meeting + 취소 카드" 상태를 직접 만들어 방어 경로만 검증한다.
        long canceledCardId = insertCanceledCard();
        long meetingId = confirmMeeting(canceledCardId, "GPS");

        assertThatThrownBy(() -> submit(USER_1, meetingId, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_CARD_NOT_OPEN);

        assertThat(countOf("meeting_reviews")).isZero();
        assertThat(countOf("footprints")).isZero();
    }

    @Test
    @DisplayName("비참여 Pet 은 403 이다")
    void nonParticipantIsRejected() {
        insertUser(3L);
        insertPet(33L, 3L);
        setActivePet(3L, 33L);
        long meetingId = confirmMeeting(cardId, "GPS");

        assertThatThrownBy(() -> submit(3L, meetingId, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_NOT_PARTICIPANT);

        assertThat(countOf("meeting_reviews")).isZero();
        assertThat(countOf("footprints")).isZero();
    }

    // ── GPS·CODE 확정 Meeting 후기 ─────────────────────────────────────────

    @Test
    @DisplayName("GPS 확정 Meeting 은 후기·발자국이 저장된다")
    void gpsConfirmedMeetingGrantsReviewAndFootprint() {
        long meetingId = confirmMeeting(cardId, "GPS");
        UUID clientRequestId = UUID.randomUUID();

        MeetingReviewSubmitResult result = submit(USER_1, meetingId, clientRequestId);

        assertThat(result.meetingId()).isEqualTo(meetingId);
        assertThat(result.placeTag()).isEqualTo(PLACE_TAG);
        assertThat(result.footprint().granted()).isTrue();
        assertThat(result.footprint().duplicateDay()).isFalse();
        assertThat(result.footprint().earnedDate()).isEqualTo(LocalDate.now(SEOUL));

        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select client_request_id::text from meeting_reviews", String.class))
                .isEqualTo(clientRequestId.toString());
        assertThat(jdbcTemplate.queryForObject(
                "select place_tag from meeting_reviews", String.class))
                .isEqualTo(PLACE_TAG);
        assertThat(jdbcTemplate.queryForObject(
                "select content from meeting_reviews", String.class))
                .isEqualTo("즐겁게 산책했어요.");
        assertThat(jdbcTemplate.queryForObject(
                "select earned_date from footprints", java.sql.Date.class).toLocalDate())
                .isEqualTo(LocalDate.now(SEOUL));
        assertThat(jdbcTemplate.queryForObject(
                "select counterpart_pet_id from footprints", Long.class))
                .isEqualTo(PET_2);
    }

    @Test
    @DisplayName("CODE 확정 Meeting 도 후기·발자국이 저장된다(방식 무관)")
    void codeConfirmedMeetingGrantsReviewAndFootprint() {
        long secondCardId = confirmAnotherCard();
        long meetingId = confirmMeeting(secondCardId, "CODE");

        MeetingReviewSubmitResult result = submit(USER_1, meetingId, UUID.randomUUID());

        assertThat(result.footprint().granted()).isTrue();
        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    @Test
    @DisplayName("후기 작성은 카드·검증 상태를 바꾸지 않는다")
    void reviewDoesNotTouchCardOrVerificationState() {
        long meetingId = confirmMeeting(cardId, "GPS");

        submit(USER_1, meetingId, UUID.randomUUID());

        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?", String.class, cardId))
                .isEqualTo(MeetingCardStatus.OPEN.name());
        assertThat(countOf("meeting_verifications")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select verification_method from meetings where id = ?", String.class, meetingId))
                .isEqualTo("GPS");
    }

    // ── 중복·멱등 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 Meeting·Pet 후기 재작성은 409 다")
    void duplicateReviewForSameMeetingAndPetIs409() {
        long meetingId = confirmMeeting(cardId, "GPS");
        submit(USER_1, meetingId, UUID.randomUUID());

        assertThatThrownBy(() -> submit(USER_1, meetingId, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS);

        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 clientRequestId 재요청은 멱등으로 기존 후기를 반환한다")
    void sameClientRequestIdReplayIsIdempotent() {
        long meetingId = confirmMeeting(cardId, "GPS");
        UUID clientRequestId = UUID.randomUUID();

        MeetingReviewSubmitResult first = submit(USER_1, meetingId, clientRequestId);
        MeetingReviewSubmitResult second = submit(USER_1, meetingId, clientRequestId);

        assertThat(first.footprint().granted()).isTrue();
        assertThat(second.footprint().granted()).isFalse();
        assertThat(second.reviewId()).isEqualTo(first.reviewId());
        assertThat(second.meetingId()).isEqualTo(first.meetingId());
        assertThat(second.placeTag()).isEqualTo(first.placeTag());
        assertThat(second.content()).isEqualTo(first.content());
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.footprint().footprintId()).isEqualTo(first.footprint().footprintId());
        assertThat(second.footprint().earnedDate()).isEqualTo(first.footprint().earnedDate());
        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    @Test
    @DisplayName("KST 자정을 넘긴 clientRequestId replay 는 최초 후기 적립일의 일일 발자국을 반환한다")
    void replayAfterKstMidnightReturnsOriginalDailyFootprint() {
        long meetingId = confirmMeeting(cardId, "GPS");
        long secondCardId = confirmAnotherCard();
        long secondMeetingId = confirmMeeting(secondCardId, "CODE");
        UUID clientRequestId = UUID.randomUUID();
        // 현재 시각과 무관하게 항상 과거인 KST 날짜로 "자정 경계를 넘은 재시도"를 재현한다.
        LocalDate originalDate = LocalDate.now(SEOUL).minusDays(2);
        Instant originalCreatedAt = originalDate.atStartOfDay(SEOUL).plusHours(9).toInstant();

        // 최초 후기는 과거 KST 날짜에 적립됐고, 그날 일일 발자국은 다른 Meeting 에서 이미 있다.
        jdbcTemplate.update("""
                insert into meeting_reviews (
                    meeting_id, reviewer_pet_id, place_tag, client_request_id,
                    content, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, meetingId, PET_1, PLACE_TAG, clientRequestId, "즐겁게 산책했어요.",
                java.sql.Timestamp.from(originalCreatedAt), java.sql.Timestamp.from(originalCreatedAt));
        jdbcTemplate.update("""
                insert into footprints (
                    meeting_id, receiver_pet_id, counterpart_pet_id, earned_date, created_at, updated_at
                ) values (?, ?, ?, ?, now(), now())
                """, secondMeetingId, PET_1, PET_2, originalDate);

        MeetingReviewSubmitResult replay = submit(USER_1, meetingId, clientRequestId);

        assertThat(replay.reviewId()).isNotNull();
        assertThat(replay.createdAt()).isEqualTo(originalCreatedAt);
        assertThat(replay.footprint().granted()).isFalse();
        assertThat(replay.footprint().footprintId()).isNotNull();
        assertThat(replay.footprint().duplicateDay()).isTrue();
        assertThat(replay.footprint().earnedDate()).isEqualTo(originalDate);
        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 clientRequestId 를 다른 Meeting 에 재사용하면 409 다")
    void sameClientRequestIdOnDifferentMeetingConflicts() {
        long meetingId = confirmMeeting(cardId, "GPS");
        UUID clientRequestId = UUID.randomUUID();
        submit(USER_1, meetingId, clientRequestId);

        long secondCardId = confirmAnotherCard();
        long secondMeetingId = confirmMeeting(secondCardId, "CODE");

        assertThatThrownBy(() -> submit(USER_1, secondMeetingId, clientRequestId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_REQUEST_CONFLICT);

        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 clientRequestId + 다른 placeTag 는 409 다")
    void sameClientRequestIdWithDifferentPlaceTagIs409() {
        long meetingId = confirmMeeting(cardId, "GPS");
        UUID clientRequestId = UUID.randomUUID();
        submit(USER_1, meetingId, clientRequestId);

        assertThatThrownBy(() -> meetingReviewService.submit(USER_1, meetingId,
                new MeetingReviewSubmitCommand(clientRequestId, "카페", "즐겁게 산책했어요.")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_REQUEST_CONFLICT);

        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select place_tag from meeting_reviews", String.class))
                .isEqualTo(PLACE_TAG);
    }

    @Test
    @DisplayName("같은 Pet·같은 KST 날짜의 다른 Meeting 후기 두 건은 후기 2건·발자국 1건이다")
    void samePetSameKstDayTwoDifferentMeetingsYieldsTwoReviewsOneFootprint() {
        long meetingId = confirmMeeting(cardId, "GPS");
        long secondCardId = confirmAnotherCard();
        long secondMeetingId = confirmMeeting(secondCardId, "CODE");

        MeetingReviewSubmitResult first = submit(USER_1, meetingId, UUID.randomUUID());
        MeetingReviewSubmitResult second = submit(USER_1, secondMeetingId, UUID.randomUUID());

        assertThat(first.footprint().granted()).isTrue();
        assertThat(first.footprint().duplicateDay()).isFalse();
        assertThat(second.footprint().granted()).isFalse();
        assertThat(second.footprint().duplicateDay()).isTrue();
        assertThat(second.footprint().footprintId()).isEqualTo(first.footprint().footprintId());
        assertThat(second.footprint().earnedDate()).isEqualTo(first.footprint().earnedDate());

        assertThat(countOf("meeting_reviews")).isEqualTo(2);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    // ── 동시성 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 Meeting·Pet 동시 후기 요청은 하나만 성공하고 나머지는 REVIEW_ALREADY_EXISTS 다")
    void concurrentReviewsForSameMeetingYieldOneSuccess() throws Exception {
        long meetingId = confirmMeeting(cardId, "GPS");

        List<Object> outcomes = runConcurrently(i ->
                captureSubmit(USER_1, meetingId, UUID.randomUUID()));

        assertThat(outcomes).hasSize(WORKERS);
        long successes = outcomes.stream()
                .filter(o -> o instanceof MeetingReviewSubmitResult)
                .count();
        List<BusinessException> conflicts = outcomes.stream()
                .filter(o -> o instanceof BusinessException)
                .map(o -> (BusinessException) o)
                .toList();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).hasSize(WORKERS - 1);
        assertThat(conflicts).allMatch(e -> e.getErrorCode() == ErrorCode.REVIEW_ALREADY_EXISTS);
        assertThat(outcomes).noneMatch(o -> o instanceof DataIntegrityViolationException);

        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 Pet·같은 날짜 다른 Meeting 동시 후기는 후기 2건·발자국 1건으로 수렴한다")
    void concurrentDailyFootprintRaceConvergesToOneFootprint() throws Exception {
        long meetingId = confirmMeeting(cardId, "GPS");
        long secondCardId = confirmAnotherCard();
        long secondMeetingId = confirmMeeting(secondCardId, "CODE");

        List<Object> outcomes = runConcurrently(i -> {
            long targetMeetingId = (i % 2 == 0) ? meetingId : secondMeetingId;
            return captureSubmit(USER_1, targetMeetingId, UUID.randomUUID());
        });

        assertThat(outcomes).hasSize(WORKERS);
        List<MeetingReviewSubmitResult> successes = outcomes.stream()
                .filter(o -> o instanceof MeetingReviewSubmitResult)
                .map(o -> (MeetingReviewSubmitResult) o)
                .toList();
        List<BusinessException> conflicts = outcomes.stream()
                .filter(o -> o instanceof BusinessException)
                .map(o -> (BusinessException) o)
                .toList();
        // Meeting 당 후기 1건씩, 총 2건만 성공한다.
        assertThat(successes).hasSize(2);
        assertThat(conflicts).hasSize(WORKERS - 2);
        assertThat(conflicts).allMatch(e -> e.getErrorCode() == ErrorCode.REVIEW_ALREADY_EXISTS);
        assertThat(outcomes).noneMatch(o -> o instanceof DataIntegrityViolationException);

        // 발자국은 정확히 한 건: 한 요청은 적립(granted), 다른 요청은 재사용(duplicateDay).
        long granted = successes.stream().filter(s -> s.footprint().granted()).count();
        long duplicated = successes.stream()
                .filter(s -> !s.footprint().granted() && s.footprint().duplicateDay())
                .count();
        assertThat(granted).isEqualTo(1);
        assertThat(duplicated).isEqualTo(1);
        assertThat(successes.get(0).footprint().footprintId())
                .isEqualTo(successes.get(1).footprint().footprintId());

        assertThat(countOf("meeting_reviews")).isEqualTo(2);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 clientRequestId 를 다른 Meeting 에 동시에 제출하면 하나만 성공하고 하나는 409 다")
    void concurrentSameClientRequestIdAcrossDifferentMeetingsYieldsOneConflict() throws Exception {
        long meetingId = confirmMeeting(cardId, "GPS");
        long secondCardId = confirmAnotherCard();
        long secondMeetingId = confirmMeeting(secondCardId, "CODE");
        UUID sharedRequestId = UUID.randomUUID();

        List<Object> outcomes = runTwoConcurrently(
                () -> captureSubmit(USER_1, meetingId, sharedRequestId),
                () -> captureSubmit(USER_1, secondMeetingId, sharedRequestId));

        assertThat(outcomes).hasSize(2);
        long successes = outcomes.stream()
                .filter(o -> o instanceof MeetingReviewSubmitResult)
                .count();
        List<BusinessException> conflicts = outcomes.stream()
                .filter(o -> o instanceof BusinessException)
                .map(o -> (BusinessException) o)
                .toList();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_REQUEST_CONFLICT);
        assertThat(outcomes).noneMatch(o -> o instanceof DataIntegrityViolationException);

        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    // ── 원자적 롤백 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("신규 발자국 저장 실패 시 후기도 롤백된다")
    void rollsBackReviewWhenFootprintInsertFails() {
        long meetingId = confirmMeeting(cardId, "GPS");
        doThrow(new RuntimeException("forced footprint failure"))
                .when(footprintRepository)
                .insertIfDailyAbsent(anyLong(), anyLong(), anyLong(), any(LocalDate.class));

        assertThatThrownBy(() -> submit(USER_1, meetingId, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("forced footprint failure");

        assertThat(countOf("meeting_reviews")).isZero();
        assertThat(countOf("footprints")).isZero();
    }

    @Test
    @DisplayName("후기 저장 실패 시 발자국도 남지 않는다")
    void rollsBackFootprintWhenReviewInsertFails() {
        long meetingId = confirmMeeting(cardId, "GPS");
        doThrow(new RuntimeException("forced review failure"))
                .when(meetingReviewRepository).saveAndFlush(any(MeetingReview.class));

        assertThatThrownBy(() -> submit(USER_1, meetingId, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("forced review failure");

        assertThat(countOf("meeting_reviews")).isZero();
        assertThat(countOf("footprints")).isZero();
    }

    // ── Active Pet 변경·비활성 회귀 ────────────────────────────────────────

    @Test
    @DisplayName("Active Pet 을 바꿔도 과거 Pet 의 발자국은 그대로 남고 목록은 현재 Active Pet 기준이다")
    void activePetChangeKeepsFootprintPerPet() {
        long meetingId = confirmMeeting(cardId, "GPS");
        submit(USER_1, meetingId, UUID.randomUUID());
        insertPet(33L, USER_1);

        setActivePet(USER_1, 33L);
        FootprintListResponse switched = footprintQueryService.listMine(USER_1, null, null);
        assertThat(switched.items()).isEmpty();

        setActivePet(USER_1, PET_1);
        FootprintListResponse restored = footprintQueryService.listMine(USER_1, null, null);
        assertThat(restored.items()).hasSize(1);
        assertThat(restored.items().get(0).counterpartPet().petId()).isEqualTo(PET_2);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    @Test
    @DisplayName("Active Pet 이 없으면 후기 작성·발자국 조회 모두 막힌다")
    void inactivePetCannotSubmitOrList() {
        long meetingId = confirmMeeting(cardId, "GPS");
        jdbcTemplate.update("update users set active_pet_id = null where id = ?", USER_1);

        assertThatThrownBy(() -> submit(USER_1, meetingId, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);
        assertThatThrownBy(() -> footprintQueryService.listMine(USER_1, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);
    }

    @Test
    @DisplayName("Active Pet 전환 경합에서 이전 Pet 명의 후기·발자국이 생기지 않는다")
    void activePetSwitchRaceRejectsPreviousPetReviewWithoutWrites() {
        long meetingId = confirmMeeting(cardId, "GPS");
        long switchedPetId = 33L;
        insertPet(switchedPetId, USER_1);

        // requireActivePet 가 이전 Pet(PET_1)을 읽은 직후, Pair Lock 재검증 전에 active pet 이
        // 전환된 경합을 결정적으로 재현한다. 잠긴 source activePetId 가 달라져
        // CONCURRENT_UPDATE_CONFLICT 로 수렴하고, 이전 Pet 명의 후기·발자국은 0건이다.
        doAnswer(invocation -> {
            ActivePetContext context = (ActivePetContext) invocation.callRealMethod();
            jdbcTemplate.update("update users set active_pet_id = ? where id = ?",
                    switchedPetId, USER_1);
            return context;
        }).when(activePetQueryService).requireActivePet(USER_1);

        assertThatThrownBy(() -> submit(USER_1, meetingId, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT);

        assertThat(countOf("meeting_reviews")).isZero();
        assertThat(countOf("footprints")).isZero();
    }

    // ── 발자국 목록 커서 ────────────────────────────────────────────────────

    @Test
    @DisplayName("발자국 목록은 최신순 커서 페이지다")
    void footprintListIsCursoredNewestFirst() {
        long meeting1 = confirmMeeting(cardId, "GPS");
        long card2 = confirmAnotherCard();
        long meeting2 = confirmMeeting(card2, "GPS");
        long card3 = confirmAnotherCard();
        long meeting3 = confirmMeeting(card3, "CODE");
        jdbcTemplate.update("""
                insert into footprints (meeting_id, receiver_pet_id, counterpart_pet_id, earned_date, created_at)
                values (?, ?, ?, ?, now() - interval '3 days')
                """, meeting1, PET_1, PET_2, LocalDate.now(SEOUL).minusDays(3));
        jdbcTemplate.update("""
                insert into footprints (meeting_id, receiver_pet_id, counterpart_pet_id, earned_date, created_at)
                values (?, ?, ?, ?, now() - interval '2 days')
                """, meeting2, PET_1, PET_2, LocalDate.now(SEOUL).minusDays(2));
        jdbcTemplate.update("""
                insert into footprints (meeting_id, receiver_pet_id, counterpart_pet_id, earned_date, created_at)
                values (?, ?, ?, ?, now() - interval '1 days')
                """, meeting3, PET_1, PET_2, LocalDate.now(SEOUL).minusDays(1));

        FootprintListResponse firstPage = footprintQueryService.listMine(USER_1, null, 2);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.items().get(0).earnedDate())
                .isEqualTo(LocalDate.now(SEOUL).minusDays(1));
        assertThat(firstPage.items().get(1).earnedDate())
                .isEqualTo(LocalDate.now(SEOUL).minusDays(2));
        assertThat(firstPage.page().hasNext()).isTrue();
        assertThat(firstPage.page().nextCursor()).isNotNull();

        FootprintListResponse secondPage =
                footprintQueryService.listMine(USER_1, firstPage.page().nextCursor(), 2);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).earnedDate())
                .isEqualTo(LocalDate.now(SEOUL).minusDays(3));
        assertThat(secondPage.page().hasNext()).isFalse();
        assertThat(secondPage.page().nextCursor()).isNull();
    }

    // ── API 계약(실제 PostgreSQL + MockMvc) ────────────────────────────────

    @Test
    @DisplayName("후기 엔드포인트는 실제 DB 에 후기·발자국을 저장하고 envelope 를 반환한다")
    void reviewEndpointStoresReviewAndFootprint() throws Exception {
        long meetingId = confirmMeeting(cardId, "GPS");

        mockMvc.perform(post("/meetings/{meetingId}/reviews", meetingId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"%s","placeTag":"%s","content":"즐겁게 산책했어요."}
                                """.formatted(UUID.randomUUID(), PLACE_TAG)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.meetingId").value(meetingId))
                .andExpect(jsonPath("$.data.placeTag").value(PLACE_TAG))
                .andExpect(jsonPath("$.data.footprint.granted").value(true))
                .andExpect(jsonPath("$.data.footprint.duplicateDay").value(false));

        assertThat(countOf("meeting_reviews")).isEqualTo(1);
        assertThat(countOf("footprints")).isEqualTo(1);
    }

    // ── DB 제약 최종 방어선 ────────────────────────────────────────────────

    @Test
    @DisplayName("같은 (meeting, reviewer_pet) 후기 두 번째 행은 uk_meeting_review_pet 로 거부된다")
    void databaseRejectsDuplicateReviewForPet() {
        long meetingId = confirmMeeting(cardId, "GPS");
        submit(USER_1, meetingId, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_reviews (meeting_id, reviewer_pet_id, place_tag, client_request_id, content)
                        values (?, ?, ?, ?, '중복')
                        """, meetingId, PET_1, PLACE_TAG, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_meeting_review_pet");
    }

    @Test
    @DisplayName("같은 clientRequestId 두 번째 행은 uk_meeting_review_client_request 로 거부된다")
    void databaseRejectsDuplicateReviewClientRequest() {
        long meetingId = confirmMeeting(cardId, "GPS");
        UUID usedRequestId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into meeting_reviews (meeting_id, reviewer_pet_id, place_tag, client_request_id, content)
                values (?, ?, ?, ?, '첫 후기')
                """, meetingId, PET_1, PLACE_TAG, usedRequestId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_reviews (meeting_id, reviewer_pet_id, place_tag, client_request_id, content)
                        values (?, ?, ?, ?, '둘째 후기')
                        """, meetingId, PET_2, PLACE_TAG, usedRequestId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_meeting_review_client_request");
    }

    @Test
    @DisplayName("같은 Pet·같은 날짜 발자국 두 번째 행은 uk_footprint_pet_date 로 거부된다")
    void databaseRejectsDuplicateFootprintForPetAndDate() {
        long meetingId = confirmMeeting(cardId, "GPS");
        long secondCardId = confirmAnotherCard();
        long secondMeetingId = confirmMeeting(secondCardId, "CODE");
        jdbcTemplate.update("""
                insert into footprints (meeting_id, receiver_pet_id, counterpart_pet_id, earned_date)
                values (?, ?, ?, ?)
                """, meetingId, PET_1, PET_2, LocalDate.now(SEOUL));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into footprints (meeting_id, receiver_pet_id, counterpart_pet_id, earned_date)
                        values (?, ?, ?, ?)
                        """, secondMeetingId, PET_1, PET_2, LocalDate.now(SEOUL)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_footprint_pet_date");
    }

    @Test
    @DisplayName("같은 (meeting, receiver) 발자국 두 번째 행은 uk_footprint_meeting_pet 로 거부된다")
    void databaseRejectsDuplicateFootprintForMeetingAndPet() {
        long meetingId = confirmMeeting(cardId, "GPS");
        jdbcTemplate.update("""
                insert into footprints (meeting_id, receiver_pet_id, counterpart_pet_id, earned_date)
                values (?, ?, ?, ?)
                """, meetingId, PET_1, PET_2, LocalDate.now(SEOUL));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into footprints (meeting_id, receiver_pet_id, counterpart_pet_id, earned_date)
                        values (?, ?, ?, ?)
                        """, meetingId, PET_1, PET_2, LocalDate.now(SEOUL).plusDays(1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_footprint_meeting_pet");
    }

    @Test
    @DisplayName("후기는 meetings FK 가 없으면 저장할 수 없다")
    void databaseRejectsReviewForMissingMeeting() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_reviews (meeting_id, reviewer_pet_id, place_tag, client_request_id, content)
                        values (99999, ?, ?, ?, '후기')
                        """, PET_1, PLACE_TAG, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("meeting_reviews_meeting_id_fkey");
    }

    @Test
    @DisplayName("V45 스키마는 place_tag 필수(NOT NULL, 최대 30자)다")
    void placeTagIsRequiredBySchema() {
        long meetingId = confirmMeeting(cardId, "GPS");
        // place_tag 없이 후기를 넣으면 NOT NULL 위반이다.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_reviews (meeting_id, reviewer_pet_id, client_request_id, content)
                        values (?, ?, ?, '태그 없음')
                        """, meetingId, PET_1, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("place_tag");
        // 30자 초과 place_tag 는 값 길이 위반이다.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_reviews (meeting_id, reviewer_pet_id, place_tag, client_request_id, content)
                        values (?, ?, ?, ?, '태그 초과')
                        """, meetingId, PET_1, "가".repeat(31), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("place_tag");
        // 스키마 컬럼 정보가 엔티티와 일치한다(V45 반영 확인).
        assertThat(jdbcTemplate.queryForObject("""
                select character_maximum_length
                from information_schema.columns
                where table_name = 'meeting_reviews' and column_name = 'place_tag'
                """, Integer.class)).isEqualTo(30);
    }

    @Test
    @DisplayName("발자국은 pets FK 가 없으면 저장할 수 없다")
    void databaseRejectsFootprintForMissingPet() {
        long meetingId = confirmMeeting(cardId, "GPS");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into footprints (meeting_id, receiver_pet_id, counterpart_pet_id, earned_date)
                        values (?, 99999, ?, ?)
                        """, meetingId, PET_2, LocalDate.now(SEOUL)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("footprints_receiver_pet_id_fkey");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private MeetingReviewSubmitResult submit(long userId, long meetingId, UUID clientRequestId) {
        return meetingReviewService.submit(userId, meetingId,
                new MeetingReviewSubmitCommand(clientRequestId, PLACE_TAG, "즐겁게 산책했어요."));
    }

    private Object captureSubmit(long userId, long meetingId, UUID clientRequestId) {
        try {
            return meetingReviewService.submit(userId, meetingId,
                    new MeetingReviewSubmitCommand(clientRequestId, PLACE_TAG, "즐겁게 산책했어요."));
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private long confirmMeeting(long targetCardId, String method) {
        // V43 ck_meeting_distance_by_method: GPS 는 거리 필수, CODE 는 null.
        Double distanceMeters = "GPS".equals(method) ? 100.0 : null;
        return jdbcTemplate.queryForObject("""
                insert into meetings (meeting_card_id, verification_method, confirmed_at, distance_meters)
                values (?, ?, now(), ?)
                returning id
                """, Long.class, targetCardId, method, distanceMeters);
    }

    private long confirmAnotherCard() {
        return meetingCardService.confirm(USER_1,
                new MeetingCardCreateRequest(roomId, null, MeetingCardType.WALK,
                        "다른공원", Instant.now().plus(2, ChronoUnit.DAYS))).cardId();
    }

    private long insertCanceledCard() {
        long canceledCardId = jdbcTemplate.queryForObject("""
                insert into meeting_cards (
                    room_id, creator_pet_id, source_draft_id, card_type, place_text, meet_at,
                    status, canceled_by_pet_id, canceled_at
                ) values (?, ?, null, 'WALK', '취소된공원', now(), 'CANCELED', ?, now())
                returning id
                """, Long.class, roomId, PET_1, PET_2);
        jdbcTemplate.update("""
                insert into meeting_participants (meeting_card_id, pet_id)
                values (?, ?), (?, ?)
                """, canceledCardId, PET_1, canceledCardId, PET_2);
        return canceledCardId;
    }

    private MeetingCardCreateRequest request(long roomId) {
        return new MeetingCardCreateRequest(roomId, null, MeetingCardType.WALK,
                "중앙공원", Instant.now().plus(1, ChronoUnit.DAYS));
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

    private CurrentUser principal(long userId) {
        return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER);
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
