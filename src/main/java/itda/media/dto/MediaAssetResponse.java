package itda.media.dto;

import itda.media.domain.MediaAsset;
import itda.media.domain.MediaPurpose;
import itda.media.domain.MediaStatus;
import java.time.Instant;

public record MediaAssetResponse(
        Long mediaAssetId,
        MediaPurpose purpose,
        MediaStatus status,
        String viewUrl,
        Instant createdAt
) {

    public static MediaAssetResponse from(MediaAsset mediaAsset, String viewUrl) {
        return new MediaAssetResponse(
                mediaAsset.getId(),
                mediaAsset.getPurpose(),
                mediaAsset.getStatus(),
                viewUrl,
                mediaAsset.getCreatedAt()
        );
    }
}
