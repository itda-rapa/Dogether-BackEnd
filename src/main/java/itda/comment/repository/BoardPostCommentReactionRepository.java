package itda.comment.repository;

import itda.comment.domain.BoardPostCommentReaction;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostCommentReactionRepository
        extends JpaRepository<BoardPostCommentReaction, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO board_post_comment_reactions (
                comment_id, reactor_pet_id, reaction_type
            ) VALUES (
                :commentId, :reactorPetId, :reactionType
            )
            ON CONFLICT (comment_id, reactor_pet_id, reaction_type) DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(
            @Param("commentId") Long commentId,
            @Param("reactorPetId") Long reactorPetId,
            @Param("reactionType") String reactionType
    );

    @Modifying
    @Query(value = """
            DELETE FROM board_post_comment_reactions
            WHERE comment_id = :commentId
              AND reactor_pet_id = :reactorPetId
              AND reaction_type = :reactionType
            """, nativeQuery = true)
    int deleteReaction(
            @Param("commentId") Long commentId,
            @Param("reactorPetId") Long reactorPetId,
            @Param("reactionType") String reactionType
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM board_post_comment_reactions
            WHERE comment_id = :commentId
              AND reaction_type = :reactionType
            """, nativeQuery = true)
    long countForComment(
            @Param("commentId") Long commentId,
            @Param("reactionType") String reactionType
    );

    @Query(value = """
            SELECT comment_id AS "commentId", COUNT(*) AS "reactionCount"
            FROM board_post_comment_reactions
            WHERE comment_id IN (:commentIds)
              AND reaction_type = :reactionType
            GROUP BY comment_id
            """, nativeQuery = true)
    List<CommentReactionCount> countForComments(
            @Param("commentIds") Collection<Long> commentIds,
            @Param("reactionType") String reactionType
    );

    @Query(value = """
            SELECT comment_id
            FROM board_post_comment_reactions
            WHERE reactor_pet_id = :reactorPetId
              AND comment_id IN (:commentIds)
              AND reaction_type = :reactionType
            """, nativeQuery = true)
    List<Long> findReactedCommentIds(
            @Param("reactorPetId") Long reactorPetId,
            @Param("commentIds") Collection<Long> commentIds,
            @Param("reactionType") String reactionType
    );

    @Query(value = """
            SELECT comment.author_pet_id AS "petId", COUNT(*) AS "helpfulReceivedCount"
            FROM board_post_comment_reactions reaction
            JOIN board_post_comments comment ON comment.id = reaction.comment_id
            WHERE comment.author_pet_id IN (:petIds)
              AND comment.deleted_at IS NULL
              AND reaction.reaction_type = 'HELPFUL'
            GROUP BY comment.author_pet_id
            """, nativeQuery = true)
    List<PetHelpfulReceivedCount> countHelpfulReceivedForPets(
            @Param("petIds") Collection<Long> petIds
    );

    interface CommentReactionCount {
        Long getCommentId();

        long getReactionCount();
    }

    interface PetHelpfulReceivedCount {
        Long getPetId();

        long getHelpfulReceivedCount();
    }
}
