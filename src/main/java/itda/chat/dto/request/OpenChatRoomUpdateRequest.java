package itda.chat.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OpenChatRoomUpdateRequest(
        @NotBlank
        @Size(max = 120)
        String title,

        @Size(max = 500)
        String description,

        @NotNull
        @Min(2)
        @Max(1000)
        Integer maxParticipants,

        @NotNull
        Boolean isPublic
) {
}
