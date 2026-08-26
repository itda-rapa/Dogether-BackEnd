package itda.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.location.dto.LocationAccuracyQuality;
import itda.location.dto.LocationInput;
import itda.location.dto.ValidatedLocation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LocationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

    private LocationService service;

    @BeforeEach
    void setUp() {
        service = new LocationService(
                new LocationProperties(Duration.ofMinutes(5), Duration.ofSeconds(30), 50.0),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void validHighQualityLocationIsAcceptable() {
        var result = service.assess(input(37.5665, 126.9780, 24.5, NOW.minusSeconds(30)));

        assertThat(result.accuracyQuality()).isEqualTo(LocationAccuracyQuality.ACCEPTABLE);
        assertThat(result.requiresAccuracyFallback()).isFalse();
    }

    @Test
    void validLowAccuracyIsReturnedForMeetingFallbackInsteadOfRejected() {
        var result = service.assess(input(37.5665, 126.9780, 50.0, NOW.minusSeconds(30)));

        assertThat(result.accuracyQuality()).isEqualTo(LocationAccuracyQuality.LOW_ACCURACY);
        assertThat(result.requiresAccuracyFallback()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidLocations")
    void malformedLocationIsInvalid(LocationInput input) {
        assertThatThrownBy(() -> service.assess(input))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LOCATION_INVALID));
    }

    @Test
    void oldCaptureIsStale() {
        LocationInput stale = input(37.5665, 126.9780, 20.0, NOW.minus(Duration.ofMinutes(5).plusMillis(1)));

        assertThatThrownBy(() -> service.assess(stale))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LOCATION_STALE));
    }

    @Test
    void captureAtFreshnessBoundaryIsAccepted() {
        var result = service.assess(input(37.5665, 126.9780, 20.0, NOW.minus(Duration.ofMinutes(5))));

        assertThat(result.location().capturedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    void distanceCalculationDoesNotApplyMeetingDistancePolicy() {
        ValidatedLocation first = location(0.0, 0.0);
        ValidatedLocation second = location(0.0, 1.0);

        assertThat(service.distanceMeters(first, second)).isCloseTo(111_195.08, withinMeters(1.0));
    }

    private static Stream<Arguments> invalidLocations() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(input(null, 126.9780, 20.0, NOW)),
                Arguments.of(input(91.0, 126.9780, 20.0, NOW)),
                Arguments.of(input(37.5665, -181.0, 20.0, NOW)),
                Arguments.of(input(Double.NaN, 126.9780, 20.0, NOW)),
                Arguments.of(input(37.5665, 126.9780, -0.1, NOW)),
                Arguments.of(input(37.5665, 126.9780, Double.POSITIVE_INFINITY, NOW)),
                Arguments.of(input(37.5665, 126.9780, 20.0, null)),
                Arguments.of(input(37.5665, 126.9780, 20.0, NOW.plusSeconds(31)))
        );
    }

    private static LocationInput input(Double latitude, Double longitude, Double accuracy, Instant capturedAt) {
        return new LocationInput(latitude, longitude, accuracy, capturedAt);
    }

    private static ValidatedLocation location(double latitude, double longitude) {
        return new ValidatedLocation(latitude, longitude, 10.0, NOW);
    }

    private static org.assertj.core.data.Offset<Double> withinMeters(double meters) {
        return org.assertj.core.data.Offset.offset(meters);
    }
}
