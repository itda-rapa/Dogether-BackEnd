package itda.board.service;

import itda.board.domain.Board;
import itda.board.repository.BoardRepository;
import itda.boardpost.repository.BoardPostRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardDeletionService {

    private final BoardRepository boards;
    private final BoardPostRepository posts;

    public BoardDeletionService(
            BoardRepository boards,
            BoardPostRepository posts
    ) {
        this.boards = boards;
        this.posts = posts;
    }

    @Transactional
    public void delete(Long boardId) {
        Board board = boards.findByIdForUpdate(boardId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.BOARD_NOT_FOUND)
                );
        if (posts.existsByBoardId(boardId)) {
            throw new BusinessException(ErrorCode.BOARD_NOT_EMPTY);
        }
        boards.delete(board);
        boards.flush();
    }
}
