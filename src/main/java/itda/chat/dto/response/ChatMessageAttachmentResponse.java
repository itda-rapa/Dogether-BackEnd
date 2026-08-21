package itda.chat.dto.response;

import java.time.Instant;

/**
 * IMAGE/VIDEO 메시지의 media 첨부 표현. Presigned URL은 응답 시점에 발급하고 영구 저장하지 않는다.
 */
public record ChatMessageAttachmentResponse(
        Long mediaId,
        String mediaType,
        String contentType,
        Long fileSize,
        String url,
        Instant expiresAt
) {
}
