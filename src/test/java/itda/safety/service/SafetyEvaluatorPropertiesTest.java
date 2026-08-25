package itda.safety.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SafetyEvaluatorPropertiesTest {
    @Test
    void disabledEvaluatorDoesNotInventPolicyDefaults() {
        var properties = new SafetyEvaluatorProperties(
                false, null, null, null, 50, 5_000, Duration.ofMinutes(1),
                10, Duration.ofSeconds(5), Duration.ofMinutes(10));

        assertThat(validator().validate(properties)).isEmpty();
        assertThat(properties.threshold()).isNull();
        assertThat(properties.window()).isNull();
        assertThat(properties.policyVersion()).isNull();
    }

    @Test
    void enabledEvaluatorRequiresExplicitPositivePolicy() {
        var invalid = new SafetyEvaluatorProperties(
                true, null, null, null, 50, 5_000, Duration.ofMinutes(1),
                10, Duration.ofSeconds(5), Duration.ofMinutes(10));
        var valid = new SafetyEvaluatorProperties(
                true, 90, Duration.ofDays(30), 1, 50, 5_000, Duration.ofMinutes(1),
                10, Duration.ofSeconds(5), Duration.ofMinutes(10));

        assertThat(validator().validate(invalid)).extracting(v -> v.getPropertyPath().toString())
                .contains("policyConfigured");
        assertThat(validator().validate(valid)).isEmpty();
    }

    @Test
    void rejectsUnsafeWorkerLimitsAndBackoffOrder() {
        var invalid = new SafetyEvaluatorProperties(
                true, 90, Duration.ofDays(30), 1, 0, 999, Duration.ZERO,
                0, Duration.ofMinutes(2), Duration.ofSeconds(1));

        assertThat(validator().validate(invalid)).extracting(v -> v.getPropertyPath().toString())
                .contains("batchSize", "delayMs", "maxAttempts", "durationsValid");
    }

    @Test
    void rejectsWindowLongerThanM3PolicyMaximum() {
        var invalid = new SafetyEvaluatorProperties(
                true, 90, Duration.ofDays(91), 1, 50, 5_000, Duration.ofMinutes(1),
                10, Duration.ofSeconds(5), Duration.ofMinutes(10));

        assertThat(validator().validate(invalid)).extracting(v -> v.getPropertyPath().toString())
                .contains("policyConfigured");
    }

    private static jakarta.validation.Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }
}
