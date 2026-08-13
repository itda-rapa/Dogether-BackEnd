package itda.chat.dto.request;

public record OpenChatMessageRequest(
        Long roomId,
        Long senderPetId,
        String message
) {
}
