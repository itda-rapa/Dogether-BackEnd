package itda.chat.support;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes/decodes the opaque cursor for {@code GET /chat/rooms} pagination.
 *
 * <p>Cursor payload: {@code {"v":1,"activityAt":"2026-07-28T09:10:00Z","roomId":75}}.
 * Encoded as Base64 URL-safe so it is safe in query parameters.
 */
public final class RoomCursorCodec {

    private static final Pattern PAYLOAD = Pattern.compile(
            "\\{\"v\":(\\d+),\"activityAt\":\"([^\"]*)\",\"roomId\":(\\d+)\\}");

    private RoomCursorCodec() {
    }

    public static String encode(long roomId, Instant activityAt) {
        String json = "{\"v\":1,\"activityAt\":\"" + activityAt + "\",\"roomId\":" + roomId + "}";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static CursorPayload decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String json;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            json = new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Matcher matcher = PAYLOAD.matcher(json);
        if (!matcher.matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        // The digit groups are unbounded, so a crafted cursor can carry a number too large for
        // int or long. That must read as a bad cursor, not as a server fault.
        int v;
        long roomId;
        try {
            v = Integer.parseInt(matcher.group(1));
            roomId = Long.parseLong(matcher.group(3));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (v != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String activityAt = matcher.group(2);
        CursorPayload payload = new CursorPayload(v, activityAt, roomId);
        // validate activityAt is a real timestamp — bad value must 400, not 500 in SQL
        payload.activityAtInstant();
        return payload;
    }

    public record CursorPayload(int v, String activityAt, long roomId) {

        public Instant activityAtInstant() {
            try {
                return Instant.parse(activityAt);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
    }
}