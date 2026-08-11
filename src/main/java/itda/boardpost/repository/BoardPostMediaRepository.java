package itda.boardpost.repository;

import itda.boardpost.domain.BoardPostMedia;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardPostMediaRepository extends JpaRepository<BoardPostMedia, Long> {
    List<BoardPostMedia> findByPostIdOrderByDisplayOrderAsc(Long postId);
    List<BoardPostMedia> findByPostIdIn(Collection<Long> postIds);
}
