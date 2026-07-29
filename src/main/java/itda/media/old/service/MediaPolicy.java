//package itda.media.service;
//
//import itda.common.constants.ErrorCode;
//import itda.common.exception.BusinessException;
//import itda.common.properties.MediaProperties;
//import itda.media.old.domain.MediaPurpose;
//import java.util.Map;
//import java.util.Set;
//import org.springframework.stereotype.Component;
//
//@Component
//public class MediaPolicy {
//
//    private static final Map<MediaPurpose, Set<String>> ALLOWED_TYPES = Map.of(
//            MediaPurpose.PROFILE, Set.of("image/jpeg", "image/png", "image/webp"),
//            MediaPurpose.SETLOG, Set.of(
//                    "image/jpeg",
//                    "image/png",
//                    "image/webp",
//                    "video/mp4"
//            ),
//            MediaPurpose.FOURCUT_SOURCE, Set.of(
//                    "image/jpeg",
//                    "image/png",
//                    "image/webp"
//            ),
//            MediaPurpose.FOURCUT_RESULT, Set.of(
//                    "image/jpeg",
//                    "image/png",
//                    "image/webp"
//            )
//    );
//
//    private final MediaProperties properties;
//
//    public MediaPolicy(MediaProperties properties) {
//        this.properties = properties;
//    }
//
//    public void validate(MediaPurpose purpose, String contentType, long sizeBytes) {
//        if (!ALLOWED_TYPES.getOrDefault(purpose, Set.of()).contains(contentType)) {
//            throw new BusinessException(ErrorCode.INVALID_MEDIA_TYPE);
//        }
//        if (sizeBytes <= 0 || sizeBytes > properties.maxUploadBytes()) {
//            throw new BusinessException(ErrorCode.MEDIA_SIZE_INVALID);
//        }
//    }
//}
