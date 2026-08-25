package itda.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record ChatRoomResponse(
        Long roomId,
        String status,
        @Schema(
                description = "DIRECT 방 생성 원천. 신규 Root 댓글 연결 방은 BOARD_COMMENT이다.",
                allowableValues = {"GREETING", "FRIEND", "BOARD_COMMENT", "OPEN_CHAT"}
        )
        String origin,
        PetSearchItem counterpartPet,
        boolean canSend,
        String sendBlockedReason,
        ChatMessageResponse lastMessage,
        Instant lastMessageAt,
        Instant updatedAt
) {
}
