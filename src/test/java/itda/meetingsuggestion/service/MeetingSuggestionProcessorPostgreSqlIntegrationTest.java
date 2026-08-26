package itda.meetingsuggestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.meetingcard.ai.AiDraftCommand;
import itda.meetingcard.ai.AiDraftResult;
import itda.meetingcard.ai.MeetingDraftAiClient;
import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingsuggestion.MeetingSuggestionProperties;
import itda.meetingsuggestion.service.MeetingSuggestionProcessor.Outcome;
import itda.meetingsuggestion.service.MeetingSuggestionScanClaimService.ClaimedScan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * claim → TEXT 선별 → AI 호출 → 후보 저장 → 상태 확정 전체 흐름을 실제 PostgreSQL 에서
 * 검증한다. AI 클라이언트만 결정적 stub 이고 나머지는 전부 실물이다.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
class MeetingSuggestionProcessorPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired private MeetingSuggestionScanClaimService claims;
    @Autowired private DirectRoomConversationQueryService conversations;
    @Autowired private MeetingSuggestionStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 2026-08-25 07:00 KST. sourceDate=08-24, referenceDate=08-25 */
    private static final Instant NOW = Instant.parse("2026-08-24T22:00:00Z");
    private static final LocalDate SOURCE_DATE = LocalDate.of(2026, 8, 24);
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 8, 25);
    private static final Instant WINDOW_START = Instant.parse("2026-08-23T15:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-24T15:00:00Z");
    private static final String NEIGHBORHOOD = "4113111500";

    private StubAi ai;
    private long roomId;
    private long messageSeq;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                truncate meeting_suggestions, meeting_suggestion_scans,
                         meeting_participants, meeting_cards, card_drafts,
                         chat_messages, chat_room_participants, chat_rooms,
                         user_blocks, pets, users, media, setlogs, neighborhoods
                restart identity cascade
                """);
        jdbc.update("insert into neighborhoods (code, sido_name) values (?, '서울특별시')", NEIGHBORHOOD);
        insertUser(1L);
        insertUser(2L);
        insertPet(11L, 1L);
        insertPet(22L, 2L);
        roomId = directRoom(11L, 22L);
        participant(roomId, 11L);
        participant(roomId, 22L);
        ai = new StubAi();
        messageSeq = 0;
    }

    private MeetingSuggestionProcessor processor() {
        return new MeetingSuggestionProcessor(
                ai, claims, conversations, store,
                properties(3), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MeetingSuggestionProcessor processor(MeetingSuggestionStore storeOverride) {
        return new MeetingSuggestionProcessor(
                ai, claims, conversations, storeOverride,
                properties(3), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MeetingSuggestionProperties properties(int maxAttempts) {
        return new MeetingSuggestionProperties(
                true, SEOUL, "0 0 7 * * *", 60000, 10,
                Duration.ofMinutes(1), maxAttempts,
                Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    private ClaimedScan claimFreshScan(LocalDate sourceDate, LocalDate referenceDate) {
        claims.createScans(sourceDate, referenceDate);
        return claims.claim(1, Duration.ofMinutes(1)).getFirst();
    }

    // ── 전체 흐름 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("양쪽 TEXT 대화의 AI 후보가 Suggestion 으로 저장되고 Scan 은 COMPLETED")
    void fullFlowSavesSuggestionsAndCompletes() {
        text(11L, "내일 저녁 7시 중앙공원 산책 어때?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아! 그럼 8시쯤 보자", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"),
                candidate("PLAY", "2026-08-27", "10:00", "댕댕카페"))));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        Outcome outcome = processor().processOne(scan);

        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        assertThat(scanStatus(scan.id())).isEqualTo("COMPLETED");
        assertThat(countSuggestions()).isEqualTo(2);
        assertThat(ai.commands()).hasSize(1);
        assertThat(ai.commands().getFirst().roomId()).isEqualTo(String.valueOf(roomId));
        assertThat(ai.commands().getFirst().referenceDate()).isEqualTo(REFERENCE_DATE);
        assertThat(ai.commands().getFirst().messages())
                .extracting(AiDraftCommand.AiMessage::content)
                .containsExactly("내일 저녁 7시 중앙공원 산책 어때?", "좋아! 그럼 8시쯤 보자");
    }

    @Test
    @DisplayName("AI 호출 중에는 DB 트랜잭션이 열려 있지 않다")
    void aiCallRunsOutsideTransaction() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.empty());
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);

        assertThat(ai.transactionsActiveDuringCall()).containsExactly(false);
    }

    // ── 대상 선별 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Scan 생성 후 Block 된 방은 AI 미호출 COMPLETED")
    void blockAfterScanCreationCompletesWithoutAiCall() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);
        blockBetween(1L, 2L);

        Outcome outcome = processor().processOne(scan);

        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        assertThat(ai.commands()).isEmpty();
        assertThat(countSuggestions()).isZero();
    }

    @Test
    @DisplayName("한쪽 Pet 만 TEXT 면 AI 미호출 COMPLETED")
    void oneSidedTextCompletesWithoutAiCall() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);

        assertThat(ai.commands()).isEmpty();
        assertThat(countSuggestions()).isZero();
    }

    // ── TEXT 선별 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TEXT 만 남기고 최신 30건을 시간 ASC 로 AI 에 넘긴다")
    void textFilteringLatestThirtyAndAscendingOrder() {
        long cardId = meetingCard(roomId, "OPEN", WINDOW_START.plusSeconds(1000));
        long setlogId = setlog(11L);
        for (int i = 1; i <= 35; i++) {
            text(i % 2 == 0 ? 11L : 22L, "t" + i, WINDOW_START.plusSeconds(i * 10L));
        }
        card(11L, cardId, WINDOW_START.plusSeconds(5));
        system("입장했습니다", WINDOW_START.plusSeconds(6));
        image(22L, WINDOW_START.plusSeconds(7));
        video(11L, WINDOW_START.plusSeconds(8));
        setlogShare(22L, setlogId, WINDOW_START.plusSeconds(9));
        ai.returns(AiDraftResult.empty());
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);

        List<String> contents = ai.commands().getFirst().messages().stream()
                .map(AiDraftCommand.AiMessage::content)
                .toList();
        assertThat(contents).hasSize(30);
        assertThat(contents.getFirst()).isEqualTo("t6");
        assertThat(contents.getLast()).isEqualTo("t35");
        assertThat(contents).allMatch(content -> content.startsWith("t"));
    }

    @Test
    @DisplayName("분석 창 경계는 [start, end) 다")
    void windowBoundariesAreStartInclusiveEndExclusive() {
        text(22L, "경계 밖 메시지", WINDOW_END);
        text(11L, "시작 경계 메시지", WINDOW_START);
        text(22L, "창 안 메시지", WINDOW_START.plusSeconds(60));
        ai.returns(AiDraftResult.empty());
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);

        List<String> contents = ai.commands().getFirst().messages().stream()
                .map(AiDraftCommand.AiMessage::content)
                .toList();
        assertThat(contents).containsExactly("시작 경계 메시지", "창 안 메시지");
    }

    // ── AI 후보 처리 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("null/빈 결과는 정상 COMPLETED")
    void emptyResultCompletes() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.empty());
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isZero();
    }

    @Test
    @DisplayName("date/time 미완성 후보는 저장하지 않는다 (오류 아님)")
    void incompleteCandidatesAreSkipped() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", null, "19:00", "중앙공원"),
                candidate("WALK", "2026-08-26", null, "중앙공원"))));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isZero();
    }

    @Test
    @DisplayName("완성 후보와 미완성 후보가 섞이면 완성 후보만 저장한다")
    void onlyCompleteCandidatesAreSaved() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"),
                candidate("PLAY", "2026-08-27", null, "댕댕카페"))));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select meet_date || ' ' || meet_time from meeting_suggestions", String.class))
                .isEqualTo("2026-08-26 19:00");
    }

    // ── 기존 MeetingCard 중복 ─────────────────────────────────────────────────

    @Test
    @DisplayName("같은 방 OPEN + 같은 KST 날짜 + 60분 이내면 중복으로 저장하지 않는다")
    void openCardWithinToleranceIsDuplicate() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"))));
        // 후보 2026-08-26T19:00 KST 의 59분 전
        meetingCard(roomId, "OPEN", Instant.parse("2026-08-26T09:01:00Z"));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isZero();
    }

    @Test
    @DisplayName("60분 경계는 중복이다")
    void exactlySixtyMinutesIsDuplicate() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"))));
        meetingCard(roomId, "OPEN", Instant.parse("2026-08-26T09:00:00Z"));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isZero();
    }

    @Test
    @DisplayName("60분을 넘으면 저장한다")
    void overSixtyMinutesIsSaved() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"))));
        meetingCard(roomId, "OPEN", Instant.parse("2026-08-26T08:59:00Z"));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 방의 OPEN 카드는 중복이 아니다")
    void openCardInAnotherRoomIsNotDuplicate() {
        long otherRoom = directRoom(33L, 44L);
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"))));
        meetingCard(otherRoom, "OPEN", Instant.parse("2026-08-26T10:00:00Z"));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isEqualTo(1);
    }

    @Test
    @DisplayName("시간 차이가 60분 이내여도 KST 날짜가 다르면 중복이 아니다")
    void differentKstDateIsNotDuplicate() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "00:30", "중앙공원"))));
        // 2026-08-25T23:50 KST = 40분 차이지만 KST 날짜가 다르다.
        meetingCard(roomId, "OPEN", Instant.parse("2026-08-25T14:50:00Z"));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isEqualTo(1);
    }

    @Test
    @DisplayName("CANCELED 카드는 중복 판정에서 제외한다")
    void canceledCardIsNotDuplicate() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"))));
        meetingCard(roomId, "CANCELED", Instant.parse("2026-08-26T09:30:00Z"));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isEqualTo(1);
    }

    // ── Suggestion 멱등성 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 후보가 같은 응답에 중복 반환돼도 한 건만 저장한다")
    void duplicateCandidateInOneResponseSavesOnce() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"),
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"))));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isEqualTo(1);
    }

    @Test
    @DisplayName("retry 재응답에서 순서가 바뀌어도 같은 의미 후보는 다시 저장되지 않는다")
    void retryWithReorderedCandidatesDoesNotDuplicate() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        List<AiDraftResult.Candidate> first = List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"),
                candidate("PLAY", "2026-08-27", "10:00", "댕댕카페"));
        ai.returns(AiDraftResult.success(first));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);
        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isEqualTo(2);

        // 같은 Scan 이 재처리되고 응답 순서가 뒤집힌 상황을 재현한다.
        jdbc.update("""
                update meeting_suggestion_scans
                   set status = 'FAILED_RETRYABLE', completed_at = NULL,
                       next_retry_at = now() - interval '1 second'
                 where id = ?
                """, scan.id());
        ai.returns(AiDraftResult.success(List.of(first.get(1), first.get(0))));
        assertThat(processor().processOne(claimAgain())).isEqualTo(Outcome.COMPLETED);

        assertThat(countSuggestions()).isEqualTo(2);
    }

    // ── 실패 분류 / 재시도 ────────────────────────────────────────────────────

    @Test
    @DisplayName("TIMEOUT 은 FAILED_RETRYABLE 로 예약되고 재시도에서 COMPLETED 된다")
    void timeoutRetriesAndCompletes() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.fallback(CardDraftFallbackReason.TIMEOUT));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(scanStatus(scan.id())).isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject(
                "select last_error from meeting_suggestion_scans where id = ?",
                String.class, scan.id())).contains("TIMEOUT");

        ai.returns(AiDraftResult.empty());
        assertThat(processor().processOne(claimAgain())).isEqualTo(Outcome.COMPLETED);
        assertThat(scanStatus(scan.id())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("MODEL_ERROR(502/연결실패) 도 FAILED_RETRYABLE 이다")
    void modelErrorIsRetryable() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.fallback(CardDraftFallbackReason.MODEL_ERROR));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(scanStatus(scan.id())).isEqualTo("FAILED_RETRYABLE");
    }

    @Test
    @DisplayName("INVALID_REQUEST(422) 는 FAILED_FINAL 이다")
    void invalidRequestIsFinal() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.fallback(CardDraftFallbackReason.INVALID_REQUEST));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.FAILED_FINAL);
        assertThat(scanStatus(scan.id())).isEqualTo("FAILED_FINAL");
        assertThat(jdbc.queryForObject(
                "select last_error from meeting_suggestion_scans where id = ?",
                String.class, scan.id())).contains("422");
    }

    @Test
    @DisplayName("maxAttempts 를 채운 재시도 가능 실패는 FAILED_FINAL 로 종결한다")
    void maxAttemptsExceededIsFinal() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.fallback(CardDraftFallbackReason.TIMEOUT));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.RETRY_SCHEDULED);   // attempts=1
        assertThat(processor().processOne(claimAgain())).isEqualTo(Outcome.RETRY_SCHEDULED); // 2
        assertThat(processor().processOne(claimAgain())).isEqualTo(Outcome.FAILED_FINAL);    // 3 = maxAttempts

        assertThat(scanStatus(scan.id())).isEqualTo("FAILED_FINAL");
        assertThat(claims.claim(1, Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    @DisplayName("retry 는 Scan 에 저장된 referenceDate 를 재계산하지 않는다")
    void retryKeepsStoredReferenceDate() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.fallback(CardDraftFallbackReason.TIMEOUT));
        // 실행일(08-25)과 다른 referenceDate(08-21) 가 저장된 Scan
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, LocalDate.of(2026, 8, 21));
        assertThat(processor().processOne(scan)).isEqualTo(Outcome.RETRY_SCHEDULED);

        ai.returns(AiDraftResult.empty());
        assertThat(processor().processOne(claimAgain())).isEqualTo(Outcome.COMPLETED);

        assertThat(ai.commands()).hasSize(2);
        assertThat(ai.commands().getFirst().referenceDate()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(ai.commands().getLast().referenceDate()).isEqualTo(LocalDate.of(2026, 8, 21));
    }

    @Test
    @DisplayName("Suggestion 저장 실패 시 Scan 은 PROCESSING 으로 남고 lease 재선점 후 완료된다")
    void suggestionSaveFailureKeepsScanRecoverable() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"))));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        MeetingSuggestionStore failingStore = new MeetingSuggestionStore(jdbc, claims) {
            @Override
            public MeetingSuggestionStore.SaveResult saveCandidatesAndComplete(
                    ClaimedScan scan, List<AiDraftResult.Candidate> candidates, ZoneId zone) {
                throw new RuntimeException("suggestion save failed");
            }
        };
        try {
            processor(failingStore).processOne(scan);
        } catch (RuntimeException expected) {
            // Scan 은 PROCESSING 으로 남아 있어야 한다.
        }

        assertThat(scanStatus(scan.id())).isEqualTo("PROCESSING");
        assertThat(countSuggestions()).isZero();

        // lease 를 만료시켜 새 holder 가 재선점하면 정상 완료된다.
        jdbc.update("update meeting_suggestion_scans set claimed_at = now() - interval '2 minutes' where id = ?",
                scan.id());
        ClaimedScan recovered = claims.claim(1, Duration.ofMinutes(1)).getFirst();
        assertThat(processor().processOne(recovered)).isEqualTo(Outcome.COMPLETED);

        assertThat(scanStatus(scan.id())).isEqualTo("COMPLETED");
        assertThat(countSuggestions()).isEqualTo(1);
    }

    @Test
    @DisplayName("COMPLETED 전이가 실패하면 Suggestion 도 rollback 되어 원자성을 DB 로 증명한다")
    void completionFailureRollsBackSuggestionsAtomically() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"))));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        // 저장 후 COMPLETED 전이가 강제로 실패하는 claims 로 Store 를 구성한다.
        MeetingSuggestionScanClaimService failingClaims = new MeetingSuggestionScanClaimService(
                jdbc, Clock.systemUTC()) {
            @Override
            public boolean markCompleted(ClaimedScan scan) {
                throw new RuntimeException("completion forced failure");
            }
        };
        MeetingSuggestionStore storeWithFailingCompletion = new MeetingSuggestionStore(jdbc, failingClaims);
        List<AiDraftResult.Candidate> candidates = List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"));
        // Store 가 Spring proxy 가 아니므로 트랜잭션 경계를 명시적으로 연다.
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                storeWithFailingCompletion.saveCandidatesAndComplete(scan, candidates, SEOUL)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("completion forced failure");

        // 원자성 증명: Suggestion 이 남지 않고 Scan 은 PROCESSING 을 유지한다.
        assertThat(countSuggestions()).isZero();
        assertThat(scanStatus(scan.id())).isEqualTo("PROCESSING");

        // 재선점 후 정상 완료될 수 있어야 한다.
        jdbc.update("update meeting_suggestion_scans set claimed_at = now() - interval '2 minutes' where id = ?",
                scan.id());
        ClaimedScan recovered = claims.claim(1, Duration.ofMinutes(1)).getFirst();
        assertThat(processor().processOne(recovered)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isEqualTo(1);
        assertThat(scanStatus(scan.id())).isEqualTo("COMPLETED");
    }

    // ── stale worker fencing ──────────────────────────────────────────────────

    @Test
    @DisplayName("lease 를 잃은 worker 는 늦은 AI 응답을 저장하지 못하고 FENCED 다")
    void staleWorkerCannotSaveSuggestions() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        ClaimedScan holderA = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        // A 가 AI 응답을 기다리는 동안 lease 만료 → B 가 token B 로 재claim
        jdbc.update("update meeting_suggestion_scans set claimed_at = now() - interval '2 minutes' where id = ?",
                holderA.id());
        ClaimedScan holderB = claims.claim(1, Duration.ofMinutes(1)).getFirst();
        assertThat(holderB.claimToken()).isNotEqualTo(holderA.claimToken());

        // B 가 후보 B 를 저장하고 완료
        ai.returns(AiDraftResult.success(List.of(
                candidate("WALK", "2026-08-26", "19:00", "중앙공원"))));
        assertThat(processor().processOne(holderB)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isEqualTo(1);

        // A 가 늦게 도착한 후보 A 저장을 시도 → FENCED, 후보 A 는 DB 에 없다
        ai.returns(AiDraftResult.success(List.of(
                candidate("PLAY", "2026-08-27", "10:00", "댕댕카페"))));
        assertThat(processor().processOne(holderA)).isEqualTo(Outcome.FENCED);

        assertThat(countSuggestions()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select meet_date from meeting_suggestions", String.class)).isEqualTo("2026-08-26");
        assertThat(scanStatus(holderA.id())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("date/time 은 nonblank 여도 combinedInstant 가 null 이면 저장하지 않는다")
    void unparseableCandidateIsNotSaved() {
        text(11L, "산책할까요?", WINDOW_START.plusSeconds(60));
        text(22L, "좋아요", WINDOW_START.plusSeconds(120));
        // 시간만 파싱 불가 → mapper 의 combinedInstant 가 null. 같은 시각 OPEN 카드가
        // 있어도 이 후보는 저장 경로(중복 조회 포함)에 아예 들어가지 않아야 한다.
        meetingCard(roomId, "OPEN", Instant.parse("2026-08-26T10:00:00Z"));
        ai.returns(AiDraftResult.success(List.of(
                new AiDraftResult.Candidate(MeetingCardType.WALK, "2026-08-26", "19시", "중앙공원",
                        null))));
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);
        assertThat(countSuggestions()).isZero();
    }

    // ── created_at 기준 정렬 ─────────────────────────────────────────────────

    @Test
    @DisplayName("최신 30개 선별과 AI 전달 ASC 는 created_at 기준이다 (id 역순 삽입)")
    void latestMessagesAreSelectedByCreatedAt() {
        // id 순서와 created_at 순서가 의도적으로 다르다:
        // 먼저 삽입(id 작음)된 메시지가 더 늦게 작성됐다.
        text(22L, "나중에작성", WINDOW_START.plusSeconds(3600));
        text(11L, "먼저작성", WINDOW_START.plusSeconds(60));
        ai.returns(AiDraftResult.empty());
        ClaimedScan scan = claimFreshScan(SOURCE_DATE, REFERENCE_DATE);

        assertThat(processor().processOne(scan)).isEqualTo(Outcome.COMPLETED);

        List<String> contents = ai.commands().getFirst().messages().stream()
                .map(AiDraftCommand.AiMessage::content)
                .toList();
        assertThat(contents).containsExactly("먼저작성", "나중에작성");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ClaimedScan claimAgain() {
        // processor 의 fixed clock 은 과거라 next_retry_at 이 항상 due 이다.
        return claims.claim(1, Duration.ofMinutes(1)).getFirst();
    }

    private String scanStatus(long scanId) {
        return jdbc.queryForObject(
                "select status from meeting_suggestion_scans where id = ?", String.class, scanId);
    }

    private long countSuggestions() {
        return jdbc.queryForObject("select count(*) from meeting_suggestions", Long.class);
    }

    private AiDraftResult.Candidate candidate(String type, String date, String time, String place) {
        return new AiDraftResult.Candidate(
                MeetingCardType.valueOf(type), date, time, place, combine(date, time));
    }

    /** AI mapper 와 같은 조합 규칙: date/time 파싱 가능하면 combinedInstant 를 채운다. */
    private static Instant combine(String date, String time) {
        try {
            return ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), SEOUL).toInstant();
        } catch (RuntimeException unparseable) {
            return null;
        }
    }

    private void text(long senderPetId, String body, Instant createdAt) {
        jdbc.update("""
                insert into chat_messages
                    (room_id, sender_type, sender_pet_id, type, body, client_message_id, created_at)
                values (?, 'PET', ?, 'TEXT', ?, ?, ?)
                """, roomId, senderPetId, body, clientId(), java.sql.Timestamp.from(createdAt));
    }

    private void card(long senderPetId, long meetingCardId, Instant createdAt) {
        jdbc.update("""
                insert into chat_messages
                    (room_id, sender_type, sender_pet_id, type, meeting_card_id, client_message_id, created_at)
                values (?, 'PET', ?, 'CARD', ?, ?, ?)
                """, roomId, senderPetId, meetingCardId, clientId(), java.sql.Timestamp.from(createdAt));
    }

    private void system(String body, Instant createdAt) {
        jdbc.update("""
                insert into chat_messages
                    (room_id, sender_type, type, body, client_message_id, created_at)
                values (?, 'SYSTEM', 'SYSTEM', ?, ?, ?)
                """, roomId, body, clientId(), java.sql.Timestamp.from(createdAt));
    }

    private void image(long senderPetId, Instant createdAt) {
        jdbc.update("""
                insert into chat_messages
                    (room_id, sender_type, sender_pet_id, type, client_message_id, created_at)
                values (?, 'PET', ?, 'IMAGE', ?, ?)
                """, roomId, senderPetId, clientId(), java.sql.Timestamp.from(createdAt));
    }

    private void video(long senderPetId, Instant createdAt) {
        jdbc.update("""
                insert into chat_messages
                    (room_id, sender_type, sender_pet_id, type, client_message_id, created_at)
                values (?, 'PET', ?, 'VIDEO', ?, ?)
                """, roomId, senderPetId, clientId(), java.sql.Timestamp.from(createdAt));
    }

    private void setlogShare(long senderPetId, long setlogId, Instant createdAt) {
        jdbc.update("""
                insert into chat_messages
                    (room_id, sender_type, sender_pet_id, type, shared_setlog_id, client_message_id, created_at)
                values (?, 'PET', ?, 'SETLOG_SHARE', ?, ?, ?)
                """, roomId, senderPetId, setlogId, clientId(), java.sql.Timestamp.from(createdAt));
    }

    private String clientId() {
        messageSeq++;
        return "cid-" + messageSeq;
    }

    /** OPEN/CANCELED meeting card 를 만든다. id 를 돌려준다. */
    private long meetingCard(long cardRoomId, String status, Instant meetAt) {
        jdbc.update("""
                insert into meeting_cards
                    (room_id, creator_pet_id, card_type, place_text, meet_at, status,
                     canceled_by_pet_id, canceled_at)
                values (?, 11, 'WALK', '중앙공원', ?, ?,
                        CASE WHEN ? = 'CANCELED' THEN 11 ELSE NULL END,
                        CASE WHEN ? = 'CANCELED' THEN now() ELSE NULL END)
                """, cardRoomId, java.sql.Timestamp.from(meetAt), status, status, status);
        return jdbc.queryForObject("select max(id) from meeting_cards", Long.class);
    }

    private long setlog(long authorPetId) {
        jdbc.update("""
                insert into media (media_type, path, status, user_id)
                values ('IMAGE', ?, 'COMPLETED', ?)
                """, "path-" + UUID.randomUUID(), 1L);
        long mediaId = jdbc.queryForObject("select max(id) from media", Long.class);
        jdbc.update("""
                insert into setlogs (author_pet_id, media_id, caption, status)
                values (?, ?, '산책 기록', 'VISIBLE')
                """, authorPetId, mediaId);
        return jdbc.queryForObject("select max(id) from setlogs", Long.class);
    }

    private long directRoom(long petLowId, long petHighId) {
        jdbc.update("""
                insert into chat_rooms (type, status, origin, pet_low_id, pet_high_id)
                values ('DIRECT', 'ACTIVE', 'GREETING', ?, ?)
                """, petLowId, petHighId);
        return jdbc.queryForObject("select max(id) from chat_rooms", Long.class);
    }

    private void participant(long roomId, long petId) {
        jdbc.update("""
                insert into chat_room_participants (room_id, pet_id, joined_at)
                values (?, ?, now())
                """, roomId, petId);
    }

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

    /** 결정적 AI stub. commands 를 관측하고 호출 시점의 TX 상태를 기록한다. */
    static class StubAi implements MeetingDraftAiClient {
        private final List<AiDraftCommand> commands = new ArrayList<>();
        private final List<Boolean> transactionsActiveDuringCall = new ArrayList<>();
        private AiDraftResult result = AiDraftResult.empty();

        StubAi returns(AiDraftResult result) {
            this.result = result;
            return this;
        }

        @Override
        public AiDraftResult extract(AiDraftCommand command) {
            commands.add(command);
            transactionsActiveDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            return result;
        }

        List<AiDraftCommand> commands() {
            return commands;
        }

        List<Boolean> transactionsActiveDuringCall() {
            return transactionsActiveDuringCall;
        }
    }
}
