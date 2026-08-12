package itda.media.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;

import itda.common.properties.S3Properties;
import itda.media.storage.ObjectNotFoundException;
import itda.media.storage.StorageProviderUnavailableException;
import itda.media.storage.StorageProviderRejectedException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageTest {

    private static final String BUCKET = "dogether-test";
    private static final String OBJECT_KEY = "setlogs/1/video.mp4";

    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;
    @Captor private ArgumentCaptor<PutObjectPresignRequest> putPresignCaptor;
    @Captor private ArgumentCaptor<HeadObjectRequest> headCaptor;
    @Captor private ArgumentCaptor<DeleteObjectRequest> deleteCaptor;
    @Captor private ArgumentCaptor<GetObjectPresignRequest> getPresignCaptor;

    private S3ObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3ObjectStorage(
                s3Client,
                s3Presigner,
                new S3Properties("access", "secret", BUCKET, "ap-northeast-2", 600L)
        );
    }

    @Test
    void presignPutUsesBucketKeyContentTypeAndTtl() throws Exception {
        Instant expiresAt = Instant.parse("2026-08-12T03:15:00Z");
        PresignedPutObjectRequest signed = mock(PresignedPutObjectRequest.class);
        given(signed.url()).willReturn(new URL("https://example.com/upload"));
        given(signed.expiration()).willReturn(expiresAt);
        given(signed.signedHeaders()).willReturn(Map.of(
                "Content-Type", List.of("video/mp4"),
                "Content-Length", List.of("1234")
        ));
        given(s3Presigner.presignPutObject(putPresignCaptor.capture())).willReturn(signed);

        var result = storage.presignPut(
                OBJECT_KEY,
                "video/mp4",
                1234L,
                Duration.ofMinutes(15)
        );

        PutObjectPresignRequest presignRequest = putPresignCaptor.getValue();
        assertThat(presignRequest.signatureDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(presignRequest.putObjectRequest().bucket()).isEqualTo(BUCKET);
        assertThat(presignRequest.putObjectRequest().key()).isEqualTo(OBJECT_KEY);
        assertThat(presignRequest.putObjectRequest().contentType()).isEqualTo("video/mp4");
        assertThat(presignRequest.putObjectRequest().contentLength()).isEqualTo(1234L);
        assertThat(result.url()).isEqualTo("https://example.com/upload");
        assertThat(result.headers()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Content-Type", "video/mp4",
                "Content-Length", "1234"
        ));
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void deleteAllVersionsRemovesVersionsAndDeleteMarkersForExactKey() {
        given(s3Client.listObjectVersions(any(software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest.class)))
                .willReturn(ListObjectVersionsResponse.builder()
                        .isTruncated(false)
                        .versions(ObjectVersion.builder().key(OBJECT_KEY).versionId("v1").build())
                        .deleteMarkers(DeleteMarkerEntry.builder().key(OBJECT_KEY).versionId("m1").build())
                        .build());
        given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .willReturn(DeleteObjectsResponse.builder().build());

        storage.deleteAllVersions(OBJECT_KEY);

        ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        then(s3Client).should().deleteObjects(captor.capture());
        assertThat(captor.getValue().delete().objects())
                .extracting(object -> object.versionId())
                .containsExactly("v1", "m1");
    }

    @Test
    void deleteAllVersionsExceptRetainsVerifiedVersionAndRemovesMarkers() {
        given(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .willReturn(ListObjectVersionsResponse.builder()
                        .isTruncated(false)
                        .versions(
                                ObjectVersion.builder().key(OBJECT_KEY).versionId("A-verified").build(),
                                ObjectVersion.builder().key(OBJECT_KEY).versionId("B-surplus").build(),
                                ObjectVersion.builder().key(OBJECT_KEY).versionId("C-surplus").build())
                        .deleteMarkers(
                                DeleteMarkerEntry.builder().key(OBJECT_KEY).versionId("marker-1").build(),
                                DeleteMarkerEntry.builder().key(OBJECT_KEY).versionId("marker-2").build())
                        .build());
        given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .willReturn(DeleteObjectsResponse.builder().build());

        storage.deleteAllVersionsExcept(OBJECT_KEY, "A-verified");

        ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        then(s3Client).should().deleteObjects(captor.capture());
        assertThat(captor.getValue().delete().objects())
                .extracting(object -> object.versionId())
                .containsExactly("B-surplus", "C-surplus", "marker-1", "marker-2")
                .doesNotContain("A-verified");
    }

    @Test
    void deleteAllVersionsFollowsPaginationMarkersAndFiltersPrefixCollisions() {
        given(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .willReturn(
                        ListObjectVersionsResponse.builder()
                                .isTruncated(true)
                                .nextKeyMarker(OBJECT_KEY)
                                .nextVersionIdMarker("v1")
                                .versions(
                                        ObjectVersion.builder().key(OBJECT_KEY).versionId("v1").build(),
                                        ObjectVersion.builder().key(OBJECT_KEY + ".bak").versionId("other").build())
                                .build(),
                        ListObjectVersionsResponse.builder()
                                .isTruncated(false)
                                .deleteMarkers(DeleteMarkerEntry.builder()
                                        .key(OBJECT_KEY).versionId("marker-2").build())
                                .build());
        given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .willReturn(DeleteObjectsResponse.builder().build());

        storage.deleteAllVersions(OBJECT_KEY);

        ArgumentCaptor<ListObjectVersionsRequest> pages =
                ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        then(s3Client).should(org.mockito.Mockito.times(2)).listObjectVersions(pages.capture());
        assertThat(pages.getAllValues().get(1).keyMarker()).isEqualTo(OBJECT_KEY);
        assertThat(pages.getAllValues().get(1).versionIdMarker()).isEqualTo("v1");
        ArgumentCaptor<DeleteObjectsRequest> deletes =
                ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        then(s3Client).should().deleteObjects(deletes.capture());
        assertThat(deletes.getValue().delete().objects())
                .extracting(identifier -> identifier.versionId())
                .containsExactly("v1", "marker-2");
    }

    @Test
    void deleteAllVersionsChunksMoreThanOneThousandIdentifiers() {
        List<ObjectVersion> versions = java.util.stream.IntStream.rangeClosed(1, 1001)
                .mapToObj(index -> ObjectVersion.builder()
                        .key(OBJECT_KEY).versionId("v" + index).build())
                .toList();
        given(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .willReturn(ListObjectVersionsResponse.builder()
                        .isTruncated(false).versions(versions).build());
        given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .willReturn(DeleteObjectsResponse.builder().build());

        storage.deleteAllVersions(OBJECT_KEY);

        ArgumentCaptor<DeleteObjectsRequest> deletes =
                ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        then(s3Client).should(org.mockito.Mockito.times(2)).deleteObjects(deletes.capture());
        assertThat(deletes.getAllValues())
                .extracting(request -> request.delete().objects().size())
                .containsExactly(1000, 1);
    }

    @Test
    void deleteAllVersionsRejectsPartialBatchErrorsWithoutLeakingKey() {
        given(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .willReturn(ListObjectVersionsResponse.builder()
                        .isTruncated(false)
                        .versions(ObjectVersion.builder().key(OBJECT_KEY).versionId("v1").build())
                        .build());
        given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .willReturn(DeleteObjectsResponse.builder()
                        .errors(software.amazon.awssdk.services.s3.model.S3Error.builder()
                                .key(OBJECT_KEY).versionId("v1")
                                .code("AccessDenied").message("denied").build())
                        .build());

        assertThatThrownBy(() -> storage.deleteAllVersions(OBJECT_KEY))
                .isInstanceOf(StorageProviderRejectedException.class)
                .hasMessageNotContaining(OBJECT_KEY);
    }

    @Test
    void mixedDeleteErrorsPreferRetryableUnavailableClassification() {
        given(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .willReturn(ListObjectVersionsResponse.builder()
                        .isTruncated(false)
                        .versions(ObjectVersion.builder().key(OBJECT_KEY).versionId("v1").build())
                        .build());
        given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .willReturn(DeleteObjectsResponse.builder().errors(
                        software.amazon.awssdk.services.s3.model.S3Error.builder()
                                .code("AccessDenied").build(),
                        software.amazon.awssdk.services.s3.model.S3Error.builder()
                                .code("SlowDown").build()).build());

        assertThatThrownBy(() -> storage.deleteAllVersions(OBJECT_KEY))
                .isInstanceOf(StorageProviderUnavailableException.class)
                .hasMessageNotContaining(OBJECT_KEY);
    }

    @Test
    void paginationFailureIsUnavailableAndDoesNotContinueDeleting() {
        given(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .willReturn(ListObjectVersionsResponse.builder()
                        .isTruncated(true)
                        .nextKeyMarker(OBJECT_KEY)
                        .nextVersionIdMarker("v1")
                        .versions(ObjectVersion.builder().key(OBJECT_KEY).versionId("v1").build())
                        .build())
                .willThrow(SdkClientException.create("network"));
        assertThatThrownBy(() -> storage.deleteAllVersions(OBJECT_KEY))
                .isInstanceOf(StorageProviderUnavailableException.class)
                .hasMessageNotContaining(OBJECT_KEY);
        then(s3Client).should(org.mockito.Mockito.never())
                .deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void deleteAllVersionsRetriesMixedErrorsWhenAnyErrorIsTransient() {
        given(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .willReturn(ListObjectVersionsResponse.builder()
                        .isTruncated(false)
                        .versions(ObjectVersion.builder().key(OBJECT_KEY).versionId("v1").build())
                        .build());
        given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .willReturn(DeleteObjectsResponse.builder()
                        .errors(
                                software.amazon.awssdk.services.s3.model.S3Error.builder()
                                        .code("AccessDenied").build(),
                                software.amazon.awssdk.services.s3.model.S3Error.builder()
                                        .code("SlowDown").build())
                        .build());

        assertThatThrownBy(() -> storage.deleteAllVersions(OBJECT_KEY))
                .isInstanceOf(StorageProviderUnavailableException.class)
                .hasMessageNotContaining(OBJECT_KEY);
    }

    @Test
    void headMapsProviderMetadataWithoutLeakingSdkTypes() {
        Instant lastModified = Instant.parse("2026-08-12T02:00:00Z");
        given(s3Client.headObject(headCaptor.capture())).willReturn(
                HeadObjectResponse.builder()
                        .contentLength(1234L)
                        .contentType("video/mp4")
                        .eTag("etag-value")
                        .lastModified(lastModified)
                        .versionId("version-1")
                        .build()
        );

        var metadata = storage.head(OBJECT_KEY);

        assertThat(headCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(headCaptor.getValue().key()).isEqualTo(OBJECT_KEY);
        assertThat(metadata.size()).isEqualTo(1234L);
        assertThat(metadata.contentType()).isEqualTo("video/mp4");
        assertThat(metadata.etag()).isEqualTo("etag-value");
        assertThat(metadata.lastModified()).isEqualTo(lastModified);
        assertThat(metadata.versionId()).isEqualTo("version-1");
    }

    @Test
    void headMaps404ToObjectNotFound() {
        given(s3Client.headObject(org.mockito.ArgumentMatchers.any(HeadObjectRequest.class)))
                .willThrow(s3Error(404));

        assertThatThrownBy(() -> storage.head(OBJECT_KEY))
                .isInstanceOf(ObjectNotFoundException.class)
                .hasMessageNotContaining(OBJECT_KEY);
    }

    @Test
    void headMapsProviderAndTransportErrorsToUnavailable() {
        given(s3Client.headObject(org.mockito.ArgumentMatchers.any(HeadObjectRequest.class)))
                .willThrow(s3Error(503));

        assertThatThrownBy(() -> storage.head(OBJECT_KEY))
                .isInstanceOf(StorageProviderUnavailableException.class)
                .hasMessageContaining("head");

        given(s3Client.headObject(org.mockito.ArgumentMatchers.any(HeadObjectRequest.class)))
                .willThrow(SdkClientException.create("connection failed"));

        assertThatThrownBy(() -> storage.head(OBJECT_KEY))
                .isInstanceOf(StorageProviderUnavailableException.class)
                .hasMessageContaining("head");
    }

    @Test
    void deleteUsesBucketAndKeyAndTreats404AsSuccess() {
        storage.delete(OBJECT_KEY);

        assertThat(deleteCaptor.getAllValues()).isEmpty();
        then(s3Client).should().deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(deleteCaptor.getValue().key()).isEqualTo(OBJECT_KEY);

        given(s3Client.deleteObject(org.mockito.ArgumentMatchers.any(DeleteObjectRequest.class)))
                .willThrow(s3Error(404));

        storage.delete(OBJECT_KEY);
    }

    @Test
    void deleteMapsNonRetryableFailureToRejected() {
        given(s3Client.deleteObject(org.mockito.ArgumentMatchers.any(DeleteObjectRequest.class)))
                .willThrow(s3Error(403));

        assertThatThrownBy(() -> storage.delete(OBJECT_KEY))
                .isInstanceOf(StorageProviderRejectedException.class)
                .hasMessageContaining("delete");
    }

    @Test
    void presignGetUsesBucketKeyAndTtl() throws Exception {
        Instant expiresAt = Instant.parse("2026-08-12T03:10:00Z");
        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        given(signed.url()).willReturn(new URI("https://example.com/view").toURL());
        given(signed.expiration()).willReturn(expiresAt);
        given(s3Presigner.presignGetObject(getPresignCaptor.capture()))
                .willReturn(signed);

        var result = storage.presignGet(
                OBJECT_KEY,
                "version-1",
                Duration.ofMinutes(10)
        );

        GetObjectPresignRequest request = getPresignCaptor.getValue();
        assertThat(request.signatureDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(request.getObjectRequest().bucket()).isEqualTo(BUCKET);
        assertThat(request.getObjectRequest().key()).isEqualTo(OBJECT_KEY);
        assertThat(request.getObjectRequest().versionId()).isEqualTo("version-1");
        assertThat(result.url()).isEqualTo("https://example.com/view");
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void versionIdIsForwardedToHeadAndDelete() {
        given(s3Client.headObject(headCaptor.capture()))
                .willReturn(HeadObjectResponse.builder().contentLength(1L).build());

        storage.head(OBJECT_KEY, "version-1");
        storage.delete(OBJECT_KEY, "version-1");

        then(s3Client).should().deleteObject(deleteCaptor.capture());
        assertThat(headCaptor.getValue().versionId()).isEqualTo("version-1");
        assertThat(deleteCaptor.getValue().versionId()).isEqualTo("version-1");
    }

    @Test
    void allowsSevenDayTtlAndRejectsLongerTtlBeforeCallingProvider() throws Exception {
        PresignedPutObjectRequest signed = mock(PresignedPutObjectRequest.class);
        given(signed.url()).willReturn(new URL("https://example.com/upload"));
        given(signed.signedHeaders()).willReturn(Map.of());
        given(s3Presigner.presignPutObject(
                org.mockito.ArgumentMatchers.any(PutObjectPresignRequest.class)))
                .willReturn(signed);

        storage.presignPut(OBJECT_KEY, "video/mp4", 1L, Duration.ofDays(7));

        assertThatThrownBy(() -> storage.presignPut(
                OBJECT_KEY, "video/mp4", 1L, Duration.ofDays(7).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7 days");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonPositiveExpectedSize(long size) {
        assertThatThrownBy(() -> storage.presignPut(
                OBJECT_KEY, "video/mp4", size, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedSize");
        then(s3Presigner).shouldHaveNoInteractions();
    }

    @Test
    void rejectsBlankKeysAndContentTypes() {
        assertThatThrownBy(() -> storage.presignPut(
                " ", "video/mp4", 1L, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.presignPut(
                OBJECT_KEY, " ", 1L, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.presignGet(" ", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.head(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403})
    void nonRetryableProviderErrorsAreRejectedWithoutLeakingObjectKey(int status) {
        given(s3Client.headObject(org.mockito.ArgumentMatchers.any(HeadObjectRequest.class)))
                .willThrow(s3Error(status));

        assertThatThrownBy(() -> storage.head(OBJECT_KEY))
                .isInstanceOf(StorageProviderRejectedException.class)
                .hasMessageNotContaining(OBJECT_KEY)
                .satisfies(error -> assertThat(
                        ((StorageProviderRejectedException) error).statusCode())
                        .isEqualTo(status));
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void retryableProviderErrorsAreUnavailableWithoutLeakingObjectKey(int status) {
        given(s3Client.headObject(org.mockito.ArgumentMatchers.any(HeadObjectRequest.class)))
                .willThrow(s3Error(status));

        assertThatThrownBy(() -> storage.head(OBJECT_KEY))
                .isInstanceOf(StorageProviderUnavailableException.class)
                .hasMessageNotContaining(OBJECT_KEY);
    }

    @Test
    void requiredHeadersUseSdkSignedHeadersAndFilterForbiddenValues() throws Exception {
        PresignedPutObjectRequest signed = mock(PresignedPutObjectRequest.class);
        given(signed.url()).willReturn(new URL("https://example.com/upload"));
        given(signed.signedHeaders()).willReturn(new java.util.LinkedHashMap<>(Map.of(
                "Content-Type", List.of("video/mp4"),
                "Content-Length", List.of("1234"),
                "X-Custom-Multi", List.of("first", "second"),
                "Host", List.of("example.com"),
                "Authorization", List.of("secret"),
                "Proxy-Authorization", List.of("secret"),
                "Cookie", List.of("secret"),
                "Set-Cookie", List.of("secret"),
                "X-Amz-Security-Token", List.of("secret"),
                "X-Amz-Signature", List.of("secret")
        )));
        given(s3Presigner.presignPutObject(
                org.mockito.ArgumentMatchers.any(PutObjectPresignRequest.class)))
                .willReturn(signed);

        var result = storage.presignPut(
                OBJECT_KEY, "video/mp4", 1234L, Duration.ofMinutes(1));

        assertThat(result.headers()).containsEntry("Content-Type", "video/mp4");
        assertThat(result.headers()).containsEntry("Content-Length", "1234");
        assertThat(result.headers()).containsEntry("X-Custom-Multi", "first,second");
        assertThat(result.headers().keySet())
                .noneMatch(name -> switch (name.toLowerCase(java.util.Locale.ROOT)) {
                    case "host", "authorization", "proxy-authorization", "cookie",
                            "set-cookie", "x-amz-security-token", "x-amz-signature" -> true;
                    default -> false;
                });
    }

    @Test
    void requiredHeadersMatchRealAwsPresignerSignedHeaders() {
        try (S3Presigner realPresigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("access", "secret")))
                .build()) {
            S3ObjectStorage realStorage = new S3ObjectStorage(
                    s3Client, realPresigner,
                    new S3Properties("access", "secret", BUCKET,
                            Region.AP_NORTHEAST_2.id(), 600L));

            var result = realStorage.presignPut(
                    OBJECT_KEY, "video/mp4", 1234L, Duration.ofMinutes(1));

            assertThat(headerValue(result.headers(), "Content-Type"))
                    .contains("video/mp4");
            assertThat(headerValue(result.headers(), "Content-Length"))
                    .contains("1234");
            assertThat(result.headers().keySet())
                    .noneMatch(name -> name.equalsIgnoreCase("host"));
        }
    }

    private static S3Exception s3Error(int statusCode) {
        return (S3Exception) S3Exception.builder()
                .statusCode(statusCode)
                .message("S3 error")
                .build();
    }

    private static java.util.Optional<String> headerValue(
            Map<String, String> headers,
            String expectedName
    ) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(expectedName))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
