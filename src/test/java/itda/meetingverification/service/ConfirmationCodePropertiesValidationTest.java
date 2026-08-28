package itda.meetingverification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ConfirmationCodeProperties} 바인딩·startup validation 검증.
 *
 * <p>{@code ttl} 은 0 또는 음수일 수 없고, 활성화 여부와 무관하게 설정 오류로 startup 이
 * 실패해야 한다.
 */
class ConfirmationCodePropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConfirmationCodePropertiesConfig.class)
            .withPropertyValues("app.meeting-confirmation-code.ttl=5m",
                    "app.meeting-confirmation-code.max-attempts=5");

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConfirmationCodeProperties.class)
    static class ConfirmationCodePropertiesConfig {
    }

    @Test
    void defaultTtlAndMaxAttemptsStartContext() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ConfirmationCodeProperties properties =
                    context.getBean(ConfirmationCodeProperties.class);
            assertThat(properties.ttl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.maxAttempts()).isEqualTo(5);
        });
    }

    @Test
    void zeroTtlFailsStartup() {
        runner.withPropertyValues("app.meeting-confirmation-code.ttl=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void negativeTtlFailsStartup() {
        runner.withPropertyValues("app.meeting-confirmation-code.ttl=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void oneSecondTtlStarts() {
        runner.withPropertyValues("app.meeting-confirmation-code.ttl=1s")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
