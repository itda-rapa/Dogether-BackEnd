package itda.media.old.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.media.old.dto.MediaAssetResponse;
import itda.media.old.dto.MediaUploadRequest;
import itda.media.old.dto.MediaUploadResponse;
import itda.media.service.MediaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/media")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/uploads")
    public ResponseEntity<ApiResponse<MediaUploadResponse>> createUpload(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody MediaUploadRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(
                        mediaService.createUpload(currentUser.id(), request),
                        "업로드 URL이 발급되었습니다."
                ));
    }

    @PostMapping("/{mediaAssetId}/complete")
    public ApiResponse<MediaAssetResponse> complete(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long mediaAssetId
    ) {
        return ApiResponse.ok(
                mediaService.complete(currentUser.id(), mediaAssetId),
                "업로드가 확인되었습니다."
        );
    }

    @GetMapping("/{mediaAssetId}")
    public ApiResponse<MediaAssetResponse> get(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long mediaAssetId
    ) {
        return ApiResponse.ok(
                mediaService.get(currentUser.id(), mediaAssetId),
                "미디어 자산이 조회되었습니다."
        );
    }

    @DeleteMapping("/{mediaAssetId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long mediaAssetId
    ) {
        mediaService.requestDeletion(currentUser.id(), mediaAssetId);
        return ResponseEntity.accepted().build();
    }
}
