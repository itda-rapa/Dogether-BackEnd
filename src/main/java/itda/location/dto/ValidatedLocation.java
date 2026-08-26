package itda.location.dto;

import java.time.Instant;

/** 형식·범위·freshness 검사를 통과한 위치 값이다. */
public record ValidatedLocation(
        double latitude,
        double longitude,
        double accuracyMeters,
        Instant capturedAt
) {
}
