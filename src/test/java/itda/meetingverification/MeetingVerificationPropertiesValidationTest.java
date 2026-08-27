package itda.meetingverification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@link MeetingVerificationProperties} 바인딩·startup validation 검증.
 *
 * <p>{@code expiry.batchSize >= 1}, {@code expiry.delay > 0} 는 {@code enabled=false} 여도
 * 값 자체가 잘못되면 context startup 이 실패해야 하고, {@code hmacSecret} 은 UTF-8 byte
 * 기준 32바이트 이상이어야 한다(단순 문자 수 아님).
 */
class MeetingVerificationPropertiesValidationTest {

    private static final String HMAC_31_ASCII = "abcdefghijklmnopqrstuvwxyz01234"; // 31 bytes
    private static final String HMAC_32_ASCII = "abcdefghijklmnopqrstuvwxyz012345"; // 32 bytes
    private static final String HMAC_30_UTF8 = "가나다라마바사아자차"; // 10 chars × 3 = 30 bytes
    private static final String HMAC_33_UTF8 = "가나다라마바사아자차카"; // 11 chars × 3 = 33 bytes

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MeetingVerificationPropertiesConfig.class)
            .withPropertyValues("app.meeting-verification.hmac-secret=" + HMAC_32_ASCII);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MeetingVerificationProperties.class)
    static class MeetingVerificationPropertiesConfig {
    }

    @Test
    void defaultExpirySettingsStartContextWithTypedDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MeetingVerificationProperties properties =
                    context.getBean(MeetingVerificationProperties.class);
            assertThat(properties.expiry().enabled()).isTrue();
            assertThat(properties.expiry().delay()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.expiry().batchSize()).isEqualTo(50);
        });
    }

    @Test
    void zeroBatchSizeFailsStartup() {
        runner.withPropertyValues("app.meeting-verification.expiry.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void negativeBatchSizeFailsStartup() {
        runner.withPropertyValues("app.meeting-verification.expiry.batch-size=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void zeroDelayFailsStartup() {
        runner.withPropertyValues("app.meeting-verification.expiry.delay=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void negativeDelayFailsStartup() {
        runner.withPropertyValues("app.meeting-verification.expiry.delay=-5s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabledExpiryStillFailsOnInvalidValues() {
        runner.withPropertyValues(
                        "app.meeting-verification.expiry.enabled=false",
                        "app.meeting-verification.expiry.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "app.meeting-verification.expiry.enabled=false",
                        "app.meeting-verification.expiry.delay=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabledExpiryWithValidValuesStarts() {
        runner.withPropertyValues("app.meeting-verification.expiry.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MeetingVerificationProperties properties =
                            context.getBean(MeetingVerificationProperties.class);
                    assertThat(properties.expiry().enabled()).isFalse();
                });
    }

    @Test
    void hmacSecretShorterThan32Utf8BytesFailsStartup() {
        runner.withPropertyValues("app.meeting-verification.hmac-secret=" + HMAC_31_ASCII)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.meeting-verification.hmac-secret=" + HMAC_30_UTF8)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void hmacSecretExamplePlaceholderFailsStartup() {
        runner.withPropertyValues("app.meeting-verification.hmac-secret="
                        + "replace-with-a-random-meeting-verification-hmac-secret-at-least-32-bytes")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void hmacSecretAtLeast32Utf8BytesStarts() {
        runner.withPropertyValues("app.meeting-verification.hmac-secret=" + HMAC_32_ASCII)
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.meeting-verification.hmac-secret=" + HMAC_33_UTF8)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
