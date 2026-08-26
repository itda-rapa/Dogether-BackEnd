package itda.meetingcard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.chat.domain.RoomOrigin;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.service.ChatQueryService;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.ai.FixtureMeetingDraftAiClient;
import itda.meetingcard.ai.AiDraftResult;
import itda.meetingcard.ai.MeetingCardAiAdapter;
import itda.common.security.CurrentUser;
import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.dto.response.CardDraftResponse;
import itda.meetingcard.dto.response.MeetingCardResponse;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AI 약속 초안 생성을 실제 PostgreSQL 에서 검증한다.
 *
 * <p>AI 클라이언트만 fixture 로 바꿔 끼우고 나머지는 전부 실물이다. AI 실패·지연·컨텍스트
 * 부족이 사용자에게 5xx 로 새지 않고 빈 폼으로 수렴하는지, 그리고 원본 메시지 선별 규칙이
 * 실제 SQL 에서 동작하는지가 확인 대상이다.
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
class CardDraftPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    /** 서울에서는 2026-07-30, UTC 에서는 2026-07-29. 존을 틀리면 잡힌다. */
    private static final Instant NOW = Instant.parse("2026-07-29T16:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private ActivePetQueryService activePetQueryService;
    @Autowired
    private ChatQueryService chatQueryService;
    @Autowired
    private ChatMessageRepository chatMessageRepository;
    @Autowired
    private CardDraftTransactionService cardDraftTransactionService;
    @Autowired
    private MeetingCardService meetingCardService;
    @Autowired
    private ChatRoomService chatRoomService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mockMvc;
    @MockitoBean(name = "meetingCardAiAdapter")
    private MeetingCardAiAdapter productionAiClient;

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final String NEIGHBORHOOD = "4113111500";

    private long roomId;
    private FixtureMeetingDraftAiClient ai;

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
        ai = new FixtureMeetingDraftAiClient(SEOUL);
    }

    /** 고정 시각과 fixture AI 를 물린 서비스. 프로덕션 빈 대신 이걸 쓴다. */
    private CardDraftService service() {
        return new CardDraftService(
                activePetQueryService,
                chatQueryService,
                chatMessageRepository,
                cardDraftTransactionService,
                ai,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── AI 결과별 fallback 수렴 ─────────────────────────────────────────────

    @Test
    @DisplayName("정상 추출은 fallback=false 이고 추출값이 저장된다")
    void fullExtractionIsNotFallback() {
        insertTextMessages(2);
        ai.prepareFullExtraction("WALK", "2026-07-31", "19:00", "중앙공원");

        CardDraftResponse draft = service().createDraft(USER_1, roomId).get(0);

        assertThat(draft.fallback()).isFalse();
        assertThat(draft.fallbackReason()).isNull();
        assertThat(draft.cardType()).isEqualTo(MeetingCardType.WALK);
        assertThat(draft.placeText()).isEqualTo("중앙공원");
        assertThat(draft.meetAt()).isNotNull();
        assertThat(countOf("card_drafts")).isEqualTo(1);
    }

    @Test
    @DisplayName("HOSPITAL 은 OTHER 로 접히지 않고 그대로 저장된다")
    void hospitalSurvives() {
        insertTextMessages(2);
        ai.prepareHospitalExtraction("2026-07-31", "10:00", "동물병원");

        CardDraftResponse draft = service().createDraft(USER_1, roomId).get(0);

        assertThat(draft.cardType()).isEqualTo(MeetingCardType.HOSPITAL);
        assertThat(jdbcTemplate.queryForObject(
                "select card_type from card_drafts where id = ?",
                String.class, draft.draftId())).isEqualTo("HOSPITAL");
    }

    @Test
    @DisplayName("빈 배열은 fallback 이 아니고 모든 추출 필드가 null 이다")
    void emptyArrayIsNotFallback() {
        insertTextMessages(2);
        ai.prepareEmptyArray();

        List<CardDraftResponse> drafts = service().createDraft(USER_1, roomId);
        assertThat(drafts).hasSize(1);
        CardDraftResponse draft = drafts.get(0);

        assertThat(draft.fallback()).isFalse();
        assertThat(draft.fallbackReason()).isNull();
        assertThat(draft.cardType()).isNull();
        assertThat(draft.placeText()).isNull();
        assertThat(draft.meetAt()).isNull();
    }

    @Test
    @DisplayName("모르는 종류는 cardType 만 null 이고 fallback 이 아니다")
    void unknownTypeOnlyNullsCardType() {
        insertTextMessages(2);
        ai.prepareUnknownType("DINNER", "2026-07-31", "19:00", "중앙공원");

        CardDraftResponse draft = service().createDraft(USER_1, roomId).get(0);

        assertThat(draft.fallback()).isFalse();
        assertThat(draft.cardType()).isNull();
        assertThat(draft.placeText()).isEqualTo("중앙공원");
    }

    @Test
    @DisplayName("날짜만 있고 시각이 없으면 meetAt 은 null 이고 장소는 남는다")
    void partialExtractionKeepsPlaceAndNullsMeetAt() {
        insertTextMessages(2);
        ai.prepareDateOnly("WALK", "2026-07-31", "중앙공원");

        CardDraftResponse draft = service().createDraft(USER_1, roomId).get(0);

        assertThat(draft.fallback()).isFalse();
        assertThat(draft.meetAt()).isNull();
        assertThat(draft.placeText()).isEqualTo("중앙공원");
    }

    @Test
    @DisplayName("타임아웃은 TIMEOUT 빈 폼이고 200 으로 수렴한다")
    void timeoutBecomesEmptyForm() {
        insertTextMessages(2);
        ai.prepareTimeout();

        List<CardDraftResponse> drafts = service().createDraft(USER_1, roomId);
        assertThat(drafts).hasSize(1);
        CardDraftResponse draft = drafts.get(0);

        assertThat(draft.fallback()).isTrue();
        assertThat(draft.fallbackReason()).isEqualTo(CardDraftFallbackReason.TIMEOUT);
        assertThat(draft.cardType()).isNull();
    }

    @Test
    @DisplayName("모델 오류와 통신 실패는 MODEL_ERROR 빈 폼이다")
    void modelErrorAndConnectionFailureBecomeEmptyForm() {
        insertTextMessages(2);
        ai.prepareModelError();
        List<CardDraftResponse> modelErrorDrafts = service().createDraft(USER_1, roomId);
        assertThat(modelErrorDrafts).hasSize(1);
        assertThat(modelErrorDrafts.get(0).fallbackReason())
                .isEqualTo(CardDraftFallbackReason.MODEL_ERROR);

        ai.prepareConnectionFailure();
        List<CardDraftResponse> connectionFailureDrafts = service().createDraft(USER_1, roomId);
        assertThat(connectionFailureDrafts).hasSize(1);
        assertThat(connectionFailureDrafts.get(0).fallbackReason())
                .isEqualTo(CardDraftFallbackReason.MODEL_ERROR);
    }

    @Test
    @DisplayName("원소가 둘이면 두 초안을 순서대로 저장한다")
    void twoElementsBecomeTwoOrderedDrafts() {
        insertTextMessages(2);
        ai.prepareTwoElements();

        var drafts = service().createDraft(USER_1, roomId);

        assertThat(drafts).hasSize(2);
        assertThat(drafts).extracting(CardDraftResponse::draftId)
                .doesNotHaveDuplicates();
        assertThat(drafts).extracting(CardDraftResponse::cardType)
                .containsExactly(MeetingCardType.WALK, MeetingCardType.PLAY);
        assertThat(drafts).extracting(CardDraftResponse::placeText)
                .containsExactly("중앙공원", "댕댕카페");
        assertThat(drafts).extracting(CardDraftResponse::meetAt)
                .containsExactly(
                        Instant.parse("2026-07-31T10:00:00Z"),
                        Instant.parse("2026-08-01T01:00:00Z"));
        assertThat(drafts).allMatch(draft -> !draft.fallback());
        assertThat(countOf("card_drafts")).isEqualTo(2);
    }

    @Test
    @DisplayName("REST 단건 응답도 data 배열이다")
    void restSingleCandidateUsesArrayData() throws Exception {
        insertRecentTextMessages(2);
        whenProductionAiReturns(List.of(candidate("WALK", "2026-07-31", "19:00", "중앙공원")));

        mockMvc.perform(post("/chat/rooms/{roomId}/card-drafts", roomId)
                        .with(user(new CurrentUser(USER_1, "user1@test.com", Role.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].cardType").value("WALK"));
    }

    @Test
    @DisplayName("REST 다건 응답은 AI 순서와 후보 필드를 보존한다")
    void restMultipleCandidatesUsesOrderedArrayData() throws Exception {
        insertRecentTextMessages(2);
        whenProductionAiReturns(List.of(
                candidate("WALK", "2026-07-31", "19:00", "중앙공원"),
                candidate("PLAY", "2026-08-01", "10:00", "댕댕카페")));

        mockMvc.perform(post("/chat/rooms/{roomId}/card-drafts", roomId)
                        .with(user(new CurrentUser(USER_1, "user1@test.com", Role.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].cardType").value("WALK"))
                .andExpect(jsonPath("$.data[0].placeText").value("중앙공원"))
                .andExpect(jsonPath("$.data[0].meetAt").value("2026-07-31T10:00:00Z"))
                .andExpect(jsonPath("$.data[1].cardType").value("PLAY"))
                .andExpect(jsonPath("$.data[1].placeText").value("댕댕카페"))
                .andExpect(jsonPath("$.data[1].meetAt").value("2026-08-01T01:00:00Z"));

        assertThat(countOf("card_drafts")).isEqualTo(2);
    }

    @Test
    @DisplayName("REST fallback은 blank 초안 한 건을 배열로 반환한다")
    void restFallbackUsesSingleBlankCandidateArray() throws Exception {
        insertRecentTextMessages(2);
        org.mockito.Mockito.when(productionAiClient.extract(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(AiDraftResult.fallback(CardDraftFallbackReason.TIMEOUT));

        mockMvc.perform(post("/chat/rooms/{roomId}/card-drafts", roomId)
                        .with(user(new CurrentUser(USER_1, "user1@test.com", Role.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fallback").value(true))
                .andExpect(jsonPath("$.data[0].fallbackReason").value("TIMEOUT"));
    }

    @Test
    @DisplayName("수동 초안의 AI 422 도 200 + 빈 폼으로 수렴하고 fallbackReason=INVALID_REQUEST 다")
    void restAi422BecomesEmptyFormWithInvalidRequest() throws Exception {
        insertRecentTextMessages(2);
        org.mockito.Mockito.when(productionAiClient.extract(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(AiDraftResult.fallback(CardDraftFallbackReason.INVALID_REQUEST));

        mockMvc.perform(post("/chat/rooms/{roomId}/card-drafts", roomId)
                        .with(user(new CurrentUser(USER_1, "user1@test.com", Role.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fallback").value(true))
                .andExpect(jsonPath("$.data[0].fallbackReason").value("INVALID_REQUEST"));

        assertThat(countOf("card_drafts")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select fallback_reason from card_drafts", String.class))
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("다건 초안 중 선택한 draftId로 약속 카드를 확정한다")
    void selectedMultiCandidateDraftCanConfirmCard() {
        insertTextMessages(2);
        ai.prepareTwoElements();
        var drafts = service().createDraft(USER_1, roomId);

        MeetingCardResponse card = meetingCardService.confirm(USER_1,
                new MeetingCardCreateRequest(
                        roomId,
                        drafts.get(1).draftId(),
                        MeetingCardType.PLAY,
                        "댕댕카페",
                        Instant.parse("2026-08-01T01:00:00Z")));

        assertThat(card.cardType()).isEqualTo(MeetingCardType.PLAY);
        assertThat(jdbcTemplate.queryForObject(
                "select source_draft_id from meeting_cards where id = ?",
                Long.class,
                card.cardId())).isEqualTo(drafts.get(1).draftId());
    }

    @Test
    @DisplayName("AI 가 500자를 넘는 장소를 주면 잘라서 저장하고 500 을 내지 않는다")
    void overlongPlaceIsTruncatedInsteadOfFailing() {
        insertTextMessages(2);
        // AI 가 장소를 못 뽑고 대화 문장을 그대로 넣어 보내는 경우를 재현한다.
        ai.prepareFullExtraction("WALK", "2026-07-31", "19:00", "가".repeat(700));

        CardDraftResponse draft = service().createDraft(USER_1, roomId).get(0);

        assertThat(draft.placeText()).hasSize(500);
        assertThat(draft.fallback()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select length(place_text) from card_drafts where id = ?",
                Integer.class, draft.draftId())).isEqualTo(500);
    }

    // ── 메시지 선별 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("메시지가 0개면 AI 를 부르지 않고 INSUFFICIENT_CONTEXT 다")
    void zeroMessagesBypassesAi() {
        ai.prepareFullExtraction("WALK", "2026-07-31", "19:00", "중앙공원");

        CardDraftResponse draft = service().createDraft(USER_1, roomId).get(0);

        assertThat(draft.fallbackReason())
                .isEqualTo(CardDraftFallbackReason.INSUFFICIENT_CONTEXT);
        assertThat(ai.callCount()).isZero();
    }

    @Test
    @DisplayName("메시지가 1개면 AI 를 부르지 않는다. 임계값은 2 미만이다")
    void oneMessageBypassesAi() {
        insertTextMessages(1);
        ai.prepareFullExtraction("WALK", "2026-07-31", "19:00", "중앙공원");

        CardDraftResponse draft = service().createDraft(USER_1, roomId).get(0);

        assertThat(draft.fallbackReason())
                .isEqualTo(CardDraftFallbackReason.INSUFFICIENT_CONTEXT);
        assertThat(ai.callCount()).isZero();
    }

    @Test
    @DisplayName("24시간보다 오래된 메시지는 세지 않는다")
    void messagesOlderThan24HoursAreExcluded() {
        insertTextMessage(PET_1, "어제 얘기", NOW.minusSeconds(25 * 3600));
        insertTextMessage(PET_2, "그것도 어제", NOW.minusSeconds(26 * 3600));
        insertTextMessage(PET_1, "방금", NOW.minusSeconds(60));
        ai.prepareFullExtraction("WALK", "2026-07-31", "19:00", "중앙공원");

        // 24시간 내 메시지가 1건뿐이라 AI 를 부르지 않아야 한다.
        assertThat(service().createDraft(USER_1, roomId).get(0).fallbackReason())
                .isEqualTo(CardDraftFallbackReason.INSUFFICIENT_CONTEXT);
        assertThat(ai.callCount()).isZero();
    }

    @Test
    @DisplayName("CARD·SYSTEM 메시지는 원본에서 제외된다")
    void cardAndSystemMessagesAreExcluded() {
        insertTextMessage(PET_1, "안녕", NOW.minusSeconds(600));
        insertSystemMessage("약속이 취소되었습니다.", NOW.minusSeconds(500));
        ai.prepareFullExtraction("WALK", "2026-07-31", "19:00", "중앙공원");

        // TEXT 1건 + SYSTEM 1건이지만 TEXT 만 세므로 AI 를 부르지 않는다.
        assertThat(service().createDraft(USER_1, roomId).get(0).fallbackReason())
                .isEqualTo(CardDraftFallbackReason.INSUFFICIENT_CONTEXT);
        assertThat(ai.callCount()).isZero();
    }

    @Test
    @DisplayName("AI 에는 시간순으로 넘긴다. 저장소는 최신순으로 주므로 되돌려야 한다")
    void messagesReachAiInChronologicalOrder() {
        insertTextMessage(PET_1, "첫번째", NOW.minusSeconds(3000));
        insertTextMessage(PET_2, "두번째", NOW.minusSeconds(2000));
        insertTextMessage(PET_1, "세번째", NOW.minusSeconds(1000));
        ai.prepareFullExtraction("WALK", "2026-07-31", "19:00", "중앙공원");

        service().createDraft(USER_1, roomId);

        assertThat(ai.lastCommand().messages())
                .extracting(m -> m.content())
                .containsExactly("첫번째", "두번째", "세번째");
    }

    @Test
    @DisplayName("최대 30개만 넘기고 잘려나가는 쪽은 오래된 메시지다")
    void atMostThirtyMessagesAndOldestAreDropped() {
        for (int i = 1; i <= 35; i++) {
            insertTextMessage(i % 2 == 0 ? PET_1 : PET_2, "m" + i,
                    NOW.minusSeconds(4000L - i * 10));
        }
        ai.prepareFullExtraction("WALK", "2026-07-31", "19:00", "중앙공원");

        service().createDraft(USER_1, roomId);

        assertThat(ai.lastCommand().messages()).hasSize(30);
        // 오래된 m1~m5 가 빠지고 m6 이 가장 앞이어야 한다.
        assertThat(ai.lastCommand().messages().get(0).content()).isEqualTo("m6");
        assertThat(ai.lastCommand().messages().get(29).content()).isEqualTo("m35");
    }

    @Test
    @DisplayName("room_id 는 접두사 없는 10진수 문자열, reference_date 는 서울 날짜다")
    void commandCarriesPlainRoomIdAndSeoulDate() {
        insertTextMessages(2);
        ai.prepareEmptyArray();

        service().createDraft(USER_1, roomId);

        assertThat(ai.lastCommand().roomId()).isEqualTo(String.valueOf(roomId));
        // NOW 는 UTC 로 07-29, 서울로 07-30 이다.
        assertThat(ai.lastCommand().referenceDate().toString()).isEqualTo("2026-07-30");
    }

    @Test
    @DisplayName("sender 는 Pet id 문자열이고 sentAt 은 +09:00 을 포함한다")
    void commandCarriesPetIdSenderAndSeoulOffset() {
        insertTextMessage(PET_1, "안녕", NOW.minusSeconds(600));
        insertTextMessage(PET_2, "반가워", NOW.minusSeconds(300));
        ai.prepareEmptyArray();

        service().createDraft(USER_1, roomId);

        assertThat(ai.lastCommand().messages().get(0).sender()).isEqualTo(String.valueOf(PET_1));
        assertThat(ai.lastCommand().messages().get(0).sentAt()).contains("+09:00");
    }

    // ── 접근 제어 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Active Pet 이 없으면 AI 를 부르기 전에 막힌다")
    void requiresActivePetBeforeCallingAi() {
        insertTextMessages(2);
        jdbcTemplate.update("update users set active_pet_id = null where id = ?", USER_1);

        assertThatThrownBy(() -> service().createDraft(USER_1, roomId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);

        assertThat(ai.callCount()).isZero();
        assertThat(countOf("card_drafts")).isZero();
    }

    @Test
    @DisplayName("방 참가자가 아니면 404 로 수렴하고 초안이 저장되지 않는다")
    void nonParticipantIsHiddenAsNotFound() {
        insertTextMessages(2);
        insertUser(3L);
        insertPet(33L, 3L);
        setActivePet(3L, 33L);

        assertThatThrownBy(() -> service().createDraft(3L, roomId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        assertThat(ai.callCount()).isZero();
        assertThat(countOf("card_drafts")).isZero();
    }

    // ── DB 제약 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DB 가 계약 밖 fallback_reason 을 거부한다")
    void databaseRejectsUnknownFallbackReason() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into card_drafts (room_id, requested_by_pet_id, fallback_reason)
                        values (?, ?, 'NO_IDEA')
                        """, roomId, PET_1))
                .hasMessageContaining("ck_card_draft_fallback_reason");
    }

    @Test
    @DisplayName("DB 가 계약 밖 card_type 을 거부하고 null 은 허용한다")
    void databaseRejectsUnknownDraftTypeButAllowsNull() {
        jdbcTemplate.update("""
                insert into card_drafts (room_id, requested_by_pet_id, card_type)
                values (?, ?, null)
                """, roomId, PET_1);
        assertThat(countOf("card_drafts")).isEqualTo(1);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into card_drafts (room_id, requested_by_pet_id, card_type)
                        values (?, ?, 'DINNER')
                        """, roomId, PET_1))
                .hasMessageContaining("ck_card_draft_type");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void insertTextMessages(int count) {
        for (int i = 0; i < count; i++) {
            insertTextMessage(i % 2 == 0 ? PET_1 : PET_2, "메시지" + i,
                    NOW.minusSeconds(600L - i));
        }
    }

    private void insertRecentTextMessages(int count) {
        Instant recent = Instant.now().minusSeconds(60);
        for (int i = 0; i < count; i++) {
            insertTextMessage(i % 2 == 0 ? PET_1 : PET_2, "최근 메시지" + i,
                    recent.plusSeconds(i));
        }
    }

    private void whenProductionAiReturns(List<AiDraftResult.Candidate> candidates) {
        org.mockito.Mockito.when(productionAiClient.extract(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(AiDraftResult.success(candidates));
    }

    private AiDraftResult.Candidate candidate(
            String cardType,
            String date,
            String time,
            String place
    ) {
        return new AiDraftResult.Candidate(
                MeetingCardType.valueOf(cardType),
                date,
                time,
                place,
                java.time.ZonedDateTime.of(
                                java.time.LocalDate.parse(date),
                                java.time.LocalTime.parse(time),
                                SEOUL)
                        .toInstant());
    }

    private void insertTextMessage(long senderPetId, String body, Instant createdAt) {
        jdbcTemplate.update("""
                insert into chat_messages
                    (room_id, sender_type, sender_pet_id, type, body, client_message_id, created_at)
                values (?, 'PET', ?, 'TEXT', ?, ?, ?)
                """, roomId, senderPetId, body,
                "cid-" + senderPetId + "-" + createdAt.toEpochMilli(),
                java.sql.Timestamp.from(createdAt));
    }

    private void insertSystemMessage(String body, Instant createdAt) {
        jdbcTemplate.update("""
                insert into chat_messages
                    (room_id, sender_type, type, body, client_message_id, created_at)
                values (?, 'SYSTEM', 'SYSTEM', ?, ?, ?)
                """, roomId, body, "sys-" + createdAt.toEpochMilli(),
                java.sql.Timestamp.from(createdAt));
    }

    private void insertUser(long userId) {
        jdbcTemplate.update("""
                        insert into users (
                            id, email, password_hash, nickname, public_tag,
                            role, account_status, neighborhood_code
                        ) values (?, ?, 'encoded', ?, ?, 'USER', 'ACTIVE', ?)
                        """,
                userId, "user" + userId + "@test.com", "사용자" + userId,
                "user" + userId + "#" + String.format("%04d", userId), NEIGHBORHOOD);
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

    private int countOf(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
