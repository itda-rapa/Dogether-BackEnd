package itda.report.dto;

import itda.chat.domain.ChatRoom;
import java.time.Instant;
import java.util.List;

public record AdminReportRoomEvidence(
        Long roomId,
        String status,
        List<Long> participantPetIds,
        Instant createdAt
) {

    public static AdminReportRoomEvidence from(ChatRoom room, List<Long> participantPetIds) {
        return new AdminReportRoomEvidence(
                room.getId(),
                room.getStatus().name(),
                List.copyOf(participantPetIds),
                room.getCreatedAt()
        );
    }
}
