package itda.media.old.dto;

import java.time.Instant;

public record MediaUploadResponse(
        Long mediaAssetId,
        String uploadUrl,
        Instant expiresAt
) {
}
