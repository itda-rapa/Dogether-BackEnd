package itda.comment.repository;

import itda.comment.domain.BoardPostComment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostCommentRepository extends JpaRepository<BoardPostComment, Long> {

    Optional<BoardPostComment> findByIdAndDeletedAtIsNull(Long commentId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            SELECT comment
            FROM BoardPostComment comment
            WHERE comment.id = :commentId
              AND comment.deletedAt IS NULL
            """)
    Optional<BoardPostComment> findActiveByIdForShare(@Param("commentId") Long commentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT comment
            FROM BoardPostComment comment
            WHERE comment.id = :commentId
              AND comment.deletedAt IS NULL
            """)
    Optional<BoardPostComment> findActiveByIdForUpdate(@Param("commentId") Long commentId);

    @Query(value = """
            SELECT board_comment.*
            FROM board_post_comments board_comment
            WHERE board_comment.post_id = :postId
              AND board_comment.parent_comment_id IS NULL
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
              AND (
                  board_comment.deleted_at IS NULL
                  OR EXISTS (
                      SELECT 1
                      FROM board_post_comments descendant
                      LEFT JOIN board_post_comments parent
                        ON parent.id = descendant.parent_comment_id
                      LEFT JOIN board_post_comments grandparent
                        ON grandparent.id = parent.parent_comment_id
                      WHERE descendant.post_id = board_comment.post_id
                        AND descendant.root_comment_id = board_comment.id
                        AND descendant.deleted_at IS NULL
                        AND NOT EXISTS (
                            SELECT 1
                            FROM user_blocks block
                            WHERE (block.blocker_user_id = :viewerUserId
                                   AND block.blocked_user_id = descendant.author_user_id)
                               OR (block.blocker_user_id = descendant.author_user_id
                                   AND block.blocked_user_id = :viewerUserId)
                        )
                        AND (
                            (descendant.depth = 1
                             AND descendant.parent_comment_id = board_comment.id)
                            OR (descendant.depth = 2
                                AND parent.post_id = board_comment.post_id
                                AND parent.depth = 1
                                AND parent.parent_comment_id = board_comment.id
                                AND NOT EXISTS (
                                    SELECT 1
                                    FROM user_blocks block
                                    WHERE (block.blocker_user_id = :viewerUserId
                                           AND block.blocked_user_id = parent.author_user_id)
                                       OR (block.blocker_user_id = parent.author_user_id
                                           AND block.blocked_user_id = :viewerUserId)
                                ))
                            OR (descendant.depth = 3
                                AND parent.post_id = board_comment.post_id
                                AND parent.depth = 2
                                AND grandparent.post_id = board_comment.post_id
                                AND grandparent.depth = 1
                                AND grandparent.parent_comment_id = board_comment.id
                                AND NOT EXISTS (
                                    SELECT 1
                                    FROM user_blocks block
                                    WHERE (block.blocker_user_id = :viewerUserId
                                           AND block.blocked_user_id = parent.author_user_id)
                                       OR (block.blocker_user_id = parent.author_user_id
                                           AND block.blocked_user_id = :viewerUserId)
                                )
                                AND NOT EXISTS (
                                    SELECT 1
                                    FROM user_blocks block
                                    WHERE (block.blocker_user_id = :viewerUserId
                                           AND block.blocked_user_id = grandparent.author_user_id)
                                       OR (block.blocker_user_id = grandparent.author_user_id
                                           AND block.blocked_user_id = :viewerUserId)
                                ))
                        )
                  )
              )
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

    @Query("""
            SELECT comment
            FROM BoardPostComment comment
            WHERE comment.postId = :postId
              AND comment.rootCommentId IN :rootCommentIds
            ORDER BY comment.createdAt ASC, comment.id ASC
            """)
    List<BoardPostComment> findDescendantsByRootCommentIdIn(
            @Param("postId") Long postId,
            @Param("rootCommentIds") List<Long> rootCommentIds
    );
}
