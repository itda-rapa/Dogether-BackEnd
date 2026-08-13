package itda.chat.dto.request;

import jakarta.validation.constraints.NotNull;

public record JoinChatRoomRequest(
        @NotNull Long roomId,
        @NotNull Long petId
) {
}
