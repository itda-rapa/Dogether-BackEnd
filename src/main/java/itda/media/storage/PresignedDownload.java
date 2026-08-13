package itda.media.storage;

import java.time.Instant;

public record PresignedDownload(String url, Instant expiresAt) {
}
