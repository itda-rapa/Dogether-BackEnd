package itda.media.storage.s3;

import itda.common.properties.S3Properties;
import itda.media.storage.ObjectMetadata;
import itda.media.storage.ObjectNotFoundException;
import itda.media.storage.ObjectStorage;
import itda.media.storage.PresignedDownload;
import itda.media.storage.PresignedUpload;
import itda.media.storage.StorageProviderUnavailableException;
import itda.media.storage.StorageProviderRejectedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class S3ObjectStorage implements ObjectStorage {

    private static final Duration MAX_PRESIGN_TTL = Duration.ofDays(7);
    private static final Set<String> CLIENT_FORBIDDEN_HEADERS = Set.of(
            "host",
            "cookie",
            "set-cookie",
            "x-amz-security-token",
            "x-amz-signature"
    );

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties properties;

    @Override
    public PresignedUpload presignPut(
            String objectKey,
            String contentType,
            long expectedSize,
            Duration ttl
    ) {
        requireArguments(objectKey, ttl);
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (expectedSize <= 0) {
            throw new IllegalArgumentException("expectedSize must be positive");
        }
        try {
            var request = PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .contentType(contentType)
                    .contentLength(expectedSize)
                    .build();
            var presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .putObjectRequest(request)
                    .build());
            return new PresignedUpload(
                    presigned.url().toString(),
                    clientRequiredHeaders(presigned.signedHeaders()),
                    presigned.expiration());
        } catch (S3Exception exception) {
            throw classify("presignPut", exception);
        } catch (SdkException exception) {
            throw unavailable("presignPut", exception);
        }
    }

    @Override
    public PresignedDownload presignGet(String objectKey, String versionId, Duration ttl) {
        requireArguments(objectKey, ttl);
        try {
            var request = GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .versionId(normalizeVersionId(versionId))
                    .build();
            var presigned = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(request)
                    .build());
            return new PresignedDownload(presigned.url().toString(), presigned.expiration());
        } catch (S3Exception exception) {
            throw classify("presignGet", exception);
        } catch (SdkException exception) {
            throw unavailable("presignGet", exception);
        }
    }

    @Override
    public ObjectMetadata head(String objectKey, String versionId) {
        requireObjectKey(objectKey);
        try {
            var response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .versionId(normalizeVersionId(versionId))
                    .build());
            return new ObjectMetadata(
                    response.contentLength(),
                    response.contentType(),
                    response.eTag(),
                    response.lastModified(),
                    response.versionId());
        } catch (NoSuchKeyException exception) {
            throw new ObjectNotFoundException("head", exception);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ObjectNotFoundException("head", exception);
            }
            throw classify("head", exception);
        } catch (SdkException exception) {
            throw unavailable("head", exception);
        }
    }

    @Override
    public byte[] read(String objectKey, String versionId) {
        requireObjectKey(objectKey);
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .versionId(normalizeVersionId(versionId))
                    .build(), ResponseTransformer.toBytes()).asByteArray();
        } catch (NoSuchKeyException exception) {
            throw new ObjectNotFoundException("read", exception);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ObjectNotFoundException("read", exception);
            }
            throw classify("read", exception);
        } catch (SdkException exception) {
            throw unavailable("read", exception);
        }
    }

    @Override
    public void delete(String objectKey, String versionId) {
        requireObjectKey(objectKey);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .versionId(normalizeVersionId(versionId))
                    .build());
        } catch (NoSuchKeyException exception) {
            // DELETE is idempotent: an absent object is already in the desired state.
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw classify("delete", exception);
            }
        } catch (SdkException exception) {
            throw unavailable("delete", exception);
        }
    }

    @Override
    public void deleteAllVersions(String objectKey) {
        requireObjectKey(objectKey);
        try {
            String keyMarker = null;
            String versionIdMarker = null;
            List<ObjectIdentifier> allIdentifiers = new ArrayList<>();
            do {
                var response = s3Client.listObjectVersions(ListObjectVersionsRequest.builder()
                        .bucket(properties.bucket())
                        .prefix(objectKey)
                        .keyMarker(keyMarker)
                        .versionIdMarker(versionIdMarker)
                        .build());
                if (response == null) {
                    throw new StorageProviderUnavailableException(
                            "deleteAllVersions",
                            new IllegalStateException("Object storage returned no version listing"));
                }
                response.versions().stream()
                        .filter(version -> objectKey.equals(version.key()))
                        .map(version -> ObjectIdentifier.builder()
                                .key(version.key()).versionId(version.versionId()).build())
                        .forEach(allIdentifiers::add);
                response.deleteMarkers().stream()
                        .filter(marker -> objectKey.equals(marker.key()))
                        .map(marker -> ObjectIdentifier.builder()
                                .key(marker.key()).versionId(marker.versionId()).build())
                        .forEach(allIdentifiers::add);
                if (!response.isTruncated()) {
                    break;
                }
                keyMarker = response.nextKeyMarker();
                versionIdMarker = response.nextVersionIdMarker();
            } while (true);
            if (!allIdentifiers.isEmpty()) {
                deleteIdentifiers(allIdentifiers, "deleteAllVersions");
            } else {
                deleteAfterEmptyVersionListing(objectKey);
            }
        } catch (NoSuchKeyException exception) {
            // Idempotent deletion.
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw classify("deleteAllVersions", exception);
            }
        } catch (SdkException exception) {
            throw unavailable("deleteAllVersions", exception);
        }
    }

    private void deleteAfterEmptyVersionListing(String objectKey) {
        try {
            ObjectMetadata metadata = head(objectKey);
            String versionId = normalizeVersionId(metadata.versionId());
            if (versionId == null) {
                // An unversioned provider can legitimately return an empty listing.
                delete(objectKey);
            } else {
                // Avoid creating a delete marker when the provider can identify the
                // current version even though ListObjectVersions returned no rows.
                delete(objectKey, versionId);
            }
        } catch (ObjectNotFoundException exception) {
            // Idempotent deletion: the key disappeared between LIST and HEAD.
        }
    }

    @Override
    public void deleteAllVersionsExcept(String objectKey, String retainedVersionId) {
        requireObjectKey(objectKey);
        String retained = normalizeVersionId(retainedVersionId);
        if (retained == null) {
            throw new IllegalArgumentException("retainedVersionId must not be blank");
        }
        try {
            String keyMarker = null;
            String versionIdMarker = null;
            List<ObjectIdentifier> allIdentifiers = new ArrayList<>();
            do {
                var response = s3Client.listObjectVersions(ListObjectVersionsRequest.builder()
                        .bucket(properties.bucket())
                        .prefix(objectKey)
                        .keyMarker(keyMarker)
                        .versionIdMarker(versionIdMarker)
                        .build());
                if (response == null) {
                    throw new StorageProviderUnavailableException(
                            "deleteAllVersionsExcept",
                            new IllegalStateException("Object storage returned no version listing"));
                }
                response.versions().stream()
                        .filter(version -> objectKey.equals(version.key()))
                        .filter(version -> !retained.equals(version.versionId()))
                        .map(version -> ObjectIdentifier.builder()
                                .key(version.key()).versionId(version.versionId()).build())
                        .forEach(allIdentifiers::add);
                response.deleteMarkers().stream()
                        .filter(marker -> objectKey.equals(marker.key()))
                        .map(marker -> ObjectIdentifier.builder()
                                .key(marker.key()).versionId(marker.versionId()).build())
                        .forEach(allIdentifiers::add);
                if (!response.isTruncated()) {
                    break;
                }
                keyMarker = response.nextKeyMarker();
                versionIdMarker = response.nextVersionIdMarker();
            } while (true);
            if (!allIdentifiers.isEmpty()) {
                deleteIdentifiers(allIdentifiers, "deleteAllVersionsExcept");
            }
        } catch (NoSuchKeyException exception) {
            // Idempotent pruning.
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw classify("deleteAllVersionsExcept", exception);
            }
        } catch (SdkException exception) {
            throw unavailable("deleteAllVersionsExcept", exception);
        }
    }

    private void deleteIdentifiers(List<ObjectIdentifier> identifiers, String operation) {
        for (int start = 0; start < identifiers.size(); start += 1000) {
            int end = Math.min(start + 1000, identifiers.size());
            var response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(properties.bucket())
                    .delete(Delete.builder().objects(identifiers.subList(start, end)).quiet(true).build())
                    .build());
            if (response.hasErrors() && !response.errors().isEmpty()) {
                boolean transientFailure = response.errors().stream()
                        .map(error -> error.code() == null ? "" : error.code())
                        .anyMatch(S3ObjectStorage::isTransientDeleteError);
                IllegalStateException failure = new IllegalStateException(
                        "Object storage rejected one or more deletions");
                if (transientFailure) {
                    throw new StorageProviderUnavailableException(operation, failure);
                }
                throw new StorageProviderRejectedException(operation, 400, failure);
            }
        }
    }

    private static boolean isTransientDeleteError(String code) {
        return switch (code) {
            case "InternalError", "ServiceUnavailable", "SlowDown", "RequestTimeout",
                    "Throttling", "ThrottlingException" -> true;
            default -> false;
        };
    }

    private static void requireArguments(String objectKey, Duration ttl) {
        requireObjectKey(objectKey);
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (ttl.compareTo(MAX_PRESIGN_TTL) > 0) {
            throw new IllegalArgumentException("ttl must not exceed 7 days");
        }
    }

    private static void requireObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
    }

    private static StorageProviderUnavailableException unavailable(
            String operation,
            SdkException exception
    ) {
        return new StorageProviderUnavailableException(operation, exception);
    }

    private static RuntimeException classify(String operation, S3Exception exception) {
        int statusCode = exception.statusCode();
        if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return unavailable(operation, exception);
        }
        return new StorageProviderRejectedException(operation, statusCode, exception);
    }

    private static String normalizeVersionId(String versionId) {
        return versionId == null || versionId.isBlank() ? null : versionId;
    }

    private static Map<String, String> clientRequiredHeaders(
            Map<String, List<String>> signedHeaders
    ) {
        Map<String, String> requiredHeaders = new LinkedHashMap<>();
        signedHeaders.forEach((name, values) -> {
            String normalizedName = name.toLowerCase(Locale.ROOT);
            // Host is controlled by the URL/user agent and browsers forbid setting it.
            // Credential-bearing headers must never cross this boundary. Content-Length
            // is intentionally retained: browsers calculate it from the request body,
            // while non-browser clients must send the exact signed size.
            if (isClientForbiddenHeader(normalizedName)) {
                return;
            }
            if (values != null && !values.isEmpty()) {
                // RFC field-line combination semantics use comma separation. AWS signs
                // the combined value, so preserving order is required.
                requiredHeaders.put(name, String.join(",", values));
            }
        });
        return Map.copyOf(requiredHeaders);
    }

    private static boolean isClientForbiddenHeader(String normalizedName) {
        return CLIENT_FORBIDDEN_HEADERS.contains(normalizedName)
                || normalizedName.contains("authorization")
                || normalizedName.contains("credential");
    }
}
