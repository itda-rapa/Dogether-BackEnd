package itda.media.service;

import itda.common.properties.S3Properties;
import itda.media.dto.uploaddto.MultipartUploadInfo;
import itda.media.dto.uploaddto.MultipartUploaded;
import itda.media.dto.uploaddto.PresignedUrlPart;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
        CreateMultipartUploadResponse createResponse = s3Client.createMultipartUpload(createRequest);
        String uploadId = createResponse.uploadId();
        // 생성할 PresignedUrl의 수를 계산
        // 35MB 파일의 경우 ceil(35 / 8) = 5개
        int numberOfParts = (int) Math.ceil((double) fileSize / PART_SIZE);
        // 정의한 수 만큼 각 파트별 PresignedURL 생성 및 해당 리스트에 저장
        List<PresignedUrlPart> presignedUrlParts = new ArrayList<>();
        for (int partNumber = 1; partNumber <= numberOfParts; partNumber++) {
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(s3Properties.bucket())
                    .key(path)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();
            //
            UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(s3Properties.presignedUrlExpirationSeconds()))
                    .uploadPartRequest(uploadPartRequest)
                    .build();
            //
            PresignedUploadPartRequest presignedRequest = s3Presigner.presignUploadPart(presignRequest);
            String presignedUrl = presignedRequest.url().toString();
            //
            presignedUrlParts.add(new PresignedUrlPart(partNumber, presignedUrl));
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
        s3Client.completeMultipartUpload(completeRequest);
    }
}