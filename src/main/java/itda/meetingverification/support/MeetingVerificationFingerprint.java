package itda.meetingverification.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 동일 HMAC key가 유지되는 동안 request ledger replay를 가능하게 하는 payload fingerprint.
 *
 * <p>raw 좌표를 ledger 에 영구 보관하지 않기 위해, 서버 비밀키 기반 HMAC-SHA-256 으로
 * canonical payload 를 되돌릴 수 없는 fingerprint 로 바꾼다. bare SHA-256 은 secret 없이
 * 재현 가능하므로 사용하지 않는다.
 *
 * <p>canonical payload 는 아래 순서의 {@code "|"} 구분 문자열이며, 각 필드는 다음
 * canonical 직렬화를 따른다.
 * <ul>
 *   <li>{@code meetingCardId}, {@code participantPetId}: {@link Long#toString}</li>
 *   <li>{@code latitude}, {@code longitude}, {@code accuracyMeters}:
 *       {@link Double#toString} (shortest round-trip. NaN/Infinity 는 상위에서 거부된다)</li>
 *   <li>{@code capturedAt}: {@link Instant#toString} (ISO-8601 UTC)</li>
 * </ul>
 * 예: {@code 100|11|37.5665|126.978|24.5|2026-07-30T00:00:00Z}
 */
public final class MeetingVerificationFingerprint {

    private static final String SEPARATOR = "|";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_SECRET_BYTES = 32;
    private static final String EXAMPLE_SECRET =
            "replace-with-a-random-meeting-verification-hmac-secret-at-least-32-bytes";

    private final byte[] secret;

    public MeetingVerificationFingerprint(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("meeting verification HMAC secret is required");
        }
        if (EXAMPLE_SECRET.equals(secret)) {
            throw new IllegalArgumentException(
                    "meeting verification HMAC secret must not be the example placeholder");
        }
        // typed properties 의 startup 검증(UTF-8 32바이트)을 우회해 직접 생성해도 약한 secret 을
        // 허용하지 않는 최소 방어다. 멀티바이트 문자열도 UTF-8 실제 byte 길이로 판정한다.
        if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "meeting verification HMAC secret must be at least 32 bytes when encoded as UTF-8");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String compute(Long meetingCardId,
                          Long participantPetId,
                          double latitude,
                          double longitude,
                          double accuracyMeters,
                          Instant capturedAt) {
        String canonical = canonical(meetingCardId, participantPetId,
                latitude, longitude, accuracyMeters, capturedAt);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to compute meeting verification fingerprint",
                    exception);
        }
    }

    static String canonical(Long meetingCardId,
                            Long participantPetId,
                            double latitude,
                            double longitude,
                            double accuracyMeters,
                            Instant capturedAt) {
        return String.join(SEPARATOR,
                Long.toString(meetingCardId),
                Long.toString(participantPetId),
                Double.toString(latitude),
                Double.toString(longitude),
                Double.toString(accuracyMeters),
                capturedAt.toString());
    }
}
