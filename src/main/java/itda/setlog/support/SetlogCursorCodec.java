package itda.setlog.support;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public final class SetlogCursorCodec {

    private SetlogCursorCodec() {
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
            Instant createdAt = Instant.parse(raw.substring(0, separator));
            Long setlogId = Long.valueOf(raw.substring(separator + 1));
            if (setlogId <= 0) {
                throw invalidCursor();
            }
            return new CursorPayload(createdAt, setlogId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    public static String encode(Long setlogId, Instant createdAt) {
        if (setlogId == null || setlogId <= 0 || createdAt == null) {
            throw invalidCursor();
        }
        String raw = createdAt + "|" + setlogId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    public record CursorPayload(Instant createdAt, Long setlogId) {
    }
}
