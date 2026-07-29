package itda.block.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BlockCursorCodecTest {

    @Test
    void cursorIsUrlSafeAndRoundTrips() {
        Instant createdAt = Instant.parse("2026-07-29T10:00:00.123456Z");

        String encoded = BlockCursorCodec.encode(42L, createdAt);
        BlockCursorCodec.CursorPayload decoded = BlockCursorCodec.decode(encoded);

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(decoded.blockId()).isEqualTo(42L);
        assertThat(decoded.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void malformedCursorReturnsValidationFailed() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> BlockCursorCodec.decode("not-a-valid-cursor"));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
