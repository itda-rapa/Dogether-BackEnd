//package itda.media.service;
//
//import itda.common.constants.ErrorCode;
//import itda.common.exception.BusinessException;
//import itda.common.properties.MediaProperties;
//import itda.media.old.domain.MediaAsset;
//import itda.media.domain.MediaStatus;
//import itda.media.old.dto.MediaAssetResponse;
//import itda.media.old.dto.MediaUploadRequest;
//import itda.media.old.dto.MediaUploadResponse;
//import itda.media.repository.MediaAssetRepository;
//import itda.media.old.domain.MediaPurpose;
//import itda.user.domain.Role;
//import itda.user.domain.User;
//import itda.user.repository.UserRepository;
//import java.time.Clock;
//import java.time.Instant;
//import java.util.Locale;
//import java.util.UUID;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
//import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
//import software.amazon.awssdk.services.s3.model.S3Exception;
//
//@Service
//public class MediaService {
//
//    private final MediaAssetRepository mediaAssetRepository;
//    private final UserRepository userRepository;
//    private final S3StorageService storageService;
//    private final MediaPolicy mediaPolicy;
//    private final MediaProperties properties;
//    private final Clock clock = Clock.systemUTC();
//
//    public MediaService(
//            MediaAssetRepository mediaAssetRepository,
//            UserRepository userRepository,
//            S3StorageService storageService,
//            MediaPolicy mediaPolicy,
//            MediaProperties properties
//    ) {
//        this.mediaAssetRepository = mediaAssetRepository;
//        this.userRepository = userRepository;
//        this.storageService = storageService;
//        this.mediaPolicy = mediaPolicy;
//        this.properties = properties;
//    }
//
//    @Transactional
//    public MediaUploadResponse createUpload(Long userId, MediaUploadRequest request) {
//        mediaPolicy.validate(
//                request.purpose(),
//                request.contentType(),
//                request.sizeBytes()
//        );
//
//        User owner = userRepository.findById(userId)
//                .filter(User::isActive)
//                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));
//        if (request.purpose() == MediaPurpose.SETLOG
//                && owner.getRole() == Role.USER) {
//            throw new BusinessException(ErrorCode.MEDIA_PURPOSE_FORBIDDEN);
//        }
//        Instant expiresAt = clock.instant().plus(properties.uploadUrlTtl());
//        String objectKey = generateObjectKey(userId, request);
//
//        MediaAsset mediaAsset = mediaAssetRepository.save(
//                MediaAsset.pending(
//                        owner,
//                        request.purpose(),
//                        objectKey,
//                        request.contentType(),
//                        request.sizeBytes(),
//                        expiresAt
//                )
//        );
//
//        String uploadUrl = storageService.createUploadUrl(
//                objectKey,
//                request.contentType(),
//                request.sizeBytes(),
//                properties.uploadUrlTtl()
//        );
//
//        return new MediaUploadResponse(mediaAsset.getId(), uploadUrl, expiresAt);
//    }
//
//    @Transactional(noRollbackFor = BusinessException.class)
//    public MediaAssetResponse complete(Long userId, Long mediaAssetId) {
//        MediaAsset mediaAsset = ownedAssetForUpdate(userId, mediaAssetId);
//        if (mediaAsset.getStatus() != MediaStatus.PENDING) {
//            throw new BusinessException(ErrorCode.MEDIA_STATE_CONFLICT);
//        }
//        if (!mediaAsset.getExpiresAt().isAfter(clock.instant())) {
//            throw new BusinessException(ErrorCode.MEDIA_EXPIRED);
//        }
//
//        HeadObjectResponse object;
//        try {
//            object = storageService.head(mediaAsset.getObjectKey());
//        } catch (NoSuchKeyException exception) {
//            throw new BusinessException(ErrorCode.MEDIA_NOT_UPLOADED);
//        } catch (S3Exception exception) {
//            if (exception.statusCode() == 404) {
//                throw new BusinessException(ErrorCode.MEDIA_NOT_UPLOADED);
//            }
//            throw exception;
//        }
//
//        if (object.contentLength() != mediaAsset.getSizeBytes()
//                || !mediaAsset.getContentType().equalsIgnoreCase(object.contentType())) {
//            throw new BusinessException(ErrorCode.MEDIA_NOT_UPLOADED);
//        }
//
//        mediaAsset.markUploaded();
//        return MediaAssetResponse.from(
//                mediaAsset,
//                storageService.createViewUrl(
//                        mediaAsset.getObjectKey(),
//                        properties.viewUrlTtl()
//                )
//        );
//    }
//
//    @Transactional(readOnly = true)
//    public MediaAssetResponse get(Long userId, Long mediaAssetId) {
//        MediaAsset mediaAsset = ownedAsset(userId, mediaAssetId);
//        if (mediaAsset.getStatus() == MediaStatus.DELETE_REQUESTED
//                || mediaAsset.getStatus() == MediaStatus.DELETED) {
//            throw new BusinessException(ErrorCode.MEDIA_NOT_FOUND);
//        }
//        String viewUrl = mediaAsset.getStatus() == MediaStatus.UPLOADED
//                ? storageService.createViewUrl(
//                        mediaAsset.getObjectKey(),
//                        properties.viewUrlTtl()
//                )
//                : null;
//        return MediaAssetResponse.from(mediaAsset, viewUrl);
//    }
//
//    @Transactional
//    public void requestDeletion(Long userId, Long mediaAssetId) {
//        ownedAssetForUpdate(userId, mediaAssetId).requestDeletion();
//    }
//
//    private MediaAsset ownedAsset(Long userId, Long mediaAssetId) {
//        MediaAsset mediaAsset = mediaAssetRepository.findById(mediaAssetId)
//                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));
//        if (!mediaAsset.belongsTo(userId)) {
//            throw new BusinessException(ErrorCode.MEDIA_NOT_OWNED);
//        }
//        return mediaAsset;
//    }
//
//    private MediaAsset ownedAssetForUpdate(Long userId, Long mediaAssetId) {
//        MediaAsset mediaAsset = mediaAssetRepository.findByIdForUpdate(mediaAssetId)
//                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));
//        if (!mediaAsset.belongsTo(userId)) {
//            throw new BusinessException(ErrorCode.MEDIA_NOT_OWNED);
//        }
//        return mediaAsset;
//    }
//
//    private String generateObjectKey(Long userId, MediaUploadRequest request) {
//        return "media/%d/%s/%s".formatted(
//                userId,
//                request.purpose().name().toLowerCase(Locale.ROOT),
//                UUID.randomUUID()
//        );
//    }
//}
