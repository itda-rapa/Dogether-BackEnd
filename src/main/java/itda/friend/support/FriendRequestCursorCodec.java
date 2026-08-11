package itda.friend.support;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public final class FriendRequestCursorCodec {

    private FriendRequestCursorCodec() {
    }

    public static CursorPayload decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8
            );
            int separator = raw.lastIndexOf('|');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw invalidCursor();
            }
            Instant requestedAt = Instant.parse(raw.substring(0, separator));
            Long requestId = Long.valueOf(raw.substring(separator + 1));
            if (requestId <= 0) {
                throw invalidCursor();
            }
            return new CursorPayload(requestedAt, requestId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    public static String encode(Long requestId, Instant requestedAt) {
        if (requestId == null || requestId <= 0 || requestedAt == null) {
            throw invalidCursor();
        }
        String raw = requestedAt + "|" + requestId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    public record CursorPayload(Instant requestedAt, Long requestId) {
    }
}
