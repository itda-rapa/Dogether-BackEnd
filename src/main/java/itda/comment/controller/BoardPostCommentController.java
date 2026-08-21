package itda.comment.controller;

import itda.comment.dto.CommentListResponse;
import itda.comment.dto.CommentRequestParser;
import itda.comment.dto.CommentResponse;
import itda.comment.service.BoardPostCommentService;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class BoardPostCommentController implements BoardPostCommentSwaggerSupporter {

    private final BoardPostCommentService service;
    private final CommentRequestParser parser;

    public BoardPostCommentController(
            BoardPostCommentService service,
            CommentRequestParser parser
    ) {
        this.service = service;
        this.parser = parser;
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentResponse>> create(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long postId,
            @RequestBody(required = true) JsonNode body
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(
                        service.create(user.id(), postId, parser.parseCreate(body)),
                        "댓글이 등록되었습니다."
                )
        );
    }

    @PostMapping("/comments/{parentCommentId}/replies")
    public ResponseEntity<ApiResponse<CommentResponse>> createReply(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long parentCommentId,
            @RequestBody(required = true) JsonNode body
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(
                        service.createReply(
                                user.id(),
                                parentCommentId,
                                parser.parseCreate(body)
                        ),
                        "대댓글이 등록되었습니다."
                )
        );
    }

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentListResponse>> list(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long postId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.list(user.id(), postId, cursor, size),
                "댓글 목록이 조회되었습니다."
        ));
    }

    @PatchMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<CommentResponse>> update(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long commentId,
            @RequestBody(required = true) JsonNode body
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.update(user.id(), commentId, parser.parseUpdate(body)),
                "댓글이 수정되었습니다."
        ));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long commentId
    ) {
        service.delete(user.id(), commentId);
        return ResponseEntity.noContent().build();
    }
}
