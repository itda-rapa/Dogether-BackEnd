package itda.media.storage;

import java.time.Instant;

public record ObjectMetadata(
        long size,
        String contentType,
        String etag,
        Instant lastModified,
        String versionId
) {
}
