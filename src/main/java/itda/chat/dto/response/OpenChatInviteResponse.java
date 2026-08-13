package itda.chat.dto.response;

public record OpenChatInviteResponse(
        Long roomId,
        Long targetPetId,
        boolean joined,
        long activeParticipants
) {
}
