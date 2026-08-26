package itda.media.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.MediaType;
import itda.media.storage.ObjectMetadata;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DIRECT와 Open Chat이 함께 사용하는 Chat 첨부 업로드 정책이다.
 *
 * <p>영상 길이는 객체 메타데이터만으로 신뢰성 있게 판별할 수 없으므로 이 정책에서
 * 클라이언트가 보낸 길이를 신뢰하지 않는다. 업로드 검증 파이프라인이 실제 컨테이너 길이를
 * 기록한 뒤 이 정책에 연결한다.</p>
 */
public final class ChatMediaPolicy {

    public static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_VIDEO_SIZE_BYTES = 50L * 1024 * 1024;
    public static final long MAX_VIDEO_DURATION_MILLIS = 5_000L;

    private static final Map<MediaType, Set<String>> ALLOWED_CONTENT_TYPES = Map.of(
            MediaType.IMAGE, Set.of("image/jpeg", "image/png", "image/webp"),
            MediaType.VIDEO, Set.of("video/mp4")
    );

    private ChatMediaPolicy() {
    }

    public static String requireValidUpload(MediaType mediaType, String contentType, Long fileSize) {
        if (mediaType == null) {
            throw new BusinessException(ErrorCode.INVALID_MEDIA_TYPE);
        }
        String normalizedContentType = normalizeContentType(contentType, mediaType);
        if (!ALLOWED_CONTENT_TYPES.get(mediaType).contains(normalizedContentType)) {
            throw new BusinessException(ErrorCode.INVALID_MEDIA_TYPE);
        }
        if (fileSize == null || fileSize <= 0 || fileSize > maxFileSize(mediaType)) {
            throw new BusinessException(ErrorCode.MEDIA_SIZE_INVALID);
        }
        return normalizedContentType;
    }

    /** Chat 전송 직전에도 정책을 다시 적용해 우회된 INIT 요청을 차단한다. */
    public static void requireValidPlayableMedia(Media media, MediaType expectedType) {
        if (media.getMediaType() != expectedType) {
            throw new BusinessException(ErrorCode.INVALID_MEDIA_TYPE);
        }
        requireValidUpload(media.getMediaType(), media.getContentType(), media.getFileSize());
    }

    /** 실제 object metadata는 init 요청의 선언값보다 우선한다. */
    public static void requireVerifiedObject(Media media, ObjectMetadata metadata) {
        if (metadata == null || metadata.size() != media.getFileSize()) {
            throw new BusinessException(ErrorCode.MEDIA_SIZE_INVALID);
        }
        String expectedContentType = normalizeContentType(media.getContentType(), media.getMediaType());
        if (metadata.contentType() == null || metadata.contentType().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_MEDIA_TYPE);
        }
        String actualContentType = metadata.contentType().trim().toLowerCase(Locale.ROOT);
        if (!expectedContentType.equals(actualContentType)) {
            throw new BusinessException(ErrorCode.INVALID_MEDIA_TYPE);
        }
        requireValidUpload(media.getMediaType(), actualContentType, metadata.size());
    }

    public static String normalizeContentType(String contentType, MediaType mediaType) {
        if (contentType == null || contentType.isBlank()) {
            return mediaType.contentType();
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    private static long maxFileSize(MediaType mediaType) {
        return mediaType == MediaType.IMAGE ? MAX_IMAGE_SIZE_BYTES : MAX_VIDEO_SIZE_BYTES;
    }
}
