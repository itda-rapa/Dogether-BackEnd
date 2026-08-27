package itda.meetingverification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * GPS 만남 확정의 Meeting 정책 설정.
 *
 * <p>Location 영역({@code app.location})은 좌표 형식·freshness·accuracy 품질만 판정하고,
 * 거리 한도·양쪽 제출 간격·약속 시간창·멱등 fingerprint secret 은 Meeting 정책이므로
 * 이 설정으로 관리한다. {@link itda.meetingverification.service.MeetingVerificationService} 가
 * 두 위치의 거리·양쪽 서버 수신시각 간격·약속 시각 대비 GPS capturedAt 시간창을 판정하고,
 * {@code hmacSecret} 으로 request ledger fingerprint 를 만든다.
 *
 * <p>{@code expiry} 는 SUBMITTED 만료 worker 설정으로, {@code enabled} 기본값은 {@code true}
 * (raw GPS retention 안전값)이고 {@code false} 는 명시적 opt-out 이다. {@code batchSize}/
 * {@code delay} 는 worker 실행 전에 application context startup 단계에서 검증되므로,
 * 잘못된 설정으로 앱이 부팅된 뒤 scheduler 가 반복 실패하지 않는다.
 */
@Validated
@ConfigurationProperties("app.meeting-verification")
public record MeetingVerificationProperties(
        @Positive @DefaultValue("100") double distanceLimitMeters,
        @NotNull @DefaultValue("5m") Duration submissionInterval,
        @NotNull @DefaultValue("1h") Duration meetingTimeWindow,
        @NotBlank String hmacSecret,
        @NotNull @Valid @DefaultValue Expiry expiry
) {

    /** 운영에서 그대로 사용하면 startup fail-fast 로 거절하는 공개 example placeholder. */
    private static final String EXAMPLE_HMAC_SECRET =
            "replace-with-a-random-meeting-verification-hmac-secret-at-least-32-bytes";

    /** SUBMITTED 만료 worker 설정({@code app.meeting-verification.expiry}). */
    public record Expiry(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("60s") Duration delay,
            @Positive @DefaultValue("50") int batchSize
    ) {
        @AssertTrue(message = "meeting verification expiry delay must be positive")
        public boolean isDelayValid() {
            return delay != null && !delay.isZero() && !delay.isNegative();
        }
    }

    @AssertTrue(message = "meeting verification submission interval must be positive")
    public boolean isSubmissionIntervalValid() {
        return submissionInterval != null
                && !submissionInterval.isZero()
                && !submissionInterval.isNegative();
    }

    @AssertTrue(message = "meeting verification meeting time window must be positive")
    public boolean isMeetingTimeWindowValid() {
        return meetingTimeWindow != null
                && !meetingTimeWindow.isZero()
                && !meetingTimeWindow.isNegative();
    }

    /**
     * HMAC secret 은 단순 문자 수가 아니라 UTF-8 인코딩 기준 32바이트 이상이어야 한다.
     * 멀티바이트 문자는 실제 byte 길이로 판정한다. 공개 example placeholder 는 운영에서
     * 실수로 사용하지 못하도록 함께 거절한다. {@code enabled=false} 여도 값 자체가
     * 잘못되면 startup 이 실패한다.
     */
    @AssertTrue(message = "meeting verification HMAC secret must be at least 32 bytes when encoded as UTF-8 and must not be the example placeholder")
    public boolean isHmacSecretValid() {
        return hmacSecret != null
                && !hmacSecret.isBlank()
                && !EXAMPLE_HMAC_SECRET.equals(hmacSecret)
                && hmacSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }
}
