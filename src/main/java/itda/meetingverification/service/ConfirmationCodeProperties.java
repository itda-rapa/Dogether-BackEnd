package itda.meetingverification.service;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Confirmation Code의 짧은 수명과 brute-force 제한을 코드 밖에서 관리한다. */
@Validated
@ConfigurationProperties(prefix = "app.meeting-confirmation-code")
public record ConfirmationCodeProperties(
        @NotNull Duration ttl,
        @Positive int maxAttempts
) {

    /** TTL은 0 또는 음수일 수 없다. 활성화 여부와 무관하게 startup fail-fast 대상이다. */
    @AssertTrue(message = "confirmation code TTL must be positive")
    public boolean isTtlValid() {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }
}
