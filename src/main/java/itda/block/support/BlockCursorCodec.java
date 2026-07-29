package itda.block.support;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public final class BlockCursorCodec {

    private BlockCursorCodec() {
    }

    public record CursorPayload(Instant createdAt, Long blockId) {
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
            Long blockId = Long.valueOf(raw.substring(separator + 1));
            if (blockId <= 0) {
                throw invalidCursor();
            }
            return new CursorPayload(createdAt, blockId);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalidCursor();
        }
    }

    public static String encode(Long blockId, Instant createdAt) {
        if (blockId == null || blockId <= 0 || createdAt == null) {
            throw invalidCursor();
        }
        String raw = createdAt + "|" + blockId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
