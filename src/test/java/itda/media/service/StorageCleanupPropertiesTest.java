package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StorageCleanupPropertiesTest {

    @Test
    void acceptsSafeDurationsAndOrdering() {
        assertThat(validator().validate(new StorageCleanupProperties(
                60_000, 100, Duration.ofMinutes(10), 10,
                Duration.ofMinutes(20), Duration.ofMinutes(30),
                Duration.ofSeconds(30), Duration.ofHours(6))))
                .isEmpty();
    }

    @Test
    void rejectsShortDelayLeaseAndReversedBackoff() {
        var violations = validator().validate(new StorageCleanupProperties(
                999, 100, Duration.ofSeconds(29), 10,
                Duration.ZERO, Duration.ZERO,
                Duration.ofMinutes(2), Duration.ofSeconds(30)));

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .contains("delayMs", "durationsValid");
    }

    @Test
    void equalGraceAndMaxUploadDurationIsAllowed() {
        assertThat(validator().validate(new StorageCleanupProperties(
                60_000, 100, Duration.ofMinutes(5), 10,
                Duration.ofMinutes(20), Duration.ofMinutes(20),
                Duration.ofSeconds(30), Duration.ofHours(6))))
                .isEmpty();
    }

    @Test
    void graceShorterThanMaxUploadDurationIsRejected() {
        var violations = validator().validate(new StorageCleanupProperties(
                60_000, 100, Duration.ofMinutes(5), 10,
                Duration.ofMinutes(20), Duration.ofMinutes(19),
                Duration.ofSeconds(30), Duration.ofHours(6)));

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .contains("durationsValid");
    }

    private static jakarta.validation.Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }
}
