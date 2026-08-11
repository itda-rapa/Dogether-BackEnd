package itda.board.controller;

import itda.board.dto.BoardCreateRequest;
import itda.board.dto.BoardResponse;
import itda.board.dto.BoardUpdateRequestParser;
import itda.board.service.BoardService;
import itda.board.service.BoardDeletionService;
import itda.common.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/admin/boards")
public class AdminBoardController implements AdminBoardSwaggerSupporter {

    private final BoardService boardService;
    private final BoardUpdateRequestParser boardUpdateRequestParser;
    private final BoardDeletionService boardDeletionService;

    public AdminBoardController(
            BoardService boardService,
            BoardUpdateRequestParser boardUpdateRequestParser,
            BoardDeletionService boardDeletionService
    ) {
        this.boardService = boardService;
        this.boardUpdateRequestParser = boardUpdateRequestParser;
        this.boardDeletionService = boardDeletionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BoardResponse>> createBoard(
            @RequestBody(required = true) BoardCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(
                boardService.create(request),
                "게시판이 등록되었습니다."
        ));
    }

    @PatchMapping("/{boardId}")
    public ResponseEntity<ApiResponse<BoardResponse>> updateBoard(
            @PathVariable Long boardId,
            @RequestBody(required = true) JsonNode requestBody
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                boardService.update(
                        boardId,
                        boardUpdateRequestParser.parse(requestBody)
                ),
                "게시판이 수정되었습니다."
        ));
    }

    @DeleteMapping("/{boardId}")
    public ResponseEntity<Void> deleteBoard(@PathVariable Long boardId) {
        boardDeletionService.delete(boardId);
        return ResponseEntity.noContent().build();
    }
}
