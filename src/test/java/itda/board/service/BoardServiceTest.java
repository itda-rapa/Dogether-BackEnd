package itda.board.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import itda.board.dto.BoardUpdateRequest;
import itda.board.repository.BoardRepository;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class BoardServiceTest {

    @Mock
    private BoardRepository boardRepository;

    @Mock
    private BoardWriteTransactionService boardWriteTransactionService;

    private BoardService boardService;

    @BeforeEach
    void setUp() {
        boardService = new BoardService(
                boardRepository,
                boardWriteTransactionService
        );
    }

    @Test
    void rethrowsNonBoardNameConstraintViolationWithoutMappingItToDuplicate() {
        DataIntegrityViolationException failure = constraintViolation(
                "ck_boards_name_not_blank"
        );
        given(boardWriteTransactionService.update(
                1L,
                0L,
                "유효한 이름"
        )).willThrow(failure);

        assertThatThrownBy(() -> boardService.update(
                1L,
                new BoardUpdateRequest("유효한 이름", 0L)
        )).isSameAs(failure);
    }

    private DataIntegrityViolationException constraintViolation(
            String constraintName
    ) {
        return new DataIntegrityViolationException(
                "constraint violation",
                new ConstraintViolationException(
                        "constraint violation",
                        new SQLException("constraint violation", "23514"),
                        constraintName
                )
        );
    }
}
