package itda.media.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.properties.S3Properties;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.dto.uploaddto.MultipartUploadInfo;
import itda.media.dto.uploaddto.MultipartUploaded;
import itda.media.dto.uploaddto.PresignedUrl;
import itda.media.repository.MediaRepository;
import itda.media.storage.ObjectMetadata;
import itda.media.storage.ObjectNotFoundException;
import itda.media.storage.ObjectStorage;
import itda.media.storage.PresignedUpload;
import itda.media.storage.StorageProviderRejectedException;
import itda.media.storage.StorageProviderUnavailableException;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private final UserRepository userRepository;
    private final ObjectStorage objectStorage;


    // 파일 업로드를 수행하는 메서드
    public PresignedUrl initMedia(
            MediaType mediaType,
            String contentType,
            Long fileSize,
            Long userId,
            String subPath
    ){
        User user = userRepository.findByIdOrThrow(userId);
        String normalizedContentType = ChatMediaPolicy.requireValidUpload(mediaType, contentType, fileSize);

        // 해당 MediaType의 확장자를 반환
        // MediaType : IMAGE인 경우 filename : UUID.jpg
        String filename = UUID.randomUUID() + mediaType.fileExtension(normalizedContentType);
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
            return initMultipartUpload(user, path, mediaType, normalizedContentType, fileSize);
        return initSingleUpload(user, path, mediaType, normalizedContentType, fileSize);
    }

    private PresignedUrl initSingleUpload(
            User user,
            String path,
            MediaType mediaType,
            String contentType,
            Long fileSize
    ) {
        // Content-Length를 서명에 포함해 선언한 크기와 다른 단일 PUT을 스토리지 단계에서 차단한다.
        PresignedUpload upload = objectStorage.presignPut(
                path,
                contentType,
                fileSize,
                Duration.ofSeconds(s3Properties.presignedUrlExpirationSeconds())
        );
        // INIT 상태의 Media 객체 생성
        Media media = mediaRepository.save(
                Media.initialized(
                        mediaType,
                        path,
                        user.getId(),
                        fileSize,
                        contentType
                )
        );
        // PresinedUrl을 Client에게 반환
        return PresignedUrl.forSingleUpload(media, upload.url(), upload.headers());
    }

    private PresignedUrl initMultipartUpload(User user, String path, MediaType mediaType,
                                             String contentType, long fileSize) {
        // 멀티파트 업로드를 수행하기 위해 RustFS에 요청을 전달하여 복수의 PresignedUrl를 수신
        MultipartUploadInfo uploadInfo;
        try {
            uploadInfo = multipartService.initMultipartUpload(path, contentType, fileSize);
        } catch (StorageProviderUnavailableException exception) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
        } catch (StorageProviderRejectedException exception) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_REJECTED);
        }
        // INIT 상태의 Media 객체 생성
        Media media = mediaRepository.save(
                Media.initializedMultipart(
                        mediaType,
                        path,
                        user.getId(),
                        fileSize,
                        uploadInfo.uploadId(),
                        contentType
                )
        );
        return PresignedUrl.forMultipartUpload(media, uploadInfo.uploadId(), uploadInfo.presignedUrlParts());
    }

    /**
     * Chat IMAGE/VIDEO 전송용 공개 검증 계약: 소유자(발신자의 Active Pet 소유 User)가 업로드한,
     * 재생 가능(UPLOADED/COMPLETED)하고 요청 MessageType과 일치하는 media만 통과시킨다.
     * Chat이 media 내부 상태를 직접 해석하지 않도록 이 메서드가 단일 검증 지점을 제공한다.
     */
    public Media requireOwnedPlayableMedia(Long mediaId, Long ownerUserId, MediaType expectedType) {
        Media media = mediaRepository.findByIdAndDeletedAtIsNull(mediaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));
        if (ownerUserId == null || !ownerUserId.equals(media.getUserId())) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_OWNED);
        }
        if (!isPlayable(media)) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_READY);
        }
        ChatMediaPolicy.requireValidPlayableMedia(media, expectedType);
        return media;
    }

    /**
     * 메시지 목록 hydration용 batch 계약. 재생 불가·삭제 media는 결과에서 빠진다.
     * 반환값은 mediaId를 key로 하므로 누락된 mediaId는 "접근 불가"로 처리할 수 있다.
     */
    public Map<Long, OwnedPresignedDownload> getMediaDownloadsByIds(Collection<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, OwnedPresignedDownload> result = new LinkedHashMap<>();
        for (Media media : mediaRepository.findAllById(mediaIds)) {
            if (isPlayable(media)) {
                result.put(media.getId(), new OwnedPresignedDownload(media, presignDownload(media)));
            }
        }
        return Map.copyOf(result);
    }

    private boolean isPlayable(Media media) {
        return media != null
                && media.getDeletedAt() == null
                && (media.getStatus() == MediaStatus.UPLOADED
                || media.getStatus() == MediaStatus.COMPLETED);
    }

    public Media mediaUploaded(
            Long mediaId,
            List<MultipartUploaded> parts,
            Long userId
    ) {
        User user = userRepository.findByIdOrThrow(userId);

        Media media = mediaRepository.findByIdAndDeletedAtIsNullOrThrow(mediaId);
        // Media가 본인 소유가 아니면 업로드 완료 처리를 차단한다.
        if (!media.getUserId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_OWNED);
        }
        // multipart 완료 이후 HEAD 일시 장애가 발생해도 INIT 상태에서 객체 HEAD로 복구한다.
        if (media.getStatus() != MediaStatus.INIT) {
            throw new BusinessException(ErrorCode.MEDIA_STATE_CONFLICT);
        }
        // 멀티파트 upload는 적어도 한 개의 완료 part가 있어야 한다.
        if (media.getUploadId() != null) {
            if (CollectionUtils.isEmpty(parts)) {
                rejectUpload(media, null);
                throw new BusinessException(ErrorCode.MEDIA_STATE_CONFLICT);
            }
            if (!objectExists(media.getPath())) {
                // completeMultipartUpload()를 호출하여 S3에 업로드 완료를 알림
                // S3에서 백엔드로부터 API 수신 시 업로드된 파일을 병합 시작
                try {
                    multipartService.completeMultipartUpload(media.getPath(), media.getUploadId(), parts);
                } catch (StorageProviderUnavailableException exception) {
                    throw new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
                } catch (StorageProviderRejectedException exception) {
                    throw new BusinessException(ErrorCode.MEDIA_STORAGE_REJECTED);
                }
                Map<String, Object> attributes = new HashMap<>();
                attributes.put("parts", parts);
                media.updateAttributes(attributes);
            }
        }
        ObjectMetadata metadata;
        try {
            metadata = headUploadedObject(media.getPath());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.MEDIA_NOT_UPLOADED) {
                rejectUpload(media, null);
            }
            throw exception;
        }
        try {
            ChatMediaPolicy.requireVerifiedObject(media, metadata);
        } catch (BusinessException exception) {
            rejectUpload(media, metadata);
            throw exception;
        }
        if (media.getMediaType() == MediaType.VIDEO) {
            verifyVideoDuration(media, metadata);
        }
        // 실제 object metadata 검증에 성공한 경우에만 재생 가능 상태로 전이한다.
        media.markUploadVerified(metadata, Instant.now());
        // Media 객체 저장
        return mediaRepository.save(media);
    }

    /**
     * 클라이언트가 주장하는 길이를 신뢰하지 않고 실제 저장된 MP4 movie header를 읽어
     * D-05가 확정한 5초 상한을 적용한다. Storage 일시 장애는 FAILED로 전이하지 않고
     * 그대로 호출자에게 전달해 안전하게 재시도할 수 있게 한다.
     */
    private void verifyVideoDuration(Media media, ObjectMetadata metadata) {
        byte[] content;
        try {
            content = objectStorage.read(media.getPath(), metadata.versionId());
        } catch (ObjectNotFoundException exception) {
            rejectUpload(media, metadata);
            throw new BusinessException(ErrorCode.MEDIA_NOT_UPLOADED);
        } catch (StorageProviderUnavailableException exception) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
        } catch (StorageProviderRejectedException exception) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_REJECTED);
        }
        try {
            ChatMediaPolicy.requireVerifiedVideoDuration(media, content);
        } catch (BusinessException exception) {
            rejectUpload(media, metadata);
            throw exception;
        }
    }

    private void rejectUpload(Media media, ObjectMetadata metadata) {
        media.updateStatus(MediaStatus.FAILED);
        mediaRepository.save(media);
        if (metadata == null && media.getUploadId() != null) {
            try {
                multipartService.abortMultipartUpload(media.getPath(), media.getUploadId());
            } catch (RuntimeException ignored) {
                // abort 실패 여부와 무관하게 Media는 재사용 불가능한 FAILED 상태로 남긴다.
            }
        }
        if (metadata != null) {
            try {
                objectStorage.delete(media.getPath(), metadata.versionId());
            } catch (RuntimeException ignored) {
                // delete 실패 여부와 무관하게 Media는 재사용 불가능한 FAILED 상태로 남긴다.
            }
        }
    }

    private ObjectMetadata headUploadedObject(String path) {
        try {
            return objectStorage.head(path);
        } catch (ObjectNotFoundException exception) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_UPLOADED);
        } catch (StorageProviderUnavailableException exception) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
        } catch (StorageProviderRejectedException exception) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_REJECTED);
        }
    }

    /**
     * 완료 요청 재시도에서 이미 병합된 객체가 있으면 CompleteMultipartUpload를 반복하지 않는다.
     * 스토리지 장애는 숨기지 않고 호출자에게 503으로 전달해 안전하게 재시도할 수 있게 한다.
     */
    private boolean objectExists(String path) {
        try {
            objectStorage.head(path);
            return true;
        } catch (ObjectNotFoundException exception) {
            return false;
        } catch (StorageProviderUnavailableException exception) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
        } catch (StorageProviderRejectedException exception) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_REJECTED);
        }
    }

    public String getPresignedUrl(Long id) {
        return getPresignedDownloadUrl(id).url();
    }

    public PresignedDownloadUrl getPresignedDownloadUrl(Long id) {
        Media foundedMedia = mediaRepository.findByIdAndDeletedAtIsNullOrThrow(id);
        validateDownloadable(foundedMedia);
        return presignDownload(foundedMedia);
    }

    public OwnedPresignedDownload getOwnedPresignedDownload(
            Long id,
            Long ownerUserId
    ) {
        Media media = mediaRepository.findByIdAndDeletedAtIsNull(id)
                .filter(candidate -> ownerUserId != null
                        && ownerUserId.equals(candidate.getUserId()))
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.MEDIA_NOT_FOUND)
                );
        validateDownloadable(media);
        return new OwnedPresignedDownload(media, presignDownload(media));
    }

    /**
     * Signs already-loaded media without repeating a repository lookup.
     * Every item receives the same status and soft-delete validation as the
     * single-item download path.
     */
    public Map<Long, PresignedDownloadUrl> getPresignedDownloadUrls(
            Collection<Media> mediaItems
    ) {
        if (mediaItems == null) {
            throw new IllegalArgumentException("Media items must not be null");
        }
        for (Media media : mediaItems) {
            validateDownloadable(media);
            if (media.getId() == null || media.getId() <= 0) {
                throw new IllegalArgumentException("Media must be persisted");
            }
        }
        Map<Long, PresignedDownloadUrl> result = new LinkedHashMap<>();
        for (Media media : mediaItems) {
            result.put(media.getId(), presignDownload(media));
        }
        return Map.copyOf(result);
    }

    private void validateDownloadable(Media media) {
        if (media == null || media.getDeletedAt() != null) {
            throw new IllegalArgumentException("Media is deleted or missing");
        }
        if (media.getStatus() != MediaStatus.UPLOADED
                && media.getStatus() != MediaStatus.COMPLETED) {
            throw new IllegalArgumentException("Media is not downloadable");
        }
    }

    private PresignedDownloadUrl presignDownload(Media foundedMedia) {
        // 다운로드할 미디어 파일의 요청객체를 생성
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Properties.bucket()) // 다운로드할 파일의 버킷
                .key(foundedMedia.getPath()) // 다운로드할 파일의 경로/파일.확장자
                .versionId(foundedMedia.getObjectVersionId())
                .build();
        // `GetObjectRequest`와 `URL 만료시간`을 지정하여 다운로드 PresignedURL을 요청하는 `객체` 생성
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(s3Properties.presignedUrlExpirationSeconds()))
                .getObjectRequest(getObjectRequest)
                .build();
        // GetObjectPresignRequest에 서명을 추가해서 RustFS에 전달함으로써 다운로드 URL을 정의하는 PresignedURL을 생성
        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return new PresignedDownloadUrl(
                presignedRequest.url().toString(),
                presignedRequest.expiration()
        );
    }

    public record PresignedDownloadUrl(
            String url,
            Instant expiresAt
    ) {
    }

    public record OwnedPresignedDownload(
            Media media,
            PresignedDownloadUrl download
    ) {
    }
}
