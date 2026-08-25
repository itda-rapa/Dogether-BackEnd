package itda.meetingsuggestion.support;

import static org.assertj.core.api.Assertions.assertThat;

import itda.meetingcard.domain.MeetingCardType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CandidateNormalizer — canonical 정규화와 fingerprint")
class CandidateNormalizerTest {

    @Test
    @DisplayName("ISO 날짜는 canonical 로 정규화된다")
    void dateIsCanonicalized() {
        assertThat(CandidateNormalizer.normalizeDate("2026-08-25")).isEqualTo("2026-08-25");
        assertThat(CandidateNormalizer.normalizeDate(" 2026-08-25 ")).isEqualTo("2026-08-25");
    }

    @Test
    @DisplayName("ISO 가 아니면 trim 한 원문을 유지한다")
    void unparseableDateKeepsTrimmedRaw() {
        assertThat(CandidateNormalizer.normalizeDate("다음주 금요일")).isEqualTo("다음주 금요일");
        assertThat(CandidateNormalizer.normalizeDate(null)).isNull();
    }

    @Test
    @DisplayName("시각은 HH:mm canonical 로 정규화된다")
    void timeIsCanonicalized() {
        assertThat(CandidateNormalizer.normalizeTime("19:00")).isEqualTo("19:00");
        // 초가 붙어도 같은 의미면 같은 canonical 이 된다.
        assertThat(CandidateNormalizer.normalizeTime("19:00:00")).isEqualTo("19:00");
        assertThat(CandidateNormalizer.normalizeTime(" 19:00 ")).isEqualTo("19:00");
    }

    @Test
    @DisplayName("ISO 가 아닌 시각은 trim 한 원문을 유지한다")
    void unparseableTimeKeepsTrimmedRaw() {
        assertThat(CandidateNormalizer.normalizeTime("저녁 7시")).isEqualTo("저녁 7시");
        assertThat(CandidateNormalizer.normalizeTime(null)).isNull();
    }

    @Test
    @DisplayName("장소는 trim 하고 500자로 자른다")
    void placeIsTrimmedAndTruncated() {
        assertThat(CandidateNormalizer.normalizePlace("  중앙공원  ")).isEqualTo("중앙공원");
        assertThat(CandidateNormalizer.normalizePlace("a".repeat(600))).hasSize(500);
        assertThat(CandidateNormalizer.normalizePlace(null)).isNull();
    }

    @Test
    @DisplayName("같은 의미 후보는 배열 순서와 무관하게 같은 fingerprint 다")
    void sameCandidateHasSameFingerprintRegardlessOfOrder() {
        String first = CandidateNormalizer.fingerprint(
                7L, MeetingCardType.WALK, "2026-08-26", "19:00", "중앙공원");
        String second = CandidateNormalizer.fingerprint(
                7L, MeetingCardType.WALK, "2026-08-26", "19:00", "중앙공원");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    @DisplayName("시각의 원문 표현이 달라도 canonical 이 같으면 fingerprint 가 같다")
    void rawTimeVariantNormalizesToSameFingerprint() {
        String withSeconds = CandidateNormalizer.fingerprint(
                7L, MeetingCardType.WALK, "2026-08-26", "19:00:00", "중앙공원");
        String withoutSeconds = CandidateNormalizer.fingerprint(
                7L, MeetingCardType.WALK, "2026-08-26", "19:00", "중앙공원");

        assertThat(withSeconds).isEqualTo(withoutSeconds);
    }

    @Test
    @DisplayName("다른 Scan 이면 fingerprint 가 다르다")
    void differentScanHasDifferentFingerprint() {
        String scanOne = CandidateNormalizer.fingerprint(
                1L, MeetingCardType.WALK, "2026-08-26", "19:00", "중앙공원");
        String scanTwo = CandidateNormalizer.fingerprint(
                2L, MeetingCardType.WALK, "2026-08-26", "19:00", "중앙공원");

        assertThat(scanOne).isNotEqualTo(scanTwo);
    }

    @Test
    @DisplayName("어느 의미 필드가 달라도 fingerprint 가 다르다")
    void differentSemanticsHaveDifferentFingerprints() {
        String base = CandidateNormalizer.fingerprint(
                7L, MeetingCardType.WALK, "2026-08-26", "19:00", "중앙공원");

        assertThat(CandidateNormalizer.fingerprint(
                7L, MeetingCardType.PLAY, "2026-08-26", "19:00", "중앙공원")).isNotEqualTo(base);
        assertThat(CandidateNormalizer.fingerprint(
                7L, MeetingCardType.WALK, "2026-08-27", "19:00", "중앙공원")).isNotEqualTo(base);
        assertThat(CandidateNormalizer.fingerprint(
                7L, MeetingCardType.WALK, "2026-08-26", "20:00", "중앙공원")).isNotEqualTo(base);
        assertThat(CandidateNormalizer.fingerprint(
                7L, MeetingCardType.WALK, "2026-08-26", "19:00", "한강공원")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("null 종류/장소도 결정적으로 다뤄진다")
    void nullTypeAndPlaceAreDeterministic() {
        assertThat(CandidateNormalizer.fingerprint(7L, null, "2026-08-26", "19:00", null))
                .isEqualTo(CandidateNormalizer.fingerprint(7L, null, "2026-08-26", "19:00", null));
    }
}
