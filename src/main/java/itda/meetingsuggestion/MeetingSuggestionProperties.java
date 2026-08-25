package itda.meetingsuggestion;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 아침 DIRECT 대화 기반 약속 후보 제안 스케줄러 운영 설정.
 *
 * <p>기본값은 기존 Risk Outbox Relay / Storage Cleanup 패턴의 값을 따랐으며 제품 정책이
 * 아니다. 운영 환경에서는 환경 변수로 덮어쓴다.
 */
@Validated
@ConfigurationProperties("app.meeting-suggestion")
public record MeetingSuggestionProperties(
        boolean enabled,
        @NotNull ZoneId zone,
        @NotBlank String schedulerCron,
        @Min(1000) long retryDelayMs,
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration lease,
        @Min(1) @Max(100) int maxAttempts,
        @NotNull Duration baseBackoff,
        @NotNull Duration maxBackoff
) {
    /**
     * lease 는 실제 AI 호출 시간 설정({@code app.meeting-card.ai.timeout})보다 엄격히
     * 길어야 한다. 하드코딩 하한을 두지 않고 {@link MeetingSuggestionConfigurationValidator}
     * 가 실제 바인딩된 timeout 을 기준으로 앱 시작 시 검증한다. lease 가 AI 응답 대기보다
     * 짧으면 새 claim holder 가 같은 Scan 을 동시에 처리하게 된다(fingerprint 멱등성으로
     * 결과는 보호되지만 AI 호출이 중복된다).
     */
    @AssertTrue(message = "meeting suggestion durations must be positive and safely ordered")
    public boolean isDurationsValid() {
        return positiveMillis(lease)
                && positiveMillis(baseBackoff)
                && positiveMillis(maxBackoff)
                && maxBackoff.compareTo(baseBackoff) >= 0;
    }

    private static boolean positiveMillis(Duration duration) {
        return duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0;
    }
}
