package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StorageCleanupConfigurationDefaultsTest {

    @Test
    void applicationTestAndEnvDefaultsUseTwentyMinuteUploadAndThirtyMinuteGrace()
            throws IOException {
        String application = Files.readString(Path.of("src/main/resources/application.yaml"));
        String test = Files.readString(Path.of("src/test/resources/application-test.yaml"));
        String env = Files.readString(Path.of(".env.example"));

        assertThat(application)
                .contains("STORAGE_CLEANUP_MAX_UPLOAD_DURATION:20m")
                .contains("STORAGE_CLEANUP_UPLOAD_SETTLE_GRACE:30m");
        assertThat(test)
                .contains("max-upload-duration: 20m")
                .contains("upload-settle-grace: 30m");
        assertThat(env)
                .contains("STORAGE_CLEANUP_MAX_UPLOAD_DURATION=20m")
                .contains("STORAGE_CLEANUP_UPLOAD_SETTLE_GRACE=30m");
    }
}
