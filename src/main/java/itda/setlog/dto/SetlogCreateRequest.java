package itda.setlog.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SetlogCreateRequest(
        @NotNull
        @Positive
        Long mediaId,

        @Size(max = 500)
        String caption
) {

    public SetlogCreateRequest {
        if (caption != null) {
            caption = caption.trim();
            if (caption.isEmpty()) {
                caption = null;
            }
        }
    }
}
