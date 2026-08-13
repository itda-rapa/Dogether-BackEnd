package itda.setlog.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SetlogUploadCompleteRequest(
        @NotNull UUID clientRequestId
) {
}
