package itda.location.service;

import static itda.location.dto.LocationAccuracyQuality.ACCEPTABLE;
import static itda.location.dto.LocationAccuracyQuality.LOW_ACCURACY;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.location.dto.LocationAssessment;
import itda.location.dto.LocationInput;
import itda.location.dto.ValidatedLocation;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 좌표의 구조적 유효성·freshness를 검증하고 두 WGS84 좌표 사이의 거리를 계산한다.
 *
 * <p>거리 제한, 양쪽 제출 간격, 약속 시간창은 Meeting 정책이므로 이 서비스에서
 * 판정하지 않는다. accuracy가 양수이지만 품질 기준에 미달하는 경우에도 예외를 던지지
 * 않고 {@code LOW_ACCURACY}를 반환한다.
 */
@Service
@RequiredArgsConstructor
public class LocationService {
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private final LocationProperties properties;
    private final Clock clock;

    public LocationAssessment assess(LocationInput input) {
        if (input == null
                || !coordinateInRange(input.latitude(), -90.0, 90.0)
                || !coordinateInRange(input.longitude(), -180.0, 180.0)
                || !nonNegativeFinite(input.accuracyMeters())
                || input.capturedAt() == null) {
            throw new BusinessException(ErrorCode.LOCATION_INVALID);
        }

        Instant now = clock.instant();
        if (input.capturedAt().isAfter(now.plus(properties.futureTolerance()))) {
            throw new BusinessException(ErrorCode.LOCATION_INVALID);
        }
        if (input.capturedAt().isBefore(now.minus(properties.maxCaptureAge()))) {
            throw new BusinessException(ErrorCode.LOCATION_STALE);
        }

        ValidatedLocation location = new ValidatedLocation(
                input.latitude(),
                input.longitude(),
                input.accuracyMeters(),
                input.capturedAt()
        );
        return new LocationAssessment(
                location,
                input.accuracyMeters() < properties.qualityThresholdMeters()
                        ? ACCEPTABLE
                        : LOW_ACCURACY
        );
    }

    /** 임계값 판정 없이 두 유효 좌표 사이의 Haversine 거리를 미터로 반환한다. */
    public double distanceMeters(ValidatedLocation first, ValidatedLocation second) {
        if (first == null
                || second == null
                || !coordinateInRange(first.latitude(), -90.0, 90.0)
                || !coordinateInRange(first.longitude(), -180.0, 180.0)
                || !coordinateInRange(second.latitude(), -90.0, 90.0)
                || !coordinateInRange(second.longitude(), -180.0, 180.0)) {
            throw new BusinessException(ErrorCode.LOCATION_INVALID);
        }
        double firstLatitude = Math.toRadians(first.latitude());
        double secondLatitude = Math.toRadians(second.latitude());
        double latitudeDelta = secondLatitude - firstLatitude;
        double longitudeDelta = Math.toRadians(second.longitude() - first.longitude());

        double haversine = square(Math.sin(latitudeDelta / 2.0))
                + Math.cos(firstLatitude) * Math.cos(secondLatitude)
                * square(Math.sin(longitudeDelta / 2.0));
        double centralAngle = 2.0 * Math.atan2(
                Math.sqrt(haversine),
                Math.sqrt(Math.max(0.0, 1.0 - haversine))
        );
        return EARTH_RADIUS_METERS * centralAngle;
    }

    private boolean coordinateInRange(Double value, double minimum, double maximum) {
        return value != null && Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private boolean coordinateInRange(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private boolean nonNegativeFinite(Double value) {
        return value != null && Double.isFinite(value) && value >= 0.0;
    }

    private double square(double value) {
        return value * value;
    }
}
