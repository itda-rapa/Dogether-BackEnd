package itda.safety.support;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public final class SafetyCursorCodec {

    private SafetyCursorCodec() {
    }

    public record Cursor(Instant sortAt, long id) {
    }

    public static String encode(Instant sortAt, long id) {
        if (sortAt == null || id <= 0) {
            throw invalid();
        }
        String raw = sortAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw invalid();
            }
            Instant sortAt = Instant.parse(raw.substring(0, separator));
            long id = Long.parseLong(raw.substring(separator + 1));
            if (id <= 0) {
                throw invalid();
            }
            return new Cursor(sortAt, id);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
