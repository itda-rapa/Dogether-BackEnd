package itda.chat.dto.response;

import java.time.Instant;

/**
 * 공유 Setlog의 media 요약. Presigned URL은 응답 시점에 발급한다.
 */
public record SetlogMediaResponse(
        Long mediaId,
        String mediaType,
        String url,
        Instant expiresAt
) {
}
