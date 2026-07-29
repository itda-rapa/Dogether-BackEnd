package itda.media.dto.uploaddto;

import java.util.List;

public record MultipartUploadInfo(
        String uploadId,
        List<PresignedUrlPart> presignedUrlParts
) {
}