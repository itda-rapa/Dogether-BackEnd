package itda.board.repository;

import itda.board.domain.Board;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardRepository extends JpaRepository<Board, Long> {

    List<Board> findAllByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from Board b where b.id = :boardId")
    Optional<Board> findByIdForShare(@Param("boardId") Long boardId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Board b where b.id = :boardId")
    Optional<Board> findByIdForUpdate(@Param("boardId") Long boardId);
}
