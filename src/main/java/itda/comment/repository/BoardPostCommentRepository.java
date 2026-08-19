package itda.comment.repository;

import itda.comment.domain.BoardPostComment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostCommentRepository extends JpaRepository<BoardPostComment, Long> {

    Optional<BoardPostComment> findByIdAndDeletedAtIsNull(Long commentId);

    @Query(value = """
            SELECT board_comment.*
            FROM board_post_comments board_comment
            WHERE board_comment.post_id = :postId
              AND board_comment.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_blocks block
                  WHERE (block.blocker_user_id = :viewerUserId
                         AND block.blocked_user_id = board_comment.author_user_id)
                     OR (block.blocker_user_id = board_comment.author_user_id
                         AND block.blocked_user_id = :viewerUserId)
              )
              AND (CAST(:cursorCreatedAt AS TIMESTAMP WITH TIME ZONE) IS NULL
                   OR board_comment.created_at > CAST(:cursorCreatedAt AS TIMESTAMP WITH TIME ZONE)
                   OR (board_comment.created_at = CAST(:cursorCreatedAt AS TIMESTAMP WITH TIME ZONE)
                       AND board_comment.id > :cursorCommentId))
            ORDER BY board_comment.created_at ASC, board_comment.id ASC
            LIMIT :limitPlusOne
            """, nativeQuery = true)
    List<BoardPostComment> findVisibleByPostId(
            @Param("postId") Long postId,
            @Param("viewerUserId") Long viewerUserId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorCommentId") Long cursorCommentId,
            @Param("limitPlusOne") int limitPlusOne
    );
}
