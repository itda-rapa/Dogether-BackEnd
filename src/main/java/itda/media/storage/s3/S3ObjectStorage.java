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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
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
