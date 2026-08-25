package itda.meetingsuggestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.meetingcard.ai.AiDraftCommand;
import itda.meetingcard.ai.AiDraftResult;
import itda.meetingcard.ai.MeetingDraftAiClient;
import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingsuggestion.MeetingSuggestionProperties;
import itda.meetingsuggestion.service.DirectRoomConversationQueryService.TextMessageRow;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MeetingSuggestionProcessor — Scan 한 건 처리")
class MeetingSuggestionProcessorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-24T22:00:00Z"); // 08-25 07:00 KST
    private static final Instant WINDOW_START = Instant.parse("2026-08-23T15:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-24T15:00:00Z");

    @Mock private MeetingDraftAiClient aiClient;
    @Mock private MeetingSuggestionScanClaimService claims;
    @Mock private DirectRoomConversationQueryService conversations;
    @Mock private MeetingSuggestionStore store;

    private MeetingSuggestionProcessor processor;
    private final AtomicReference<AiDraftCommand> capturedCommand = new AtomicReference<>();

    private ClaimedScan scan() {
        return new ClaimedScan(7L, 70L, 11L, 22L,
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 25),
                1, UUID.randomUUID());
    }

    @BeforeEach
    void setUp() {
        processor = new MeetingSuggestionProcessor(
                aiClient, claims, conversations, store,
                properties(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MeetingSuggestionProperties properties() {
        return new MeetingSuggestionProperties(
                true, SEOUL, "0 0 7 * * *", 60000, 10,
                Duration.ofMinutes(1), 3,
                Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    private void eligibleRoomWithBothSidesTalking() {
        when(conversations.isEligible(70L)).thenReturn(true);
        when(conversations.textSenderPetIds(70L, WINDOW_START, WINDOW_END))
                .thenReturn(Set.of(11L, 22L));
    }

    private void aiReturns(AiDraftResult result) {
        when(aiClient.extract(any())).thenAnswer(invocation -> {
            capturedCommand.set(invocation.getArgument(0));
            return result;
        });
    }

    private void storeSaves(MeetingSuggestionStore.SaveResult result) {
        when(store.saveCandidatesAndComplete(any(), any(), any())).thenReturn(result);
    }

    // ── 대상 선별 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Scan 생성 후 Block/leftAt 으로 제외된 방은 AI 미호출 COMPLETED")
    void ineligibleRoomCompletesWithoutAiCall() {
        ClaimedScan scan = scan();
        when(conversations.isEligible(70L)).thenReturn(false);
        when(claims.markCompleted(any())).thenReturn(true);

        Outcome outcome = processor.processOne(scan);

        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        verify(aiClient, never()).extract(any());
        verify(claims).markCompleted(scan);
    }

    @Test
    @DisplayName("한쪽 Pet 만 TEXT 면 AI 미호출 COMPLETED")
    void oneSidedConversationCompletesWithoutAiCall() {
        when(conversations.isEligible(70L)).thenReturn(true);
        when(conversations.textSenderPetIds(70L, WINDOW_START, WINDOW_END))
                .thenReturn(Set.of(11L));
        when(claims.markCompleted(any())).thenReturn(true);

        Outcome outcome = processor.processOne(scan());

        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        verify(aiClient, never()).extract(any());
    }

    @Test
    @DisplayName("양쪽이 TEXT 면 AI 를 호출한다")
    void bothSidesTalkingCallsAi() {
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.empty());
        storeSaves(MeetingSuggestionStore.SaveResult.saved(0));

        Outcome outcome = processor.processOne(scan());

        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        verify(aiClient).extract(any());
        // 저장+완료는 Store 의 단일 진입점에서 원자적으로 수행된다.
        verify(store).saveCandidatesAndComplete(any(), any(), any());
        verify(claims, never()).markCompleted(any());
    }

    // ── AI command 계약 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("command 는 Scan 에 저장된 referenceDate 를 쓰고 실행일로 재계산하지 않는다")
    void commandUsesStoredReferenceDate() {
        ClaimedScan oldScan = new ClaimedScan(7L, 70L, 11L, 22L,
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 21),
                1, UUID.randomUUID());
        // sourceDate 2026-08-20 의 KST 창
        Instant oldStart = Instant.parse("2026-08-19T15:00:00Z");
        Instant oldEnd = Instant.parse("2026-08-20T15:00:00Z");
        when(conversations.isEligible(70L)).thenReturn(true);
        when(conversations.textSenderPetIds(70L, oldStart, oldEnd))
                .thenReturn(Set.of(11L, 22L));
        aiReturns(AiDraftResult.empty());
        storeSaves(MeetingSuggestionStore.SaveResult.saved(0));

        processor.processOne(oldScan);

        assertThat(capturedCommand.get().referenceDate()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(capturedCommand.get().roomId()).isEqualTo("70");
    }

    @Test
    @DisplayName("TEXT 최신순 조회 결과를 시간 ASC 로 되돌려 AI 에 넘긴다")
    void messagesArePassedInAscendingOrder() {
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.empty());
        when(conversations.latestTextMessages(70L, WINDOW_START, WINDOW_END))
                .thenReturn(List.of(
                        row(3L, 22L, "셋째", NOW.minusSeconds(10)),
                        row(2L, 11L, "둘째", NOW.minusSeconds(20)),
                        row(1L, 22L, "첫째", NOW.minusSeconds(30))));
        storeSaves(MeetingSuggestionStore.SaveResult.saved(0));

        processor.processOne(scan());

        assertThat(capturedCommand.get().messages())
                .extracting(AiDraftCommand.AiMessage::content)
                .containsExactly("첫째", "둘째", "셋째");
        assertThat(capturedCommand.get().messages().getFirst().sentAt())
                .isEqualTo("2026-08-25T06:59:30+09:00");
    }

    // ── AI 결과 분류 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("null/빈 후보 목록은 정상 COMPLETED")
    void emptyCandidatesComplete() {
        ClaimedScan scan = scan();
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.success(List.of()));
        when(store.saveCandidatesAndComplete(eq(scan), eq(List.of()), any()))
                .thenReturn(MeetingSuggestionStore.SaveResult.saved(0));

        assertThat(processor.processOne(scan)).isEqualTo(Outcome.COMPLETED);
    }

    @Test
    @DisplayName("date 나 time 이 없으면 저장하지 않고 COMPLETED")
    void incompleteCandidatesAreSkipped() {
        ClaimedScan scan = scan();
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.success(List.of(
                candidate(null, "19:00", "공원"),          // date null
                candidate("2026-08-26", null, "공원"),      // time null
                candidate("", "19:00", "공원"),             // date blank
                candidate("2026-08-26", " ", "공원"))));    // time blank
        when(store.saveCandidatesAndComplete(eq(scan), eq(List.of()), any()))
                .thenReturn(MeetingSuggestionStore.SaveResult.saved(0));

        Outcome outcome = processor.processOne(scan);

        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        verify(store).saveCandidatesAndComplete(scan, List.of(), SEOUL);
    }

    @Test
    @DisplayName("date/time 이 nonblank 여도 combinedInstant 가 null 이면 저장하지 않는다")
    void unparseableDateAndTimeCandidatesAreSkipped() {
        ClaimedScan scan = scan();
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.success(List.of(
                candidate("2026-08-26", "19시", "공원"),      // time 파싱 불가 → combined null
                candidate("다음주 금요일", "19:00", "공원")))); // date 파싱 불가 → combined null
        when(store.saveCandidatesAndComplete(eq(scan), eq(List.of()), any()))
                .thenReturn(MeetingSuggestionStore.SaveResult.saved(0));

        Outcome outcome = processor.processOne(scan);

        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        verify(store).saveCandidatesAndComplete(scan, List.of(), SEOUL);
    }

    @Test
    @DisplayName("완성된 후보 여러 건이 그대로 저장 계층에 전달된다")
    void multipleCompleteCandidatesArePassedThrough() {
        ClaimedScan scan = scan();
        eligibleRoomWithBothSidesTalking();
        List<AiDraftResult.Candidate> candidates = List.of(
                candidate("2026-08-26", "19:00", "중앙공원"),
                candidate("2026-08-27", "10:00", "댕댕카페"));
        aiReturns(AiDraftResult.success(candidates));
        when(store.saveCandidatesAndComplete(eq(scan), eq(candidates), any()))
                .thenReturn(MeetingSuggestionStore.SaveResult.saved(2));

        assertThat(processor.processOne(scan)).isEqualTo(Outcome.COMPLETED);
        verify(store).saveCandidatesAndComplete(scan, candidates, SEOUL);
    }

    @Test
    @DisplayName("TIMEOUT 은 FAILED_RETRYABLE 로 예약한다")
    void timeoutSchedulesRetry() {
        ClaimedScan scan = scan();
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.fallback(CardDraftFallbackReason.TIMEOUT));
        when(claims.markRetryable(any(), any(), any())).thenReturn(true);

        Outcome outcome = processor.processOne(scan);

        assertThat(outcome).isEqualTo(Outcome.RETRY_SCHEDULED);
        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        verify(claims).markRetryable(eq(scan), retryAt.capture(), any());
        assertThat(retryAt.getValue()).isAfter(NOW);
        assertThat(retryAt.getValue()).isBefore(NOW.plus(Duration.ofMinutes(20)));
    }

    @Test
    @DisplayName("MODEL_ERROR(502/연결실패) 도 FAILED_RETRYABLE 이다")
    void modelErrorSchedulesRetry() {
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.fallback(CardDraftFallbackReason.MODEL_ERROR));
        when(claims.markRetryable(any(), any(), any())).thenReturn(true);

        assertThat(processor.processOne(scan())).isEqualTo(Outcome.RETRY_SCHEDULED);
    }

    @Test
    @DisplayName("422(INVALID_REQUEST) 는 FAILED_FINAL 이다")
    void invalidRequestIsFinal() {
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.fallback(CardDraftFallbackReason.INVALID_REQUEST));
        when(claims.markFinal(any(), any())).thenReturn(true);

        assertThat(processor.processOne(scan())).isEqualTo(Outcome.FAILED_FINAL);
        verify(claims, never()).markRetryable(any(), any(), any());
    }

    @Test
    @DisplayName("maxAttempts 에 도달한 재시도 가능 실패는 FAILED_FINAL 이다")
    void maxAttemptsReachedIsFinal() {
        ClaimedScan exhausted = new ClaimedScan(7L, 70L, 11L, 22L,
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 25),
                3, UUID.randomUUID()); // maxAttempts = 3
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.fallback(CardDraftFallbackReason.TIMEOUT));
        when(claims.markFinal(any(), any())).thenReturn(true);

        assertThat(processor.processOne(exhausted)).isEqualTo(Outcome.FAILED_FINAL);
        verify(claims, never()).markRetryable(any(), any(), any());
    }

    // ── fencing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Store 가 fenced 를 반환하면 상태를 확정하지 않고 FENCED 로 종료한다")
    void storeFenceStopsWithoutStateTransition() {
        ClaimedScan scan = scan();
        eligibleRoomWithBothSidesTalking();
        aiReturns(AiDraftResult.success(List.of(candidate("2026-08-26", "19:00", "공원"))));
        storeSaves(MeetingSuggestionStore.SaveResult.fencedResult());

        assertThat(processor.processOne(scan)).isEqualTo(Outcome.FENCED);
        verify(claims, never()).markCompleted(any());
        verify(claims, never()).markRetryable(any(), any(), any());
        verify(claims, never()).markFinal(any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static TextMessageRow row(long id, long senderPetId, String body, Instant createdAt) {
        return new TextMessageRow(id, senderPetId, body, createdAt);
    }

    /** AI mapper 와 같은 조합 규칙: date/time 파싱 가능하면 combinedInstant 를 채운다. */
    private static AiDraftResult.Candidate candidate(String date, String time, String place) {
        return new AiDraftResult.Candidate(
                MeetingCardType.WALK, date, time, place, combine(date, time));
    }

    private static Instant combine(String date, String time) {
        try {
            return ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), SEOUL).toInstant();
        } catch (RuntimeException unparseable) {
            return null;
        }
    }
}
