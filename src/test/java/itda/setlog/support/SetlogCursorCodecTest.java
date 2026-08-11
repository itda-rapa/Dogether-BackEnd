package itda.setlog.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SetlogCursorCodecTest {

    @Test
    void cursorRoundTripsWithStableTieBreaker() {
        Instant createdAt = Instant.parse("2026-08-11T01:02:03.123456Z");

        String encoded = SetlogCursorCodec.encode(42L, createdAt);
        SetlogCursorCodec.CursorPayload decoded =
                SetlogCursorCodec.decode(encoded);

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(decoded.setlogId()).isEqualTo(42L);
        assertThat(decoded.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void nullAndBlankCursorMeanFirstPage() {
        assertThat(SetlogCursorCodec.decode(null)).isNull();
        assertThat(SetlogCursorCodec.decode("   ")).isNull();
    }

    @Test
    void malformedCursorReturnsValidationFailed() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> SetlogCursorCodec.decode("not-a-cursor")
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
