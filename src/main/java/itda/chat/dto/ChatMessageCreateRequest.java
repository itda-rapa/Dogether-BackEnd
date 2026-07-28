package itda.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of a user-authored chat message, mirroring the {@code ChatMessageCreateRequest} schema in
 * the M1 OpenAPI contract.
 *
 * <p>Only these two fields are accepted. The room comes from the URL path and the sender from the
 * caller's authenticated Active Pet, so a client cannot address another room or impersonate another
 * Pet by editing the payload. Message type is absent for the same reason: TEXT is the only type a
 * user may send (최신 제품 정책 §4).
 */
public record ChatMessageCreateRequest(
        @NotBlank @Size(max = 64) String clientMessageId,
        @NotBlank @Size(max = 2000) String body
) {}
