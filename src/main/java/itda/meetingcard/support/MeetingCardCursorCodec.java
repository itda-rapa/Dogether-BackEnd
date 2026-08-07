package itda.meetingcard.support;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Cursor codec for the (meetAt ASC, cardId ASC) meeting-card list. */
public final class MeetingCardCursorCodec {

    private MeetingCardCursorCodec() {
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
            Instant meetAt = Instant.parse(raw.substring(0, separator));
            Long cardId = Long.valueOf(raw.substring(separator + 1));
            if (cardId <= 0) {
                throw invalidCursor();
            }
            return new CursorPayload(meetAt, cardId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    public static String encode(Long cardId, Instant meetAt) {
        if (cardId == null || cardId <= 0 || meetAt == null) {
            throw invalidCursor();
        }
        String raw = meetAt + "|" + cardId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    public record CursorPayload(Instant meetAt, Long cardId) {
    }
}
