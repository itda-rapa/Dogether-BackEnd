package itda.media.dto.uploaddto;

import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;

import java.time.Instant;
import java.util.Map;

public record MediaResponse(
        Long id,
        MediaType mediaType,
        String path,
        MediaStatus status,
        Long userId,
        Long fileSize,
        Map<String, Object> attributes,
        Instant createdAt,
        Instant modifiedAt
) {
    public static MediaResponse from(Media media) {
        return new MediaResponse(
                media.getId(),
                media.getMediaType(),
                media.getPath(),
                media.getStatus(),
                media.getUserId(),
                media.getFileSize(),
                media.getAttributes(),
                media.getCreatedAt(),
                media.getUpdatedAt()
        );
    }
}