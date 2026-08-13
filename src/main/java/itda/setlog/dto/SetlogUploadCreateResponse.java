package itda.setlog.dto;

import itda.media.storage.PresignedUpload;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SetlogUploadCreateResponse(
        UUID uploadId,
        String uploadUrl,
        String objectKey,
        Map<String, String> headers,
        Instant expiresAt
) {
    public static SetlogUploadCreateResponse from(
            UUID uploadId,
            String objectKey,
            PresignedUpload presignedUpload
    ) {
        return new SetlogUploadCreateResponse(
                uploadId,
                presignedUpload.url(),
                objectKey,
                presignedUpload.headers(),
                presignedUpload.expiresAt()
        );
    }
}
