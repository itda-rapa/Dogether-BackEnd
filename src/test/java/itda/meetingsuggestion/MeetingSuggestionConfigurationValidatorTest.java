package itda.meetingsuggestion;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.meetingcard.ai.MeetingDraftAiProperties;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MeetingSuggestionConfigurationValidator — zone 일관성 + lease/AI timeout 검증")
class MeetingSuggestionConfigurationValidatorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId OTHER = ZoneId.of("America/New_York");
    private static final String MORNING_CRON = "0 0 7 * * *";

    // ── enabled=true: KST zone + 07:00 cron 고정 ───────────────────────────────

    @Test
    @DisplayName("[enabled=true] 두 zone 모두 Asia/Seoul + cron 0 0 7 * * * → 통과")
    void kstMorningComboPasses() {
        assertThatCode(() -> validator(true, Duration.ofMinutes(1), Duration.ofSeconds(5),
                SEOUL, SEOUL, MORNING_CRON).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[enabled=true] 두 zone 모두 non-KST 면 실패한다(같기만 하면 안 된다)")
    void bothZonesNonKstFail() {
        assertThatThrownBy(() -> validator(true, Duration.ofMinutes(1), Duration.ofSeconds(5),
                OTHER, OTHER, MORNING_CRON).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.meeting-suggestion.zone (America/New_York) must be Asia/Seoul");
    }

    @Test
    @DisplayName("[enabled=true] suggestion zone 만 non-KST 면 실패한다")
    void suggestionZoneNonKstFails() {
        assertThatThrownBy(() -> validator(true, Duration.ofMinutes(1), Duration.ofSeconds(5),
                OTHER, SEOUL, MORNING_CRON).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.meeting-suggestion.zone (America/New_York) must be Asia/Seoul");
    }

    @Test
    @DisplayName("[enabled=true] AI zone 만 non-KST 면 실패한다")
    void aiZoneNonKstFails() {
        assertThatThrownBy(() -> validator(true, Duration.ofMinutes(1), Duration.ofSeconds(5),
                SEOUL, OTHER, MORNING_CRON).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.meeting-card.ai.zone (America/New_York) must be Asia/Seoul");
    }

    @Test
    @DisplayName("[enabled=true] zone 은 KST 여도 cron 이 다르면 실패한다")
    void nonMorningCronFails() {
        assertThatThrownBy(() -> validator(true, Duration.ofMinutes(1), Duration.ofSeconds(5),
                SEOUL, SEOUL, "0 0 8 * * *").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduler-cron ('0 0 8 * * *') must be exactly '0 0 7 * * *'");
    }

    @Test
    @DisplayName("[enabled=false] non-KST zone + 다른 cron + 불리한 lease 조합도 통과한다")
    void disabledSkipsAllContractChecks() {
        assertThatCode(() -> validator(false, Duration.ofSeconds(5), Duration.ofSeconds(30),
                OTHER, OTHER, "0 0 8 * * *").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    // ── enabled=true: lease 는 보수적 HTTP budget(2 x AI timeout) 보다 엄격히 길어야 한다 ──

    @Test
    @DisplayName("[enabled=true] lease 가 보수적 budget(2 x timeout) 보다 길면 통과한다")
    void leaseLongerThanConservativeBudgetPasses() {
        // timeout 5s → budget 10s. lease 11s 는 통과한다.
        assertThatCode(() -> validator(true, Duration.ofSeconds(11), Duration.ofSeconds(5)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[enabled=true] 기본 조합(lease=1m, AI timeout=5s) 은 통과한다")
    void defaultComboPasses() {
        assertThatCode(() -> validator(true, Duration.ofMinutes(1), Duration.ofSeconds(5)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[enabled=true] lease == 보수적 budget(2 x timeout) 이면 실패한다")
    void leaseEqualToConservativeBudgetFails() {
        // timeout 5s → budget 10s. lease 10s 는 경계로 실패한다.
        assertThatThrownBy(() -> validator(true, Duration.ofSeconds(10), Duration.ofSeconds(5)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be strictly longer");
    }

    @Test
    @DisplayName("[enabled=true] lease 가 보수적 budget 보다 짧으면 실패한다")
    void leaseShorterThanConservativeBudgetFails() {
        assertThatThrownBy(() -> validator(true, Duration.ofSeconds(5), Duration.ofSeconds(5)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be strictly longer");
    }

    @Test
    @DisplayName("[enabled=true] timeout 만 길어진 경우(예: AI_TIMEOUT=30s) 실제 값을 반영해 실패한다")
    void longerTimeoutIsReflectedInValidation() {
        // timeout 30s → budget 60s. lease 10s 는 실패하며 budget 값이 메시지에 드러난다.
        assertThatThrownBy(() -> validator(true, Duration.ofSeconds(10), Duration.ofSeconds(30)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 x app.meeting-card.ai.timeout = PT1M");
    }

    // ── enabled=false: 검증을 수행하지 않는다 ────────────────────────────────────

    @Test
    @DisplayName("[enabled=false] lease <= AI timeout 이어도 통과한다(기동을 막지 않는다)")
    void disabledSkipsValidationEvenWithBadCombo() {
        assertThatCode(() -> validator(false, Duration.ofSeconds(5), Duration.ofSeconds(5)).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator(false, Duration.ofSeconds(4), Duration.ofSeconds(30)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private static MeetingSuggestionConfigurationValidator validator(boolean enabled,
                                                                     Duration lease,
                                                                     Duration timeout) {
        return validator(enabled, lease, timeout, SEOUL, SEOUL, MORNING_CRON);
    }

    private static MeetingSuggestionConfigurationValidator validator(boolean enabled,
                                                                     Duration lease,
                                                                     Duration timeout,
                                                                     ZoneId suggestionZone,
                                                                     ZoneId aiZone,
                                                                     String cron) {
        return new MeetingSuggestionConfigurationValidator(
                properties(enabled, lease, suggestionZone, cron),
                new MeetingDraftAiProperties("http://localhost:8000", timeout, aiZone));
    }

    private static MeetingSuggestionProperties properties(boolean enabled,
                                                          Duration lease,
                                                          ZoneId zone,
                                                          String cron) {
        return new MeetingSuggestionProperties(
                enabled, zone, cron, 60000, 10,
                lease, 10, Duration.ofSeconds(5), Duration.ofMinutes(10));
    }
}
