package itda.media.dto;

import java.time.Instant;

public record MediaUploadResponse(
        Long mediaAssetId,
        String uploadUrl,
        Instant expiresAt
) {
}
