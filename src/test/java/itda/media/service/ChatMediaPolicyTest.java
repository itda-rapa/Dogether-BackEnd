package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.MediaType;
import org.junit.jupiter.api.Test;

class ChatMediaPolicyTest {

    @Test
    void acceptsContractMimeTypesAtTheirMaximumSizes() {
        assertThat(ChatMediaPolicy.requireValidUpload(
                MediaType.IMAGE, "image/jpeg", 10L * 1024 * 1024)).isEqualTo("image/jpeg");
        assertThat(ChatMediaPolicy.requireValidUpload(
                MediaType.IMAGE, "image/png", 10L * 1024 * 1024)).isEqualTo("image/png");
        assertThat(ChatMediaPolicy.requireValidUpload(
                MediaType.IMAGE, "image/webp", 10L * 1024 * 1024)).isEqualTo("image/webp");
        assertThat(ChatMediaPolicy.requireValidUpload(
                MediaType.VIDEO, "video/mp4", 50L * 1024 * 1024)).isEqualTo("video/mp4");
    }

    @Test
    void rejectsUnsupportedMimeWith415() {
        assertError(
                () -> ChatMediaPolicy.requireValidUpload(MediaType.IMAGE, "image/gif", 1L),
                ErrorCode.INVALID_MEDIA_TYPE
        );
        assertThat(ErrorCode.INVALID_MEDIA_TYPE.getStatus().value()).isEqualTo(415);
    }

    @Test
    void rejectsInvalidOrOversizedFileWith413() {
        assertError(
                () -> ChatMediaPolicy.requireValidUpload(
                        MediaType.IMAGE, "image/png", 10L * 1024 * 1024 + 1),
                ErrorCode.MEDIA_SIZE_INVALID
        );
        assertError(
                () -> ChatMediaPolicy.requireValidUpload(MediaType.VIDEO, "video/mp4", 0L),
                ErrorCode.MEDIA_SIZE_INVALID
        );
        assertThat(ErrorCode.MEDIA_SIZE_INVALID.getStatus().value()).isEqualTo(413);
    }

    @Test
    void defaultsLegacyContentTypeToTheFormerCanonicalMime() {
        assertThat(ChatMediaPolicy.requireValidUpload(MediaType.IMAGE, null, 1L))
                .isEqualTo("image/jpeg");
        assertThat(ChatMediaPolicy.requireValidUpload(MediaType.VIDEO, " ", 1L))
                .isEqualTo("video/mp4");
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
