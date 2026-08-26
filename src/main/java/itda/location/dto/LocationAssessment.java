package itda.location.dto;

/**
 * 유효한 위치와 accuracy 품질을 함께 전달한다.
 *
 * <p>{@code LOW_ACCURACY}도 정상 결과이므로 Meeting은 이를 확인 코드 fallback으로
 * 소비할 수 있다.
 */
public record LocationAssessment(
        ValidatedLocation location,
        LocationAccuracyQuality accuracyQuality
) {
    public boolean requiresAccuracyFallback() {
        return accuracyQuality == LocationAccuracyQuality.LOW_ACCURACY;
    }
}
