package itda.media.controller;

import itda.common.dto.ApiResponse;
import itda.media.domain.Media;
import itda.media.dto.downloaddto.PresignedUrlResponse;
import itda.media.dto.uploaddto.*;
import itda.media.repository.MediaRepository;
import itda.media.service.MediaService;
import itda.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MediaController {
    private final MediaService mediaService;
    private final MediaRepository mediaRepository;
    // ...

    @PostMapping("/api/v1/media/init")
    public ApiResponse<MediaInitResponse> initMedia(
            @AuthenticationPrincipal User user,
            @RequestBody MediaInitRequest request
    ) {
        PresignedUrl result = mediaService.initMedia(
                request.mediaType(),
                request.fileSize(),
                user,
                "posts"
        );
        return
                ApiResponse.created(
                        MediaInitResponse.from(result),
                        "Media 객체가 초기화되었습니다."
                )
                ;
    }

    @PostMapping("/api/v1/media/uploaded")
    public ApiResponse<MediaResponse> mediaUploaded(
            @AuthenticationPrincipal User user,
            @RequestBody MediaUploadedRequest request
    ) {
        Media media = mediaService.mediaUploaded(
                request.mediaId(),
                request.parts(),
                user
        );
        return ApiResponse.ok(
                MediaResponse.from(media),
                "성공적으로 업로드되었습니다."
        )
                ;
    }

    @GetMapping("/api/v1/media/{id}/presigned-url")
    public ApiResponse<PresignedUrlResponse> getPresignedUrl(@PathVariable Long id) {
        Media media = mediaRepository.findByIdAndDeletedAtIsNullOrThrow(id);
        String presignedUrl = mediaService.getPresignedUrl(id);
        return ApiResponse.ok(
                new PresignedUrlResponse(presignedUrl, MediaResponse.from(media)),
                "다운로드용 PresignedURL이 발급되었습니다."
        );
    }
}
