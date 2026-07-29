package itda.media.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.exception.BusinessException;
import itda.common.properties.MediaProperties;
import itda.media.old.domain.MediaPurpose;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MediaPolicyTest {

    private final MediaPolicy policy = new MediaPolicy(
            new MediaProperties(
                    Duration.ofMinutes(15),
                    Duration.ofMinutes(10),
                    10 * 1024 * 1024
            )
    );

    @Test
    void setlogAllowsMp4WithinConfiguredSize() {
        assertThatCode(() -> policy.validate(
                MediaPurpose.SETLOG,
                "video/mp4",
                1024
        )).doesNotThrowAnyException();
    }

    @Test
    void profileRejectsVideo() {
        assertThatThrownBy(() -> policy.validate(
                MediaPurpose.PROFILE,
                "video/mp4",
                1024
        )).isInstanceOf(BusinessException.class);
    }
}
