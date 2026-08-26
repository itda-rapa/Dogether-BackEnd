package itda.meetingsuggestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.meetingsuggestion.MeetingSuggestionProperties;
import itda.meetingsuggestion.service.MeetingSuggestionProcessor.Outcome;
import itda.meetingsuggestion.service.MeetingSuggestionScanClaimService.ClaimedScan;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MeetingSuggestionRetryWorker — claim 후 처리 loop")
class MeetingSuggestionRetryWorkerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration LEASE = Duration.ofMinutes(1);

    @Mock private MeetingSuggestionScanClaimService claims;
    @Mock private MeetingSuggestionProcessor processor;

    private MeetingSuggestionRetryWorker worker() {
        return new MeetingSuggestionRetryWorker(claims, processor, properties());
    }

    private static MeetingSuggestionProperties properties() {
        return new MeetingSuggestionProperties(
                true, SEOUL, "0 0 7 * * *", 60000, 10,
                LEASE, 3, Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    private static ClaimedScan scan(int attempts) {
        return new ClaimedScan(7L, 70L, 11L, 22L,
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 25),
                attempts, UUID.randomUUID());
    }

    @Test
    @DisplayName("claim 할 Scan 이 없으면 바로 멈춘다")
    void stopsWhenNothingToClaim() {
        when(claims.claim(anyInt(), any())).thenReturn(List.of());

        MeetingSuggestionRetryWorker.Result result = worker().runOnce();

        assertThat(result).isEqualTo(new MeetingSuggestionRetryWorker.Result(0, 0, 0, 0));
        verify(processor, never()).processOne(any());
        // retry worker 는 새 Scan 을 만들지 않는다. 생성 책임은 07:00 Scheduler 다.
        verify(claims, never()).createScans(any(), any());
    }

    @Test
    @DisplayName("attempts 가 maxAttempts 를 초과한 claim 은 즉시 FAILED_FINAL 이다")
    void claimBeyondMaxAttemptsIsFinal() {
        when(claims.claim(anyInt(), any())).thenReturn(List.of(scan(4)), List.of()); // maxAttempts = 3
        when(claims.markFinal(any(), anyString())).thenReturn(true);

        MeetingSuggestionRetryWorker.Result result = worker().runOnce();

        assertThat(result).isEqualTo(new MeetingSuggestionRetryWorker.Result(0, 0, 1, 0));
        verify(processor, never()).processOne(any());
    }

    @Test
    @DisplayName("processor 결과를 그대로 집계한다")
    void aggregatesProcessorOutcomes() {
        when(claims.claim(anyInt(), any()))
                .thenReturn(List.of(scan(1)), List.of(scan(1)), List.of(scan(1)), List.of());
        when(processor.processOne(any()))
                .thenReturn(Outcome.COMPLETED, Outcome.RETRY_SCHEDULED, Outcome.FENCED);

        MeetingSuggestionRetryWorker.Result result = worker().runOnce();

        assertThat(result).isEqualTo(new MeetingSuggestionRetryWorker.Result(1, 1, 0, 1));
    }

    @Test
    @DisplayName("처리 중 예기치 못한 예외는 Scan 을 PROCESSING 으로 남기고 계속 진행한다")
    void unexpectedExceptionKeepsScanProcessable() {
        when(claims.claim(anyInt(), any()))
                .thenReturn(List.of(scan(1)), List.of(scan(1)), List.of());
        when(processor.processOne(any()))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(Outcome.COMPLETED);

        MeetingSuggestionRetryWorker.Result result = worker().runOnce();

        assertThat(result).isEqualTo(new MeetingSuggestionRetryWorker.Result(1, 0, 0, 0));
    }
}
