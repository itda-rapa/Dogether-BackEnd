package itda.media.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import itda.common.properties.S3Properties;
import itda.media.storage.StorageProviderRejectedException;
import itda.media.storage.StorageProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

@ExtendWith(MockitoExtension.class)
class MultipartServiceTest {

    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;

    @Test
    void mapsPresignerSdkFailureToUnavailable() {
        MultipartService service = service();
        given(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .willReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
        given(s3Presigner.presignUploadPart(any(UploadPartPresignRequest.class)))
                .willThrow(SdkClientException.create("presigner unavailable"));

        assertThatThrownBy(() -> service.initMultipartUpload("users/1/image.png", "image/png", 1L))
                .isInstanceOf(StorageProviderUnavailableException.class)
                .hasMessageContaining("presignUploadPart");
    }

    @Test
    void mapsPresignerProviderRejectionToRejected() {
        MultipartService service = service();
        given(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .willReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
        given(s3Presigner.presignUploadPart(any(UploadPartPresignRequest.class)))
                .willThrow(S3Exception.builder().statusCode(403).build());

        assertThatThrownBy(() -> service.initMultipartUpload("users/1/image.png", "image/png", 1L))
                .isInstanceOf(StorageProviderRejectedException.class)
                .hasMessageContaining("presignUploadPart");
    }

    private MultipartService service() {
        return new MultipartService(
                s3Client,
                s3Presigner,
                new S3Properties("access", "secret", "bucket", "ap-northeast-2", 300L)
        );
    }
}
