package itda.media.storage;

import java.time.Duration;

/**
 * Application-facing object storage contract. Provider SDK types must not cross
 * this boundary.
 */
public interface ObjectStorage {

    PresignedUpload presignPut(
            String objectKey,
            String contentType,
            long expectedSize,
            Duration ttl
    );

    default PresignedDownload presignGet(String objectKey, Duration ttl) {
        return presignGet(objectKey, null, ttl);
    }

    PresignedDownload presignGet(String objectKey, String versionId, Duration ttl);

    default ObjectMetadata head(String objectKey) {
        return head(objectKey, null);
    }

    ObjectMetadata head(String objectKey, String versionId);

    /** Deletes an object. Missing objects are treated as an already successful deletion. */
    default void delete(String objectKey) {
        delete(objectKey, null);
    }

    /** Deletes a specific version when versionId is supplied. */
    void delete(String objectKey, String versionId);
}
