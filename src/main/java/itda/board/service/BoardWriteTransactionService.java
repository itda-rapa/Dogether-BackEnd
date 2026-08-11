package itda.board.service;

import itda.board.domain.Board;
import itda.board.dto.BoardResponse;
import itda.board.repository.BoardRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardWriteTransactionService {

    private final BoardRepository boardRepository;

    public BoardWriteTransactionService(BoardRepository boardRepository) {
        this.boardRepository = boardRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BoardResponse create(String name) {
        Board board = boardRepository.saveAndFlush(Board.create(name));
        return BoardResponse.from(board);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BoardResponse update(
            Long boardId,
            long requestedVersion,
            String normalizedName
    ) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));
        if (board.getVersion() != requestedVersion) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }

        if (board.changeName(normalizedName)) {
            boardRepository.flush();
        }
        return BoardResponse.from(board);
    }
}
