package itda.meetingverification.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 위치 제출 요청(04_M3_API_상세명세.md §11 POST /meeting-cards/{cardId}/meeting-verifications).
 *
 * <p>위도·경도 유효성, stale, accuracy 품질, 거리 판정은 #146 Location 병합 뒤 수행하므로
 * 여기서 검증하지 않는다. {@code clientRequestId} 는 동일 Pet 재요청 식별용 전역 멱등키다.
 *
 * @param clientRequestId 멱등키(UUID)
 * @param latitude WGS84 위도
 * @param longitude WGS84 경도
 * @param accuracyMeters 위치 정확도(미터). 값 검증은 #146 병합 뒤
 * @param capturedAt 측위 시각
 */
public record MeetingVerificationSubmitCommand(
        UUID clientRequestId,
        double latitude,
        double longitude,
        double accuracyMeters,
        Instant capturedAt
) {
}
