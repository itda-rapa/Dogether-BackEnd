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

    /**
     * Reads a verified object version for server-side content validation.
     * Callers must impose a domain-specific size limit before invoking this method.
     */
    default byte[] read(String objectKey, String versionId) {
        throw new UnsupportedOperationException("Object reads are not supported");
    }

    /** Deletes an object. Missing objects are treated as an already successful deletion. */
    default void delete(String objectKey) {
        delete(objectKey, null);
    }

    /** Deletes a specific version when versionId is supplied. */
    void delete(String objectKey, String versionId);

    /**
     * Removes every version and delete marker for a key. Implementations for an
     * unversioned provider may delegate to {@link #delete(String)}.
     */
    default void deleteAllVersions(String objectKey) {
        delete(objectKey);
    }

    /** Deletes every version and marker except the retained, verified version. */
    default void deleteAllVersionsExcept(String objectKey, String retainedVersionId) {
        if (retainedVersionId == null || retainedVersionId.isBlank()) {
            throw new IllegalArgumentException("retainedVersionId must not be blank");
        }
        throw new UnsupportedOperationException("Version pruning is not supported");
    }
}
