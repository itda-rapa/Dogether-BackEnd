package itda.media.dto.downloaddto;

import itda.media.dto.uploaddto.MediaResponse;

public record PresignedUrlResponse(
        String presignedUrl,
        MediaResponse media
) {
}