package itda.meetingsuggestion.service;

import itda.meetingcard.ai.AiDraftCommand;
import itda.meetingcard.ai.AiDraftResult;
import itda.meetingcard.ai.MeetingDraftAiClient;
import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingsuggestion.MeetingSuggestionProperties;
import itda.meetingsuggestion.service.MeetingSuggestionScanClaimService.ClaimedScan;
import itda.meetingsuggestion.service.DirectRoomConversationQueryService.TextMessageRow;
import itda.meetingsuggestion.support.SourceDateWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * claim 된 Scan 한 건의 처리.
 *
 * <p>AI HTTP 호출은 DB 트랜잭션 밖에서 수행한다. 처리 경계는:
 *
 * <ol>
 *   <li>짧은 TX — Scan claim / PROCESSING ({@link MeetingSuggestionScanClaimService#claim})</li>
 *   <li>TX 없음 — 대상 재확인, TEXT 조회, AI HTTP 호출</li>
 *   <li>짧은 TX — Suggestion 저장 + Scan {@code COMPLETED} 확정(원자적,
 *       {@link MeetingSuggestionStore#saveCandidatesAndComplete})</li>
 * </ol>
 *
 * <p>AI 응답을 기다리는 동안 row lock 이나 트랜잭션을 잡지 않는다. 이 보호는
 * {@link Propagation#NEVER} 가 강제한다. AI fallback(TIMEOUT/MODEL_ERROR/INVALID_REQUEST)
 * 의 retry/final 전이는 Suggestion 이 없으므로 기존 짧은 트랜잭션
 * ({@code markRetryable}/{@code markFinal})을 유지한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MeetingSuggestionProcessor {

    /** AI 메시지 sentAt 은 KST 오프셋이 포함된 ISO-8601 문자열로 보낸다. */
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final MeetingDraftAiClient aiClient;
    private final MeetingSuggestionScanClaimService claims;
    private final DirectRoomConversationQueryService conversations;
    private final MeetingSuggestionStore store;
    private final MeetingSuggestionProperties properties;
    private final Clock clock;

    /**
     * Scan 한 건을 처리하고 상태를 확정한다. 예외는 DB 장애 등 예기치 못한 경우에만
     * 새며, 그때는 Scan 이 PROCESSING 으로 남아 lease 만료 후 다시 claim 된다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public Outcome processOne(ClaimedScan scan) {
        SourceDateWindow window = SourceDateWindow.of(
                scan.sourceDate(), scan.referenceDate(), properties.zone());

        // Scan 생성(07:00) 이후 Block 되거나 나간 방은 AI 를 부르지 않는다.
        if (!conversations.isEligible(scan.roomId())) {
            return completeOrFenced(scan);
        }

        // 양쪽 Pet 이 각각 TEXT 1건 이상이어야 한다. 한쪽만 말했으면 AI 미호출.
        Set<Long> senders = conversations.textSenderPetIds(
                scan.roomId(), window.windowStart(), window.windowEnd());
        if (!senders.contains(scan.petLowId()) || !senders.contains(scan.petHighId())) {
            return completeOrFenced(scan);
        }

        AiDraftResult result = aiClient.extract(toCommand(scan, window));

        if (result.fallbackReason() != null) {
            return handleFallback(scan, result.fallbackReason());
        }

        // 저장 조건은 raw 문자열 nonblank 만이 아니라 기존 AI mapper 의 canonical 결과다.
        // date/time 을 조합하지 못한(combinedInstant null) 후보는 저장하지 않는다.
        List<AiDraftResult.Candidate> storable = result.candidates().stream()
                .filter(candidate -> !isBlank(candidate.date())
                        && !isBlank(candidate.time())
                        && candidate.combinedInstant() != null)
                .toList();
        // Suggestion 저장과 Scan COMPLETED 확정을 한 짧은 트랜잭션으로 원자화한다.
        // 소유권(claimToken)이 없으면 한 건도 저장하지 않고 상태도 바꾸지 않는다.
        MeetingSuggestionStore.SaveResult saveResult =
                store.saveCandidatesAndComplete(scan, storable, properties.zone());
        if (saveResult.fenced()) {
            // lease 를 잃어 소유권이 없으면 후보가 저장되지 않았다. 상태를 확정하지
            // 않고 FENCED 로 종료한다. 새 holder 의 결과를 덮어쓰면 안 된다.
            log.warn("Meeting suggestion save fenced, ownership lost: scanId={}, attempts={}",
                    scan.id(), scan.attempts());
            return Outcome.FENCED;
        }
        return Outcome.COMPLETED;
    }

    private Outcome handleFallback(ClaimedScan scan, CardDraftFallbackReason reason) {
        // 422 는 요청 자체가 거절된 것이므로 재시도해도 같다 → FAILED_FINAL.
        if (reason == CardDraftFallbackReason.INVALID_REQUEST) {
            return claims.markFinal(scan, "AI rejected request (422)")
                    ? Outcome.FAILED_FINAL : Outcome.FENCED;
        }
        // TIMEOUT(504/timeout) · MODEL_ERROR(502/연결실패/깨진 응답) → FAILED_RETRYABLE.
        if (scan.attempts() >= properties.maxAttempts()) {
            return claims.markFinal(scan, "max attempts exceeded: " + reason)
                    ? Outcome.FAILED_FINAL : Outcome.FENCED;
        }
        Instant nextRetryAt = clock.instant().plus(backoff(scan));
        return claims.markRetryable(scan, nextRetryAt, "ai fallback: " + reason)
                ? Outcome.RETRY_SCHEDULED : Outcome.FENCED;
    }

    private Outcome completeOrFenced(ClaimedScan scan) {
        return claims.markCompleted(scan) ? Outcome.COMPLETED : Outcome.FENCED;
    }

    /**
     * TEXT 필터 후 created_at 기준 최신순으로 받은 메시지를 created_at ASC 로 되돌려
     * AI 에 넘긴다. referenceDate 는 Scan 에 저장된 값을 그대로 쓴다(retry 재계산 금지).
     */
    private AiDraftCommand toCommand(ClaimedScan scan, SourceDateWindow window) {
        List<TextMessageRow> newestFirst = conversations.latestTextMessages(
                scan.roomId(), window.windowStart(), window.windowEnd());
        List<TextMessageRow> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);

        List<AiDraftCommand.AiMessage> messages = chronological.stream()
                .map(message -> new AiDraftCommand.AiMessage(
                        String.valueOf(message.senderPetId()),
                        message.body(),
                        ISO_OFFSET.format(message.createdAt().atZone(properties.zone()))))
                .toList();

        return new AiDraftCommand(
                String.valueOf(scan.roomId()),
                scan.referenceDate(),
                messages);
    }

    /** Risk Outbox 와 같은 지수 backoff + jitter. scanId 로 결정적이어서 재현 가능하다. */
    private Duration backoff(ClaimedScan scan) {
        long baseMillis = properties.baseBackoff().toMillis();
        long maxMillis = properties.maxBackoff().toMillis();
        long multiplier = 1L << Math.min(scan.attempts() - 1, 20);
        long exponential = baseMillis > maxMillis / multiplier
                ? maxMillis
                : baseMillis * multiplier;
        long jitterRange = Math.max(1L, exponential / 5L);
        long jitter = Math.floorMod(Long.hashCode(scan.id()), jitterRange);
        return Duration.ofMillis(Math.min(maxMillis, exponential + jitter));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Outcome {
        COMPLETED,
        RETRY_SCHEDULED,
        FAILED_FINAL,
        FENCED
    }
}
