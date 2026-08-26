package itda.media.dto.uploaddto;

import java.util.Map;

public record PresignedUrlPart(
        int partNumber,
        String presignedUrl,
        Map<String, String> headers
) {
    public PresignedUrlPart {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
