package itda.chat.dto;

public record EnsureDirectRoomResult(
        Long roomId,
        boolean isNew
) {}