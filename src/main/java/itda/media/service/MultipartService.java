package itda.media.service;

import itda.common.properties.S3Properties;
import itda.media.dto.uploaddto.MultipartUploadInfo;
import itda.media.dto.uploaddto.MultipartUploaded;
import itda.media.dto.uploaddto.PresignedUrlPart;
import itda.media.storage.StorageProviderRejectedException;
import itda.media.storage.StorageProviderUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MultipartService {
    private static final long PART_SIZE = 8 * 1024 * 1024; // 8MB
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;
    // ...
    public MultipartUploadInfo initMultipartUpload(String path, String contentType, long fileSize) {
        // 멀티파트 업로드를 위한 요청객체 생성
        CreateMultipartUploadRequest createRequest = CreateMultipartUploadRequest.builder()
                .bucket(s3Properties.bucket()) // RustFS에 저장할 버킷명
                .key(path) // 경로 및 이름을 정의한 저장될 파일명
                .contentType(contentType) // 파일의 MIME 타입
                .build();
        // S3에 멀티파트 업로드를 시작하도록 요청 전달 및 uploadId 수신
        CreateMultipartUploadResponse createResponse =
                call("createMultipartUpload", () -> s3Client.createMultipartUpload(createRequest));
        String uploadId = createResponse.uploadId();
        // 생성할 PresignedUrl의 수를 계산
        // 35MB 파일의 경우 ceil(35 / 8) = 5개
        int numberOfParts = (int) Math.ceil((double) fileSize / PART_SIZE);
        // 정의한 수 만큼 각 파트별 PresignedURL 생성 및 해당 리스트에 저장
        List<PresignedUrlPart> presignedUrlParts = new ArrayList<>();
        for (int partNumber = 1; partNumber <= numberOfParts; partNumber++) {
            long expectedPartSize = Math.min(PART_SIZE, fileSize - (long) (partNumber - 1) * PART_SIZE);
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(s3Properties.bucket())
                    .key(path)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    // 각 part 길이를 서명에 포함해 전체 선언 크기를 초과하는 업로드를 사전에 차단한다.
                    .contentLength(expectedPartSize)
                    .build();
            //
            UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(s3Properties.presignedUrlExpirationSeconds()))
                    .uploadPartRequest(uploadPartRequest)
                    .build();
            //
            // Presigner도 SDK 예외를 던질 수 있으므로 create/complete/abort와 같은 오류 계약을 적용한다.
            PresignedUploadPartRequest presignedRequest =
                    call("presignUploadPart", () -> s3Presigner.presignUploadPart(presignRequest));
            String presignedUrl = presignedRequest.url().toString();
            //
            presignedUrlParts.add(new PresignedUrlPart(
                    partNumber,
                    presignedUrl,
                    clientRequiredHeaders(presignedRequest.signedHeaders())
            ));
        }
        return new MultipartUploadInfo(uploadId, presignedUrlParts);
    }

    // 클라이언트에서 멀티파트 업로드가 완료된 경우 RustFS에
    public void completeMultipartUpload(String path, String uploadId, List<MultipartUploaded> parts) {
        // 각 파트의 파트번호와 ETag을 포함하는 CompletedPart를 생성
        List<CompletedPart> s3Parts = parts.stream()
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.eTag())
                        .build())
                .toList();
        // CompletedPart를 하나로 묶는 CompletedMultipartUpload를 생성
        CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder()
                .parts(s3Parts)
                .build();
        // 멀티파트 업로드 완료를 RustFS에게 알리는 CompleteMultipartUploadRequest 객체를 생성
        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket(s3Properties.bucket())
                .key(path)
                .uploadId(uploadId)
                .multipartUpload(completedMultipartUpload)
                .build();
        // 멀티파트 업로드 완료를 전달하는 API
        // 이후 RustFS는 업로드된 Part들을 하나의 파일로 병합
        call("completeMultipartUpload", () -> s3Client.completeMultipartUpload(completeRequest));
    }

    public void abortMultipartUpload(String path, String uploadId) {
        call("abortMultipartUpload", () -> s3Client.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                        .bucket(s3Properties.bucket())
                        .key(path)
                        .uploadId(uploadId)
                        .build()));
    }

    /**
     * 스토리지 장애를 재시도 가능(503)과 거절(502)로 구분해 호출자에게 전달한다.
     * 분류하지 않으면 raw S3Exception이 전역 handler에서 500으로 새어 나가 재시도 계약이 깨진다.
     */
    private static <T> T call(String operation, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (S3Exception exception) {
            int statusCode = exception.statusCode();
            if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
                throw new StorageProviderUnavailableException(operation, exception);
            }
            throw new StorageProviderRejectedException(operation, statusCode, exception);
        } catch (SdkException exception) {
            throw new StorageProviderUnavailableException(operation, exception);
        }
    }

    private static final Set<String> CLIENT_FORBIDDEN_HEADERS = Set.of(
            "host", "cookie", "set-cookie", "x-amz-security-token", "x-amz-signature"
    );

    private static Map<String, String> clientRequiredHeaders(Map<String, List<String>> signedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        signedHeaders.forEach((name, values) -> {
            if (name == null || CLIENT_FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            headers.put(name, String.join(",", values));
        });
        return Map.copyOf(headers);
    }
}
