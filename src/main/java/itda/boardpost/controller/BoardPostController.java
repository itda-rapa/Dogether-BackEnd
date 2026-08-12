package itda.boardpost.controller;

import itda.boardpost.dto.BoardPostFeedResponse;
import itda.boardpost.dto.BoardPostReactionResponse;
import itda.boardpost.dto.BoardPostRequestParser;
import itda.boardpost.dto.BoardPostResponse;
import itda.boardpost.domain.BoardPostReactionType;
import itda.boardpost.service.BoardPostService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class BoardPostController implements BoardPostSwaggerSupporter {

    private final BoardPostService service;
    private final BoardPostRequestParser parser;

    public BoardPostController(
            BoardPostService service,
            BoardPostRequestParser parser
    ) {
        this.service = service;
        this.parser = parser;
    }

    @PostMapping("/boards/{boardId}/posts")
    public ResponseEntity<ApiResponse<BoardPostResponse>> create(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long boardId,
            @RequestBody(required = true) JsonNode body
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(
                        service.create(user.id(), boardId, parser.parseCreate(body)),
                        "게시글이 등록되었습니다."
                )
        );
    }

    @GetMapping("/boards/{boardId}/posts")
    public ResponseEntity<ApiResponse<BoardPostFeedResponse>> feed(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long boardId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.feed(user.id(), boardId, cursor, size),
                "게시글 목록이 조회되었습니다."
        ));
    }

    @GetMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<BoardPostResponse>> detail(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long postId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.detail(user.id(), postId),
                "게시글이 조회되었습니다."
        ));
    }

    @PatchMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<BoardPostResponse>> update(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long postId,
            @RequestBody(required = true) JsonNode body
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.update(user.id(), postId, parser.parseUpdate(body)),
                "게시글이 수정되었습니다."
        ));
    }

    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long postId
    ) {
        service.delete(user.id(), postId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/posts/{postId}/reactions/{type}")
    public ResponseEntity<ApiResponse<BoardPostReactionResponse>> addReaction(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long postId,
            @PathVariable BoardPostReactionType type
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.addReaction(user.id(), postId, type),
                "게시글 반응 상태가 변경되었습니다."
        ));
    }

    @DeleteMapping("/posts/{postId}/reactions/{type}")
    public ResponseEntity<ApiResponse<BoardPostReactionResponse>> removeReaction(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long postId,
            @PathVariable BoardPostReactionType type
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.removeReaction(user.id(), postId, type),
                "게시글 반응 상태가 변경되었습니다."
        ));
    }
}
