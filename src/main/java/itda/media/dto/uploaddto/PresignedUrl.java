package itda.media.dto.uploaddto;

import itda.media.domain.Media;

import java.util.List;
import java.util.Map;

public record PresignedUrl(
        Media media,
        String presignedUrl,
        Map<String, String> presignedHeaders,
        String uploadId,
        List<PresignedUrlPart> presignedUrlParts
) {
    public static PresignedUrl forSingleUpload(
            Media media,
            String presignedUrl,
            Map<String, String> presignedHeaders
    ) {
        return new PresignedUrl(media, presignedUrl, presignedHeaders, null, null);
    }
    public static PresignedUrl forMultipartUpload(
            Media media,
            String uploadId,
            List<PresignedUrlPart> presignedUrlParts
    ) {
        return new PresignedUrl(media, null, Map.of(), uploadId, presignedUrlParts);
    }
}
