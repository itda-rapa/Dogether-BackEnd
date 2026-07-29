package itda.media.dto.uploaddto;

import java.util.List;

public record MediaUploadedRequest(
        Long mediaId,
        List<MultipartUploaded> parts
) {
}