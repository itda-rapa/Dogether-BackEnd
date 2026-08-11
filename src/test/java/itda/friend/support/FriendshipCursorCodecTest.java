package itda.friend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class FriendshipCursorCodecTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-30T10:00:00.123456Z");

    @Test
    void cursorIsUrlSafeAndRoundTrips() {
        String encoded = FriendshipCursorCodec.encode(42L, CREATED_AT);

        FriendshipCursorCodec.CursorPayload decoded =
                FriendshipCursorCodec.decode(encoded);

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(decoded.friendshipId()).isEqualTo(42L);
        assertThat(decoded.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void nullAndBlankCursorRepresentFirstPage() {
        assertThat(FriendshipCursorCodec.decode(null)).isNull();
        assertThat(FriendshipCursorCodec.decode(" ")).isNull();
    }

    @Test
    void rejectsMalformedCursorPayloads() {
        assertInvalid("not-base64!");
        assertInvalid(encoded("not-an-instant|1"));
        assertInvalid(encoded(CREATED_AT + "|0"));
        assertInvalid(encoded(CREATED_AT + "|-1"));
        assertInvalid(encoded(CREATED_AT + "|9223372036854775808"));
        assertInvalid(encoded(CREATED_AT + "|1|trailing"));
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> FriendshipCursorCodec.decode(cursor))
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
