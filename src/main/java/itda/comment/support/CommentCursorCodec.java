package itda.comment.support;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public final class CommentCursorCodec {

    private CommentCursorCodec() {
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
                throw invalid();
            }
            Instant createdAt = Instant.parse(raw.substring(0, separator));
            long id = Long.parseLong(raw.substring(separator + 1));
            if (id <= 0) {
                throw invalid();
            }
            return new CursorPayload(createdAt, id);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    public static String encode(Long commentId, Instant createdAt) {
        if (commentId == null || commentId <= 0 || createdAt == null) {
            throw invalid();
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (createdAt + "|" + commentId).getBytes(StandardCharsets.UTF_8)
                );
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    public record CursorPayload(Instant createdAt, Long commentId) {
    }
}
