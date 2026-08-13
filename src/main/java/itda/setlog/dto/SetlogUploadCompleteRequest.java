package itda.setlog.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SetlogUploadCompleteRequest(
        @NotNull UUID clientRequestId,

        @Size(max = 500)
        String caption
) {

    public SetlogUploadCompleteRequest {
        if (caption != null) {
            caption = caption.trim();
            if (caption.isEmpty()) {
                caption = null;
            }
        }
    }
}
