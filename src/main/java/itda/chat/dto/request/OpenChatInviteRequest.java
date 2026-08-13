package itda.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OpenChatInviteRequest(
        @NotNull @Positive Long targetPetId
) {
}
