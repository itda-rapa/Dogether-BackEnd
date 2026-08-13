package itda.boardpost.repository;

import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.PostStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostRepository extends JpaRepository<BoardPost, Long> {
    boolean existsByBoardId(Long boardId);
    boolean existsByBoardIdAndStatus(Long boardId, PostStatus status);
    Optional<BoardPost> findByIdAndStatus(Long id, PostStatus status);

    @Query(value = """
            SELECT post.* FROM board_posts post
            WHERE post.board_id = :boardId
              AND post.neighborhood_code = :neighborhoodCode
              AND post.status = 'PUBLISHED'
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks block WHERE
                    (block.blocker_user_id = :viewerUserId AND block.blocked_user_id = post.author_user_id)
                    OR (block.blocker_user_id = post.author_user_id AND block.blocked_user_id = :viewerUserId)
              )
              AND (CAST(:cursorCreatedAt AS TIMESTAMPTZ) IS NULL
                   OR post.created_at < CAST(:cursorCreatedAt AS TIMESTAMPTZ)
                   OR (post.created_at = CAST(:cursorCreatedAt AS TIMESTAMPTZ) AND post.id < :cursorPostId))
            ORDER BY post.created_at DESC, post.id DESC
            LIMIT :limitPlusOne
            """, nativeQuery = true)
    List<BoardPost> findVisibleFeed(@Param("boardId") Long boardId, @Param("neighborhoodCode") String neighborhoodCode,
            @Param("viewerUserId") Long viewerUserId, @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorPostId") Long cursorPostId, @Param("limitPlusOne") int limitPlusOne);
}
