package itda.meetingverification.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * 위치 제출 요청(04_M3_API_상세명세.md §11 POST /meeting-cards/{cardId}/meeting-verifications).
 *
 * <p>좌표·accuracy 는 {@code Double} wrapper 로 받아 JSON 필드 누락과 실제 숫자
 * {@code 0.0} 을 구분한다({@link itda.location.dto.LocationInput} 과 같은 관례). 필드
 * 누락은 {@code @NotNull} 로 400 에서 막고, 실제 {@code 0.0} 값 자체의 범위·품질 판정은
 * {@code LocationService.assess} 의 책임이다.
 *
 * @param clientRequestId 멱등키(UUID, 필수)
 * @param latitude WGS84 위도(필수, 0.0 허용)
 * @param longitude WGS84 경도(필수, 0.0 허용)
 * @param accuracyMeters 위치 정확도(미터, 필수, 0.0 허용)
 * @param capturedAt 측위 시각(필수)
 */
public record MeetingVerificationSubmitCommand(
        @NotNull UUID clientRequestId,
        @NotNull Double latitude,
        @NotNull Double longitude,
        @NotNull Double accuracyMeters,
        @NotNull Instant capturedAt
) {
}
