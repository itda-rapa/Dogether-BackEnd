package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.MediaType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.math.BigInteger;
import java.util.Arrays;
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

    @Test
    void readsVideoDurationFromActualMp4MovieHeader() {
        assertThat(Mp4DurationExtractor.durationMillis(mp4WithDuration(5_000, 1_000)))
                .isEqualByComparingTo(BigInteger.valueOf(5_000));
        assertThat(Mp4DurationExtractor.durationMillis(mp4WithDuration(5_001, 1_000)))
                .isEqualByComparingTo(BigInteger.valueOf(5_001));
    }

    @Test
    void rejectsMalformedOrMissingMovieHeader() {
        assertThatThrownBy(() -> Mp4DurationExtractor.durationMillis(new byte[8]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Mp4DurationExtractor.durationMillis(box("ftyp", new byte[8])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] mp4WithDuration(long duration, long timescale) {
        ByteBuffer mvhdPayload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        mvhdPayload.putInt(0); // version 0 + flags
        mvhdPayload.putInt(0); // creation time
        mvhdPayload.putInt(0); // modification time
        mvhdPayload.putInt(Math.toIntExact(timescale));
        mvhdPayload.putInt(Math.toIntExact(duration));
        return join(box("ftyp", new byte[8]), box("moov", box("mvhd", mvhdPayload.array())));
    }

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer value = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        value.putInt(value.capacity());
        value.put(type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        value.put(payload);
        return value.array();
    }

    private static byte[] join(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        ByteBuffer result = ByteBuffer.allocate(length);
        for (byte[] value : values) {
            result.put(value);
        }
        return result.array();
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
