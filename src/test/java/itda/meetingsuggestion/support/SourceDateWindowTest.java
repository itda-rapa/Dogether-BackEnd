package itda.meetingsuggestion.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SourceDateWindow — KST 전날 창")
class SourceDateWindowTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("07:00 KST 실행이면 sourceDate 는 전날, referenceDate 는 실행일이다")
    void runAtSevenAmUsesYesterday() {
        // 2026-08-25T07:00 KST = 2026-08-24T22:00Z
        SourceDateWindow window = SourceDateWindow.forRunAt(
                Instant.parse("2026-08-24T22:00:00Z"), SEOUL);

        assertThat(window.sourceDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(window.referenceDate()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-08-23T15:00:00Z"));
        assertThat(window.windowEnd()).isEqualTo(Instant.parse("2026-08-24T15:00:00Z"));
    }

    @Test
    @DisplayName("KST 자정 직전 실행은 그 전날이 sourceDate 다")
    void justBeforeKstMidnightBelongsToPreviousDay() {
        SourceDateWindow window = SourceDateWindow.forRunAt(
                Instant.parse("2026-08-24T14:59:59Z"), SEOUL);

        assertThat(window.sourceDate()).isEqualTo(LocalDate.of(2026, 8, 23));
        assertThat(window.referenceDate()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    @Test
    @DisplayName("KST 자정 정각 실행은 그날이 실행일이다")
    void exactlyKstMidnightBelongsToNewDay() {
        SourceDateWindow window = SourceDateWindow.forRunAt(
                Instant.parse("2026-08-24T15:00:00Z"), SEOUL);

        assertThat(window.sourceDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(window.referenceDate()).isEqualTo(LocalDate.of(2026, 8, 25));
    }

    @Test
    @DisplayName("retry 는 저장된 sourceDate/referenceDate 로 창을 만들고 referenceDate 를 재계산하지 않는다")
    void retryKeepsStoredReferenceDate() {
        SourceDateWindow window = SourceDateWindow.of(
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 21), SEOUL);

        assertThat(window.sourceDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(window.referenceDate()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-08-19T15:00:00Z"));
        assertThat(window.windowEnd()).isEqualTo(Instant.parse("2026-08-20T15:00:00Z"));
    }
}
