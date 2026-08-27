package itda.meetingverification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MeetingVerificationFingerprintTest {

    private final MeetingVerificationFingerprint fingerprint =
            new MeetingVerificationFingerprint("secret-32-bytes-minimum-for-testing");

    @Test
    void canonicalSerializationIsStable() {
        Instant capturedAt = Instant.parse("2026-07-30T00:00:00Z");
        String first = MeetingVerificationFingerprint.canonical(
                100L, 11L, 37.5665, 126.978, 24.5, capturedAt);
        String second = MeetingVerificationFingerprint.canonical(
                100L, 11L, 37.5665, 126.978, 24.5, capturedAt);

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo("100|11|37.5665|126.978|24.5|2026-07-30T00:00:00Z");
    }

    @Test
    void samePayloadProducesSameFingerprint() {
        Instant capturedAt = Instant.parse("2026-07-30T00:00:00Z");
        String first = fingerprint.compute(100L, 11L, 37.5665, 126.978, 24.5, capturedAt);
        String second = fingerprint.compute(100L, 11L, 37.5665, 126.978, 24.5, capturedAt);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64); // HMAC-SHA-256 hex
    }

    @Test
    void differentPayloadProducesDifferentFingerprint() {
        Instant capturedAt = Instant.parse("2026-07-30T00:00:00Z");
        String first = fingerprint.compute(100L, 11L, 37.5665, 126.978, 24.5, capturedAt);
        String differentCoordinate = fingerprint.compute(100L, 11L, 37.5666, 126.978, 24.5, capturedAt);
        String differentCard = fingerprint.compute(101L, 11L, 37.5665, 126.978, 24.5, capturedAt);

        assertThat(first).isNotEqualTo(differentCoordinate);
        assertThat(first).isNotEqualTo(differentCard);
    }

    @Test
    void blankSecretIsRejected() {
        assertThatThrownBy(() -> new MeetingVerificationFingerprint(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MeetingVerificationFingerprint(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void examplePlaceholderSecretIsRejected() {
        assertThatThrownBy(() -> new MeetingVerificationFingerprint(
                "replace-with-a-random-meeting-verification-hmac-secret-at-least-32-bytes"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretShorterThan32Utf8BytesIsRejected() {
        // 31 ASCII bytes 와 30 bytes 멀티바이트(한글 10자 × 3)는 모두 거절한다.
        assertThatThrownBy(() -> new MeetingVerificationFingerprint(
                "abcdefghijklmnopqrstuvwxyz01234"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MeetingVerificationFingerprint("가나다라마바사아자차"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretAtLeast32Utf8BytesIsAccepted() {
        // 정확히 32 ASCII bytes 와 33 bytes 멀티바이트(한글 11자 × 3)는 허용한다.
        assertThat(new MeetingVerificationFingerprint(
                "abcdefghijklmnopqrstuvwxyz012345")).isNotNull();
        assertThat(new MeetingVerificationFingerprint("가나다라마바사아자차카")).isNotNull();
    }
}
