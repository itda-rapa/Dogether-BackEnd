package itda.board.service;

import itda.board.dto.BoardCreateRequest;
import itda.board.dto.BoardResponse;
import itda.board.dto.BoardUpdateRequest;
import itda.board.repository.BoardRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardService {

    private static final String BOARD_NAME_UNIQUE_CONSTRAINT = "uk_boards_name";

    private final BoardRepository boardRepository;
    private final BoardWriteTransactionService boardWriteTransactionService;

    public BoardService(
            BoardRepository boardRepository,
            BoardWriteTransactionService boardWriteTransactionService
    ) {
        this.boardRepository = boardRepository;
        this.boardWriteTransactionService = boardWriteTransactionService;
    }

    @Transactional(readOnly = true)
    public List<BoardResponse> getBoards() {
        return boardRepository.findAllByOrderByIdAsc().stream()
                .map(BoardResponse::from)
                .toList();
    }

    @Transactional(propagation = Propagation.NEVER)
    public BoardResponse create(BoardCreateRequest request) {
        String normalizedName = normalizeName(request == null ? null : request.name());
        try {
            return boardWriteTransactionService.create(normalizedName);
        } catch (DataIntegrityViolationException exception) {
            throw mapBoardNameDuplicate(exception);
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public BoardResponse update(Long boardId, BoardUpdateRequest request) {
        String normalizedName = normalizeName(
                request == null ? null : request.name()
        );
        try {
            return boardWriteTransactionService.update(
                    boardId,
                    request.version(),
                    normalizedName
            );
        } catch (DataIntegrityViolationException exception) {
            throw mapBoardNameDuplicate(exception);
        }
    }

    static String normalizeName(String rawName) {
        if (rawName == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        String normalizedName = rawName.strip();
        int codePointLength = normalizedName.codePointCount(
                0,
                normalizedName.length()
        );
        if (normalizedName.isEmpty() || codePointLength > 30) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalizedName;
    }

    private RuntimeException mapBoardNameDuplicate(
            DataIntegrityViolationException exception
    ) {
        if (isBoardNameUniqueConstraintViolation(exception)) {
            return new BusinessException(ErrorCode.BOARD_NAME_DUPLICATED);
        }
        return exception;
    }

    private boolean isBoardNameUniqueConstraintViolation(
            DataIntegrityViolationException exception
    ) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException
                    constraintViolation
                    && BOARD_NAME_UNIQUE_CONSTRAINT.equalsIgnoreCase(
                    constraintViolation.getConstraintName()
            )) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
