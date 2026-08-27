package itda.meetingverification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * raw GPS retention 기본 안전값 검증. 만료 worker 는 opt-in 이 아니라 기본 활성
 * ({@code enabled: true})이어야 하고, prod 에서 비활성화하는 위험이 설정에 드러나야 한다.
 * 또한 application.yaml/.env.example/application-test.yaml 의 기본값이 typed configuration
 * 계약과 어긋나지 않는지 코드로 대조한다.
 */
class MeetingVerificationConfigurationDefaultsTest {

    @Test
    void expiryWorkerIsEnabledByDefaultInApplicationAndEnv() throws IOException {
        String application = Files.readString(Path.of("src/main/resources/application.yaml"));
        String env = Files.readString(Path.of(".env.example"));

        assertThat(application).contains("MEETING_VERIFICATION_EXPIRY_ENABLED:true");
        assertThat(application).contains("MEETING_VERIFICATION_EXPIRY_DELAY:60s");
        assertThat(application).contains("MEETING_VERIFICATION_EXPIRY_BATCH_SIZE:50");
        assertThat(env).contains("MEETING_VERIFICATION_EXPIRY_ENABLED=true");
        assertThat(env).contains("MEETING_VERIFICATION_EXPIRY_DELAY=60s");
        assertThat(env).contains("MEETING_VERIFICATION_EXPIRY_BATCH_SIZE=50");
    }

    @Test
    void expiryServiceUsesTypedPropertiesInsteadOfValue() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/itda/meetingverification/service/MeetingVerificationExpiryService.java"));

        assertThat(source).doesNotContain("@Value(");
        assertThat(source).doesNotContain("org.springframework.beans.factory.annotation.Value");
        assertThat(source).contains("properties.expiry().batchSize()");
    }

    @Test
    void testProfileHmacSecretIsAtLeast32Utf8Bytes() throws IOException {
        String testSecret = "test-meeting-verification-hmac-secret-at-least-32-bytes";
        assertThat(testSecret.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThanOrEqualTo(32);
        String testYaml = Files.readString(Path.of("src/test/resources/application-test.yaml"));
        assertThat(testYaml).contains("hmac-secret: " + testSecret);
    }
}
