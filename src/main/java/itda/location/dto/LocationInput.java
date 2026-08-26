package itda.location.dto;

import java.time.Instant;

/**
 * Location 영역의 공용 WGS84 입력 계약.
 *
 * <p>Wrapper 타입을 사용해 JSON 필드 누락과 실제 숫자 0을 구분한다. 유효성 검사는
 * {@code LocationService}가 담당하며, 낮은 accuracy 품질은 validation 오류가 아니다.
 */
public record LocationInput(
        Double latitude,
        Double longitude,
        Double accuracyMeters,
        Instant capturedAt
) {
}
