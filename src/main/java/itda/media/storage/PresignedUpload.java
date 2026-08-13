package itda.media.storage;

import java.time.Instant;
import java.util.Map;

public record PresignedUpload(
        String url,
        Map<String, String> headers,
        Instant expiresAt
) {
    public PresignedUpload {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
