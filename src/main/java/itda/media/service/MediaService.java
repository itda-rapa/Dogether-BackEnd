package itda.media.service;

import itda.common.properties.S3Properties;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.dto.uploaddto.MultipartUploadInfo;
import itda.media.dto.uploaddto.MultipartUploaded;
import itda.media.dto.uploaddto.PresignedUrl;
import itda.media.repository.MediaRepository;
import itda.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaService {
    private static final long MULTIPART_THRESHOLD = 8 * 1024 * 1024; // 8MB = 8,388,608
    //
    private final MediaRepository mediaRepository;
    private final MultipartService multipartService;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;


    // 파일 업로드를 수행하는 메서드
    public PresignedUrl initMedia(
            MediaType mediaType,
            Long fileSize,
            User user,
            String subPath
    ){
        // 해당 MediaType의 확장자를 반환
        // MediaType : IMAGE인 경우 filename : UUID.jpg
        String filename = UUID.randomUUID() + mediaType.fileExtension();
        // Object Storage 상 미디어파일 저장경로 생성
        // ex ) RustFS 상 users/1/posts/UUID.jpg로 저장
        String path = "users/%s/%s/%s".formatted(
                user.getId(),
                subPath,
                filename
        );
        // 파일크기에 따라서 단일업로드 / 멀티파트 업로드 결정
        if (fileSize != null
                && fileSize > MULTIPART_THRESHOLD)
            return initMultipartUpload(user, path, mediaType, fileSize);
        return initSingleUpload(user,path,mediaType,fileSize);
    }

    private PresignedUrl initSingleUpload(
            User user,
            String path,
            MediaType mediaType,
            Long fileSize
    ) {
        // RustFS의 버킷정보 및 initMedia()에서 정의한 Path 정보와 MediaType 정보를 전달
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(s3Properties.bucket()) // RustFS에 저장할 버킷명
                .key(path) // 경로 및 이름을 정의한 저장될 파일명
                .contentType(mediaType.contentType()) // 파일의 MIME 타입
                .build();
        // RustFS에 전달할 PresignedURL에 대한 요청을 생성하기 위해 PutObjectRequest 객체 생성
        // PresignedUrl의 유효기간에 관한 설정도 추가
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(s3Properties.presignedUrlExpirationSeconds()))
                .putObjectRequest(putObjectRequest)
                .build();
        // S3Presigner 객체를 통해 RustFS에 요청을 전달하여 PresignedUrl를 수신
        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
        String presignedUrl = presignedRequest.url().toString();
        // INIT 상태의 Media 객체 생성
        Media media = mediaRepository.save(
                new Media(
                        mediaType,
                        path,
                        user.getId(),
                        fileSize
                )
        );
        // PresinedUrl을 Client에게 반환
        return PresignedUrl.forSingleUpload(media, presignedUrl);
    }

    private PresignedUrl initMultipartUpload(User user, String path, MediaType mediaType, long fileSize) {
        // 멀티파트 업로드를 수행하기 위해 RustFS에 요청을 전달하여 복수의 PresignedUrl를 수신
        MultipartUploadInfo uploadInfo = multipartService.initMultipartUpload(
                path,
                mediaType.contentType(),
                fileSize
        );
        // INIT 상태의 Media 객체 생성
        Media media = mediaRepository.save(
                new Media(
                        mediaType,
                        path,
                        user.getId(),
                        fileSize,
                        uploadInfo.uploadId()
                )
        );
        return PresignedUrl.forMultipartUpload(media, uploadInfo.uploadId(), uploadInfo.presignedUrlParts());
    }

    public Media mediaUploaded(
            Long mediaId,
            List<MultipartUploaded> parts,
            User user
    ) {
        Media media = mediaRepository.findByIdAndDeletedAtIsNullOrThrow(mediaId);
        // Media가 본인 소유가 아니면 다운로드 차단
        if (!media.getUserId().equals(user.getId())) {
            throw new IllegalArgumentException("You are not authorized to update this media");
        }
        // Media가 INIT 상태 인 경우에만 허용
        if (media.getStatus() != MediaStatus.INIT) {
            throw new IllegalArgumentException("Media is not in INIT status");
        }
        // 멀티파트 업로드인 경우 S3에 작업완료를 지시 ( 단일 업로드 인 경우 생략 )
        if (media.getUploadId() != null && !CollectionUtils.isEmpty(parts)) {
            // completeMultipartUpload()를 호출하여 S3에 업로드 완료를 알림
            // S3에서 백엔드로부터 API 수신 시 업로드된 파일을 병합 시작
            multipartService.completeMultipartUpload(media.getPath(), media.getUploadId(), parts);
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("parts", parts);
            media.updateAttributes(attributes);
        }
        // Media 객체 상태를 UPLOADED로 전환
        media.updateStatus(MediaStatus.UPLOADED);
        // Media 객체 저장
        return mediaRepository.save(media);
    }

    public String getPresignedUrl(Long id) {
        Media foundedMedia = mediaRepository.findByIdAndDeletedAtIsNullOrThrow(id);
        // Media가 INIT & FAILED 상태 인 경우 비허용
        if (foundedMedia.getStatus() == MediaStatus.INIT
                || foundedMedia.getStatus() == MediaStatus.FAILED
        ) {
            throw new IllegalArgumentException("Media is in FAILED or INIT status");
        }
        // 다운로드할 미디어 파일의 요청객체를 생성
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Properties.bucket()) // 다운로드할 파일의 버킷
                .key(foundedMedia.getPath()) // 다운로드할 파일의 경로/파일.확장자
                .build();
        // `GetObjectRequest`와 `URL 만료시간`을 지정하여 다운로드 PresignedURL을 요청하는 `객체` 생성
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(s3Properties.presignedUrlExpirationSeconds()))
                .getObjectRequest(getObjectRequest)
                .build();
        // GetObjectPresignRequest에 서명을 추가해서 RustFS에 전달함으로써 다운로드 URL을 정의하는 PresignedURL을 생성
        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }
}