package itda.media.dto.uploaddto;

import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record MediaInitResponse(
        Long id,
        MediaType mediaType,
        String path,
        MediaStatus status,
        Long userId,
        String presignedUrl,
        String uploadId,
        List<PresignedUrlPart> presignedUrlParts,
        Instant createdAt,
        Instant updatedAt
) {
    public static MediaInitResponse from(PresignedUrl presignedUrl) {
        LocalDateTime localDateTime = LocalDateTime.now();

        Media media = presignedUrl.media();
        return new MediaInitResponse(
                media.getId(),
                media.getMediaType(),
                media.getPath(),
                media.getStatus(),
                media.getUserId(),
                presignedUrl.presignedUrl(),
                presignedUrl.uploadId(),
                presignedUrl.presignedUrlParts(),
                media.getCreatedAt(),
                media.getUpdatedAt()
        );
    }
}
