package itda.setlog.controller;

import itda.common.dto.ApiResponse;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.greeting.dto.GreetingResponse;
import itda.greeting.service.GreetingService;
import itda.setlog.domain.ReactionType;
import itda.setlog.dto.SetlogListResponse;
import itda.setlog.dto.SetlogReactionResponse;
import itda.setlog.dto.SetlogUploadCreateRequest;
import itda.setlog.dto.SetlogUploadCreateResponse;
import itda.setlog.dto.SetlogUploadCompleteRequest;
import itda.setlog.dto.SetlogResponse;
import itda.setlog.service.SetlogReadService;
import itda.setlog.service.SetlogReactionService;
import itda.setlog.service.SetlogUploadSessionService;
import itda.setlog.service.SetlogUploadCompletionService;
import itda.setlog.service.SetlogDeleteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/setlogs")
@RequiredArgsConstructor
public class SetlogController implements SetlogSwaggerSupporter {

    private final SetlogReadService setlogReadService;
    private final SetlogReactionService setlogReactionService;
    private final SetlogUploadSessionService setlogUploadSessionService;
    private final SetlogUploadCompletionService setlogUploadCompletionService;
    private final SetlogDeleteService setlogDeleteService;
    private final GreetingService greetingService;

    @PostMapping("/uploads")
    public ResponseEntity<ApiResponse<SetlogUploadCreateResponse>> createUploadSession(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody SetlogUploadCreateRequest request
    ) {
        SetlogUploadCreateResponse upload = setlogUploadSessionService.create(
                currentUser.id(),
                request
        );
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(
                ApiResponse.created(upload, "셋로그 업로드 세션 생성 성공")
        );
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<ApiResponse<SetlogResponse>> completeUpload(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable java.util.UUID uploadId,
            @Valid @RequestBody SetlogUploadCompleteRequest request
    ) {
        SetlogUploadCompletionService.CompletionResult result =
                setlogUploadCompletionService.complete(
                        currentUser.id(), uploadId, request.clientRequestId(), request.caption()
                );
        ApiResponse<SetlogResponse> body = result.replayed()
                ? ApiResponse.ok(result.response(), "셋로그 업로드 완료 재처리 성공")
                : ApiResponse.created(result.response(), "셋로그 업로드 완료 성공");
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /** The legacy direct-create path bypassed storage metadata verification. */
    @PostMapping
    public ResponseEntity<Void> rejectDirectSetlogCreation() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<SetlogListResponse>> getSetlogs(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        SetlogListResponse setlogs = setlogReadService.getSetlogs(
                currentUser.id(),
                cursor,
                size
        );
        return ResponseEntity.ok(
                ApiResponse.ok(setlogs, "셋로그 조회 성공")
        );
    }

    @GetMapping("/{setlogId}")
    public ResponseEntity<ApiResponse<SetlogResponse>> getSetlog(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long setlogId
    ) {
        SetlogResponse setlog = setlogReadService.getSetlog(
                currentUser.id(), setlogId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(setlog, "셋로그 상세 조회 성공"));
    }

    @DeleteMapping("/{setlogId}")
    public ResponseEntity<Void> deleteSetlog(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long setlogId
    ) {
        setlogDeleteService.delete(currentUser.id(), setlogId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{setlogId}/reactions/{type}")
    public ResponseEntity<ApiResponse<SetlogReactionResponse>> addReaction(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long setlogId,
            @PathVariable ReactionType type
    ) {
        SetlogReactionResponse reaction =
                setlogReactionService.addReaction(
                        currentUser.id(),
                        setlogId,
                        type
                );
        return ResponseEntity.ok(
                ApiResponse.ok(reaction, "셋로그 반응 추가 성공")
        );
    }

    @DeleteMapping("/{setlogId}/reactions/{type}")
    public ResponseEntity<ApiResponse<SetlogReactionResponse>> removeReaction(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long setlogId,
            @PathVariable ReactionType type
    ) {
        SetlogReactionResponse reaction =
                setlogReactionService.removeReaction(
                        currentUser.id(),
                        setlogId,
                        type
                );
        return ResponseEntity.ok(
                ApiResponse.ok(reaction, "셋로그 반응 취소 성공")
        );
    }

    @PostMapping("/{setlogId}/greetings")
    public ResponseEntity<ApiResponse<GreetingResponse>> sendGreeting(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long setlogId
    ) {
        GreetingResponse greeting = greetingService.send(
                currentUser.id(),
                setlogId
        );
        return ResponseEntity.status(201).body(
                ApiResponse.created(greeting, "인사 전송 및 채팅방 생성 성공")
        );
    }
}
