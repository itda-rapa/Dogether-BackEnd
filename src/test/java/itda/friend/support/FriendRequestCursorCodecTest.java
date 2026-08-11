package itda.friend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class FriendRequestCursorCodecTest {

    private static final Instant REQUESTED_AT =
            Instant.parse("2026-07-30T10:00:00.123456Z");

    @Test
    void cursorIsUrlSafeAndRoundTrips() {
        String encoded = FriendRequestCursorCodec.encode(42L, REQUESTED_AT);

        FriendRequestCursorCodec.CursorPayload decoded =
                FriendRequestCursorCodec.decode(encoded);

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(decoded.requestId()).isEqualTo(42L);
        assertThat(decoded.requestedAt()).isEqualTo(REQUESTED_AT);
    }

    @Test
    void nullAndBlankCursorRepresentFirstPage() {
        assertThat(FriendRequestCursorCodec.decode(null)).isNull();
        assertThat(FriendRequestCursorCodec.decode(" ")).isNull();
    }

    @Test
    void rejectsMalformedCursorPayloads() {
        assertInvalid("not-base64!");
        assertInvalid(encoded("not-an-instant|1"));
        assertInvalid(encoded(REQUESTED_AT + "|0"));
        assertInvalid(encoded(REQUESTED_AT + "|-1"));
        assertInvalid(encoded(REQUESTED_AT + "|9223372036854775808"));
        assertInvalid(encoded(REQUESTED_AT + "|1|trailing"));
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> FriendRequestCursorCodec.decode(cursor))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private String encoded(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8)
        );
    }
}
