package itda.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import itda.chat.domain.MessageType;
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
        @Schema(
                description = "클라이언트가 생성하는 방 단위 재시도 멱등 키",
                maxLength = 64,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @Size(max = 64) String clientMessageId,
        @Schema(
                description = "사용자 전송 타입. CARD/SYSTEM은 서버 전용이다. "
                        + "생략하면 body만 가진 legacy TEXT 요청으로 처리한다.",
                allowableValues = {"TEXT", "IMAGE", "VIDEO", "SETLOG_SHARE"}
        )
        MessageType type,
        @Schema(description = "TEXT에서 필수. IMAGE/VIDEO/SETLOG_SHARE에서는 null이어야 한다.", maxLength = 2000)
        @Size(max = 2000) String body,
        @Schema(description = "IMAGE/VIDEO에서 필수. 다른 타입에서는 null이어야 한다.")
        Long mediaId,
        @Schema(description = "SETLOG_SHARE에서 필수. 다른 타입에서는 null이어야 한다.")
        Long setlogId
) {

    /**
     * TEXT 전송을 위한 편의 생성자. 기존 M1/M2 계약과 테스트 호환을 유지한다.
     */
    public ChatMessageCreateRequest(String clientMessageId, String body) {
        this(clientMessageId, MessageType.TEXT, body, null, null);
    }
}
