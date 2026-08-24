package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RiskSignalOutboxRelayPropertiesTest {

    @Test
    void acceptsSafeRelayConfiguration() {
        assertThat(validator().validate(new RiskSignalOutboxRelayProperties(
                true, 1_000, 50, Duration.ofMinutes(1), Duration.ofSeconds(10),
                Duration.ofSeconds(5), 10, Duration.ofSeconds(5), Duration.ofMinutes(10))))
                .isEmpty();
    }

    @Test
    void rejectsShortDelayAndLeaseNotLongerThanSendTimeout() {
        var violations = validator().validate(new RiskSignalOutboxRelayProperties(
                true, 999, 50, Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(5), 10, Duration.ofMinutes(2), Duration.ofMinutes(1)));

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .contains("delayMs", "durationsValid");
    }

    @Test
    void rejectsSubMillisecondTimeoutAndInsufficientLeaseSafetyMargin() {
        var violations = validator().validate(new RiskSignalOutboxRelayProperties(
                true, 1_000, 50, Duration.ofSeconds(20), Duration.ofNanos(1),
                Duration.ofSeconds(15), 10, Duration.ofSeconds(5), Duration.ofMinutes(10)));

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .contains("durationsValid");
    }

    private static jakarta.validation.Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }
}
