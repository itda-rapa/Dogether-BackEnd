package itda.chat.dto;

import itda.chat.domain.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of a user-authored chat message, mirroring the typed-message schema in M3
 * ({@code 04_M3_API_상세명세.md} §6).
 *
 * <p>The room comes from the URL path and the sender from the caller's authenticated Active Pet, so a
 * client cannot address another room or impersonate another Pet. {@code type} discriminates the
 * payload: TEXT requires {@code body}; IMAGE/VIDEO require {@code mediaId}; SETLOG_SHARE requires
 * {@code setlogId}. CARD and SYSTEM are server-only and rejected in the service.
 *
 * <p>{@code type} is deliberately nullable: a legacy TEXT request that omits {@code type} (and
 * carries neither {@code mediaId} nor {@code setlogId}) is normalized to TEXT inside the service.
 * Explicit typed requests (IMAGE/VIDEO/SETLOG_SHARE) still require {@code type}.
 */
public record ChatMessageCreateRequest(
        @NotBlank @Size(max = 64) String clientMessageId,
        MessageType type,
        @Size(max = 2000) String body,
        Long mediaId,
        Long setlogId
) {

    /**
     * TEXT 전송을 위한 편의 생성자. 기존 M1/M2 계약과 테스트 호환을 유지한다.
     */
    public ChatMessageCreateRequest(String clientMessageId, String body) {
        this(clientMessageId, MessageType.TEXT, body, null, null);
    }
}
