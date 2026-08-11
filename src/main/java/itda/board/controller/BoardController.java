package itda.board.controller;

import itda.board.dto.BoardResponse;
import itda.board.service.BoardService;
import itda.common.dto.ApiResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/boards")
public class BoardController implements BoardSwaggerSupporter {

    private final BoardService boardService;

    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BoardResponse>>> getBoards() {
        return ResponseEntity.ok(ApiResponse.ok(
                boardService.getBoards(),
                "게시판 목록이 조회되었습니다."
        ));
    }
}
