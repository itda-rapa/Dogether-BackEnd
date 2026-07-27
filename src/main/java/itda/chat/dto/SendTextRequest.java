package itda.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A user-authored chat message. TEXT is the only type a user may send in M1
 * (최신 제품 정책 §4), so neither the message type nor the sender type is part of the payload.
 */
public record SendTextRequest(
        @NotNull Long roomId,
        @NotNull Long senderPetId,
        @NotBlank @Size(max = 2000) String body,
        @NotBlank @Size(max = 64) String clientMessageId
) {}
