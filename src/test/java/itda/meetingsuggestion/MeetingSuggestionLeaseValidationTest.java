package itda.meetingsuggestion;

import static org.assertj.core.api.Assertions.assertThat;

import itda.meetingcard.ai.MeetingDraftAiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Spring 바인딩 경로까지 포함해 zone 일관성·lease 와 실제 AI timeout 의 시작 검증을
 * 확인한다.
 *
 * <p>환경변수(AI_TIMEOUT, MEETING_SUGGESTION_ZONE 등)는 결국 같은 property 로
 * 바인딩되므로, property 값이 {@link MeetingSuggestionConfigurationValidator} 에 그대로
 * 전달돼 성공/실패하는지가 검증 대상이다. 기능이 비활성(enabled=false)이면 조합과
 * 무관하게 앱 기동이 성공해야 한다.
 */
@DisplayName("MeetingSuggestionLeaseValidation — Spring 바인딩 경로 검증")
class MeetingSuggestionLeaseValidationTest {

    private static final String SEOUL = "Asia/Seoul";
    private static final String OTHER = "America/New_York";
    private static final String MORNING_CRON = "0 0 7 * * *";

    @Test
    @DisplayName("[enabled=true] 기본 조합(lease=1m, timeout=5s, KST zone, 07:00 cron) 은 컨텍스트가 시작된다")
    void defaultComboStarts() {
        runner(true, "1m", "5s", SEOUL, SEOUL, MORNING_CRON).run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(MeetingSuggestionConfigurationValidator.class);
        });
    }

    @Test
    @DisplayName("[enabled=true] lease == AI timeout 이면 시작이 실패한다")
    void leaseEqualToTimeoutFailsStartup() {
        runner(true, "5s", "5s", SEOUL, SEOUL, MORNING_CRON).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("must be strictly longer");
        });
    }

    @Test
    @DisplayName("[enabled=true] lease == 보수적 budget(2 x timeout) 이면 시작이 실패한다")
    void leaseEqualToConservativeBudgetFailsStartup() {
        runner(true, "10s", "5s", SEOUL, SEOUL, MORNING_CRON).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("must be strictly longer");
        });
    }

    @Test
    @DisplayName("[enabled=true] timeout 만 길어진 값(AI_TIMEOUT=30s 반영) 이면 시작이 실패한다")
    void longerAiTimeoutIsReflectedInStartupValidation() {
        runner(true, "10s", "30s", SEOUL, SEOUL, MORNING_CRON).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("2 x app.meeting-card.ai.timeout = PT1M");
        });
    }

    @Test
    @DisplayName("[enabled=true] 두 zone 모두 non-KST 면 시작이 실패한다")
    void bothZonesNonKstFailStartup() {
        runner(true, "1m", "5s", OTHER, OTHER, MORNING_CRON).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("app.meeting-suggestion.zone (America/New_York) must be Asia/Seoul");
        });
    }

    @Test
    @DisplayName("[enabled=true] AI zone 만 non-KST 면 시작이 실패한다")
    void aiZoneNonKstFailsStartup() {
        runner(true, "1m", "5s", SEOUL, OTHER, MORNING_CRON).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("app.meeting-card.ai.zone (America/New_York) must be Asia/Seoul");
        });
    }

    @Test
    @DisplayName("[enabled=true] cron 이 07:00 이 아니면 시작이 실패한다")
    void nonMorningCronFailsStartup() {
        runner(true, "1m", "5s", SEOUL, SEOUL, "0 0 8 * * *").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("scheduler-cron ('0 0 8 * * *') must be exactly '0 0 7 * * *'");
        });
    }

    @Test
    @DisplayName("[enabled=false] AI_TIMEOUT 이 lease 보다 길어도 컨텍스트가 시작된다")
    void disabledFeatureStartsEvenWithShortLease() {
        runner(false, "5s", "30s", SEOUL, SEOUL, MORNING_CRON).run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(MeetingSuggestionConfigurationValidator.class);
        });
    }

    @Test
    @DisplayName("[enabled=false] lease == AI timeout 이어도 컨텍스트가 시작된다")
    void disabledFeatureStartsEvenWithEqualLease() {
        runner(false, "5s", "5s", SEOUL, SEOUL, MORNING_CRON).run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(MeetingSuggestionConfigurationValidator.class);
        });
    }

    @Test
    @DisplayName("[enabled=false] non-KST zone + 다른 cron 이어도 컨텍스트가 시작된다")
    void disabledFeatureStartsEvenWithNonKstAndOtherCron() {
        runner(false, "5s", "5s", OTHER, OTHER, "0 0 8 * * *").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(MeetingSuggestionConfigurationValidator.class);
        });
    }

    private static ApplicationContextRunner runner(boolean enabled,
                                                   String lease,
                                                   String timeout,
                                                   String suggestionZone,
                                                   String aiZone,
                                                   String cron) {
        return new ApplicationContextRunner()
                .withPropertyValues(
                        "app.meeting-suggestion.enabled=" + enabled,
                        "app.meeting-suggestion.zone=" + suggestionZone,
                        "app.meeting-suggestion.scheduler-cron=" + cron,
                        "app.meeting-suggestion.retry-delay-ms=60000",
                        "app.meeting-suggestion.batch-size=10",
                        "app.meeting-suggestion.max-attempts=10",
                        "app.meeting-suggestion.base-backoff=5s",
                        "app.meeting-suggestion.max-backoff=10m",
                        "app.meeting-suggestion.lease=" + lease,
                        "app.meeting-card.ai.base-url=http://localhost:8000",
                        "app.meeting-card.ai.timeout=" + timeout,
                        "app.meeting-card.ai.zone=" + aiZone)
                .withUserConfiguration(PropertiesConfig.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MeetingSuggestionProperties.class, MeetingDraftAiProperties.class})
    @Import(MeetingSuggestionConfigurationValidator.class)
    static class PropertiesConfig {
    }
}
