package itda.meetingverification;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * GPS 만남 확정의 Meeting 정책 설정.
 *
 * <p>Location 영역({@code app.location})은 좌표 형식·freshness·accuracy 품질만 판정하고,
 * 거리 한도와 양쪽 제출 간격은 Meeting 정책이므로 이 설정으로 관리한다.
 * {@link MeetingVerificationService}가 두 위치의 거리·제출 시각 간격을 이 값으로 판정한다.
 */
@Validated
@ConfigurationProperties("app.meeting-verification")
public record MeetingVerificationProperties(
        @Positive @DefaultValue("100") double distanceLimitMeters,
        @NotNull @DefaultValue("5m") Duration submissionInterval
) {
    @AssertTrue(message = "meeting verification submission interval must be positive")
    public boolean isDurationPolicyValid() {
        return submissionInterval != null
                && !submissionInterval.isZero()
                && !submissionInterval.isNegative();
    }
}
