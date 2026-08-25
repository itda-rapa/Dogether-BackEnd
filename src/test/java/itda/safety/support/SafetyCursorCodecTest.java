package itda.safety.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SafetyCursorCodecTest {

    @Test
    void roundTripsUrlSafeCursor() {
        Instant occurredAt = Instant.parse("2026-08-24T10:00:00Z");
        String encoded = SafetyCursorCodec.encode(occurredAt, 42L);

        assertThat(encoded).doesNotContain("=", "+", "/");
        assertThat(SafetyCursorCodec.decode(encoded))
                .isEqualTo(new SafetyCursorCodec.Cursor(occurredAt, 42L));
    }

    @Test
    void rejectsMalformedCursor() {
        assertThatThrownBy(() -> SafetyCursorCodec.decode("not-a-cursor"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
