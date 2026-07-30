package itda.meetingcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.chat.domain.RoomOrigin;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.dto.response.MeetingCardResponse;
import itda.meetingcard.service.MeetingCardBlockCleanupService;
import itda.meetingcard.service.MeetingCardService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/**
 * 약속 카드 확정·조회·취소를 실제 PostgreSQL 에서 검증한다.
 *
 * <p>단위 테스트로는 증명할 수 없는 것만 여기서 본다. 확정 세 행의 원자성, 동시 취소에서
 * 한쪽만 성공하는 것, DB 제약이 실제로 걸려 있는지다.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class MeetingCardPostgreSqlIntegrationTest {

    private static final int WORKERS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MeetingCardService meetingCardService;
    @Autowired
    private MeetingCardBlockCleanupService meetingCardBlockCleanupService;

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
                truncate meeting_participants, meeting_cards, card_drafts,
                         chat_messages, chat_room_participants, chat_rooms,
                         pets, users
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

    // ── 확정 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("확정 시 카드 1행·참여자 2행·CARD 메시지 1건이 함께 생긴다")
    void confirmCreatesCardParticipantsAndCardMessage() {
        MeetingCardResponse card = meetingCardService.confirm(USER_1, request(null));

        assertThat(card.cardId()).isNotNull();
        assertThat(card.status()).isEqualTo(MeetingCardStatus.OPEN);
        assertThat(card.creatorPetId()).isEqualTo(PET_1);
        assertThat(card.participantPetIds()).containsExactlyInAnyOrder(PET_1, PET_2);

        assertThat(countOf("meeting_cards")).isEqualTo(1);
        assertThat(countOf("meeting_participants")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from chat_messages
                 where type = 'CARD' and meeting_card_id = ?
                """, Integer.class, card.cardId())).isEqualTo(1);
    }

    @Test
    @DisplayName("HOSPITAL 로도 확정된다")
    void confirmAcceptsHospital() {
        MeetingCardResponse card = meetingCardService.confirm(
                USER_1,
                new MeetingCardCreateRequest(roomId, null, MeetingCardType.HOSPITAL,
                        "동물병원", Instant.now().plus(1, ChronoUnit.DAYS)));

        assertThat(card.cardType()).isEqualTo(MeetingCardType.HOSPITAL);
        assertThat(jdbcTemplate.queryForObject(
                "select card_type from meeting_cards where id = ?",
                String.class, card.cardId())).isEqualTo("HOSPITAL");
    }

    @Test
    @DisplayName("Active Pet 이 없으면 확정 전에 막힌다")
    void confirmRequiresActivePet() {
        jdbcTemplate.update("update users set active_pet_id = null where id = ?", USER_1);

        assertThatThrownBy(() -> meetingCardService.confirm(USER_1, request(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);

        assertThat(countOf("meeting_cards")).isZero();
    }

    @Test
    @DisplayName("방 참가자가 아니면 404 로 수렴하고 아무것도 저장되지 않는다")
    void confirmHidesRoomFromNonParticipant() {
        insertUser(3L);
        insertPet(33L, 3L);
        setActivePet(3L, 33L);

        assertThatThrownBy(() -> meetingCardService.confirm(3L, request(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        assertThat(countOf("meeting_cards")).isZero();
        assertThat(countOf("chat_messages")).isZero();
    }

    // ── draftId 검증 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("남의 초안으로는 확정할 수 없다")
    void confirmRejectsDraftOwnedByAnotherPet() {
        long othersDraft = insertDraft(roomId, PET_2);

        assertThatThrownBy(() -> meetingCardService.confirm(USER_1, request(othersDraft)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("다른 방 초안으로는 확정할 수 없다")
    void confirmRejectsDraftFromAnotherRoom() {
        insertPet(33L, USER_2);
        long otherRoomId = chatRoomService.ensureDirectRoom(PET_1, 33L, RoomOrigin.FRIEND).roomId();
        long draftInOtherRoom = insertDraft(otherRoomId, PET_1);

        assertThatThrownBy(() -> meetingCardService.confirm(USER_1, request(draftInOtherRoom)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("한 초안으로 카드를 두 번 만들 수 없다")
    void oneDraftCannotCreateTwoCards() {
        long draftId = insertDraft(roomId, PET_1);
        meetingCardService.confirm(USER_1, request(draftId));

        assertThatThrownBy(() -> meetingCardService.confirm(USER_1, request(draftId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(countOf("meeting_cards")).isEqualTo(1);
    }

    @Test
    @DisplayName("초안 없이도 확정할 수 있고 source_draft_id 는 null 이다")
    void confirmWithoutDraftLeavesSourceDraftNull() {
        MeetingCardResponse card = meetingCardService.confirm(USER_1, request(null));

        assertThat(jdbcTemplate.queryForObject(
                "select source_draft_id from meeting_cards where id = ?",
                Long.class, card.cardId())).isNull();
    }

    // ── 조회 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("참여 Pet 양쪽 모두 조회할 수 있다")
    void bothParticipantsCanRead() {
        long cardId = meetingCardService.confirm(USER_1, request(null)).cardId();

        assertThat(meetingCardService.get(USER_1, cardId).participantPetIds()).hasSize(2);
        assertThat(meetingCardService.get(USER_2, cardId).participantPetIds()).hasSize(2);
    }

    @Test
    @DisplayName("참여자가 아니면 카드 존재를 숨긴다")
    void nonParticipantCannotReadCard() {
        long cardId = meetingCardService.confirm(USER_1, request(null)).cardId();
        insertUser(3L);
        insertPet(33L, 3L);
        setActivePet(3L, 33L);

        assertThatThrownBy(() -> meetingCardService.get(3L, cardId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);
    }

    @Test
    @DisplayName("없는 카드는 404 다")
    void missingCardIsNotFound() {
        assertThatThrownBy(() -> meetingCardService.get(USER_1, 9999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);
    }

    // ── 취소 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("취소하면 CANCELED 와 취소 흔적이 함께 채워지고 SYSTEM 메시지가 1건 생긴다")
    void cancelSetsStateAndPostsSystemMessage() {
        long cardId = meetingCardService.confirm(USER_1, request(null)).cardId();

        MeetingCardResponse canceled = meetingCardService.cancel(USER_2, cardId);

        assertThat(canceled.status()).isEqualTo(MeetingCardStatus.CANCELED);
        assertThat(canceled.canceledByPetId()).isEqualTo(PET_2);
        assertThat(canceled.canceledAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from chat_messages
                 where type = 'SYSTEM' and room_id = ?
                """, Integer.class, roomId)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 취소된 카드를 다시 취소하면 409 다")
    void secondCancelConflicts() {
        long cardId = meetingCardService.confirm(USER_1, request(null)).cardId();
        meetingCardService.cancel(USER_1, cardId);

        assertThatThrownBy(() -> meetingCardService.cancel(USER_2, cardId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_ALREADY_CANCELED);
    }

    @Test
    @DisplayName("참여자가 아니면 취소도 404 로 수렴한다")
    void nonParticipantCannotCancel() {
        long cardId = meetingCardService.confirm(USER_1, request(null)).cardId();
        insertUser(3L);
        insertPet(33L, 3L);
        setActivePet(3L, 33L);

        assertThatThrownBy(() -> meetingCardService.cancel(3L, cardId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?",
                String.class, cardId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("양쪽이 동시에 취소해도 성공 1건·409 나머지·SYSTEM 메시지 정확히 1건")
    void concurrentCancelProducesOneSuccessAndOneSystemMessage() throws Exception {
        long cardId = meetingCardService.confirm(USER_1, request(null)).cardId();

        // 두 참여자를 번갈아 붙여 양방향 동시 취소를 만든다.
        List<Object> outcomes = runConcurrently(i -> {
            long actor = (i % 2 == 0) ? USER_1 : USER_2;
            try {
                return meetingCardService.cancel(actor, cardId);
            } catch (BusinessException e) {
                return e.getErrorCode();
            }
        });

        long succeeded = outcomes.stream().filter(o -> o instanceof MeetingCardResponse).count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(outcomes.stream()
                .filter(o -> o instanceof ErrorCode)
                .allMatch(o -> o == ErrorCode.MEETING_CARD_ALREADY_CANCELED)).isTrue();

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from chat_messages
                 where type = 'SYSTEM' and room_id = ?
                """, Integer.class, roomId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?",
                String.class, cardId)).isEqualTo("CANCELED");
    }

    // ── 차단 연동 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("차단하면 두 User 사이의 OPEN 카드가 취소되고 취소 흔적이 남는다")
    void blockCancelsOpenCardsBetweenUsers() {
        long cardId = meetingCardService.confirm(USER_1, request(null)).cardId();

        int canceled = meetingCardBlockCleanupService
                .cancelOpenCardsBetweenUsers(USER_1, USER_2, PET_1);

        assertThat(canceled).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?",
                String.class, cardId)).isEqualTo("CANCELED");
        // ck_meeting_card_cancel 이 취소 흔적을 요구하므로 둘 다 채워져야 한다.
        assertThat(jdbcTemplate.queryForObject(
                "select canceled_by_pet_id from meeting_cards where id = ?",
                Long.class, cardId)).isEqualTo(PET_1);
        assertThat(jdbcTemplate.queryForObject(
                "select canceled_at from meeting_cards where id = ?",
                java.sql.Timestamp.class, cardId)).isNotNull();
    }

    @Test
    @DisplayName("차단은 두 User 의 모든 Pet 쌍을 훑는다. UI 에서 고른 Pet 만 보지 않는다")
    void blockCancelsCardsAcrossEveryPetPair() {
        // USER_2 의 두 번째 Pet 으로 만든 별도 방과 카드.
        insertPet(33L, USER_2);
        long otherRoomId = chatRoomService
                .ensureDirectRoom(PET_1, 33L, RoomOrigin.FRIEND).roomId();
        long cardInFirstRoom = meetingCardService.confirm(USER_1, request(null)).cardId();
        long cardInSecondRoom = meetingCardService.confirm(
                USER_1,
                new MeetingCardCreateRequest(otherRoomId, null, MeetingCardType.PLAY,
                        "놀이터", Instant.now().plus(1, ChronoUnit.DAYS))).cardId();

        int canceled = meetingCardBlockCleanupService
                .cancelOpenCardsBetweenUsers(USER_1, USER_2, PET_1);

        assertThat(canceled).isEqualTo(2);
        for (long id : new long[] {cardInFirstRoom, cardInSecondRoom}) {
            assertThat(jdbcTemplate.queryForObject(
                    "select status from meeting_cards where id = ?",
                    String.class, id)).isEqualTo("CANCELED");
        }
    }

    @Test
    @DisplayName("차단은 무관한 User 의 카드와 이미 취소된 카드를 건드리지 않는다")
    void blockLeavesUnrelatedAndAlreadyCanceledCardsAlone() {
        long alreadyCanceled = meetingCardService.confirm(USER_1, request(null)).cardId();
        meetingCardService.cancel(USER_1, alreadyCanceled);
        Long firstCanceledBy = jdbcTemplate.queryForObject(
                "select canceled_by_pet_id from meeting_cards where id = ?",
                Long.class, alreadyCanceled);

        // 제3자끼리의 방과 카드.
        insertUser(3L);
        insertUser(4L);
        insertPet(33L, 3L);
        insertPet(44L, 4L);
        setActivePet(3L, 33L);
        long strangerRoomId = chatRoomService
                .ensureDirectRoom(33L, 44L, RoomOrigin.GREETING).roomId();
        long strangerCardId = meetingCardService.confirm(
                3L,
                new MeetingCardCreateRequest(strangerRoomId, null, MeetingCardType.WALK,
                        "다른공원", Instant.now().plus(1, ChronoUnit.DAYS))).cardId();

        int canceled = meetingCardBlockCleanupService
                .cancelOpenCardsBetweenUsers(USER_1, USER_2, PET_1);

        assertThat(canceled).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select status from meeting_cards where id = ?",
                String.class, strangerCardId)).isEqualTo("OPEN");
        // 이미 취소된 카드의 취소자가 덮어써지면 안 된다.
        assertThat(jdbcTemplate.queryForObject(
                "select canceled_by_pet_id from meeting_cards where id = ?",
                Long.class, alreadyCanceled)).isEqualTo(firstCanceledBy);
    }

    // ── DB 제약 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DB 가 계약 밖 card_type 을 ck_meeting_card_type 으로 거부한다")
    void databaseRejectsUnknownCardType() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_cards (room_id, creator_pet_id, card_type, place_text, meet_at)
                        values (?, ?, 'DINNER', '중앙공원', now())
                        """, roomId, PET_1))
                .hasMessageContaining("ck_meeting_card_type");
    }

    @Test
    @DisplayName("DB 가 취소 흔적 없는 CANCELED 를 ck_meeting_card_cancel 로 거부한다")
    void databaseRejectsHalfCancel() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_cards
                            (room_id, creator_pet_id, card_type, place_text, meet_at, status)
                        values (?, ?, 'WALK', '중앙공원', now(), 'CANCELED')
                        """, roomId, PET_1))
                .hasMessageContaining("ck_meeting_card_cancel");
    }

    @Test
    @DisplayName("DB 가 같은 초안의 두 번째 카드를 uk_meeting_card_source_draft 로 거부한다")
    void databaseRejectsDuplicateSourceDraft() {
        long draftId = insertDraft(roomId, PET_1);
        jdbcTemplate.update("""
                insert into meeting_cards
                    (room_id, creator_pet_id, source_draft_id, card_type, place_text, meet_at)
                values (?, ?, ?, 'WALK', '중앙공원', now())
                """, roomId, PET_1, draftId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into meeting_cards
                            (room_id, creator_pet_id, source_draft_id, card_type, place_text, meet_at)
                        values (?, ?, ?, 'PLAY', '다른곳', now())
                        """, roomId, PET_2, draftId))
                .hasMessageContaining("uk_meeting_card_source_draft");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private MeetingCardCreateRequest request(Long draftId) {
        return new MeetingCardCreateRequest(roomId, draftId, MeetingCardType.WALK,
                "중앙공원", Instant.now().plus(1, ChronoUnit.DAYS));
    }

    private long insertDraft(long draftRoomId, long requestedByPetId) {
        jdbcTemplate.update("""
                insert into card_drafts (room_id, requested_by_pet_id, card_type, place_text)
                values (?, ?, 'WALK', '중앙공원')
                """, draftRoomId, requestedByPetId);
        return jdbcTemplate.queryForObject(
                "select max(id) from card_drafts", Long.class);
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

    private int countOf(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
