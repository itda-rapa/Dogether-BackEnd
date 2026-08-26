package itda.media.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.media.domain.Media;
import itda.media.dto.downloaddto.PresignedUrlResponse;
import itda.media.dto.uploaddto.*;
import itda.media.service.MediaService;
import itda.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MediaController implements MediaSwaggerSupporter {
    private final MediaService mediaService;
    // ...

    @PostMapping("/api/v1/media/init")
    public ApiResponse<MediaInitResponse> initMedia(
            @AuthenticationPrincipal CurrentUser user,
            @RequestBody MediaInitRequest request
    ) {
        PresignedUrl result = mediaService.initMedia(
                request.mediaType(),
                request.contentType(),
                request.fileSize(),
                user.id(),
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
            @AuthenticationPrincipal CurrentUser user,
            @RequestBody MediaUploadedRequest request
    ) {
        Media media = mediaService.mediaUploaded(
                request.mediaId(),
                request.parts(),
                user.id()
        );
        return ApiResponse.ok(
                MediaResponse.from(media),
                "성공적으로 업로드되었습니다."
        )
                ;
    }

    @GetMapping("/api/v1/media/{id}/presigned-url")
    public ResponseEntity<ApiResponse<PresignedUrlResponse>> getPresignedUrl(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long id
    ) {
        MediaService.OwnedPresignedDownload result =
                mediaService.getOwnedPresignedDownload(id, user.id());
        ApiResponse<PresignedUrlResponse> body = ApiResponse.ok(
                new PresignedUrlResponse(
                        result.download().url(),
                        MediaResponse.from(result.media())
                ),
                "다운로드용 PresignedURL이 발급되었습니다."
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
