package itda.boardpost.repository;

import itda.boardpost.domain.BoardPostReaction;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostReactionRepository
        extends JpaRepository<BoardPostReaction, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO board_post_reactions (
                post_id, reactor_pet_id, reaction_type
            ) VALUES (
                :postId, :reactorPetId, :reactionType
            )
            ON CONFLICT (post_id, reactor_pet_id, reaction_type) DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(
            @Param("postId") Long postId,
            @Param("reactorPetId") Long reactorPetId,
            @Param("reactionType") String reactionType
    );

    @Modifying
    @Query(value = """
            DELETE FROM board_post_reactions
            WHERE post_id = :postId
              AND reactor_pet_id = :reactorPetId
              AND reaction_type = :reactionType
            """, nativeQuery = true)
    int deleteReaction(
            @Param("postId") Long postId,
            @Param("reactorPetId") Long reactorPetId,
            @Param("reactionType") String reactionType
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM board_post_reactions
            WHERE post_id = :postId
              AND reaction_type = :reactionType
            """, nativeQuery = true)
    long countForPost(
            @Param("postId") Long postId,
            @Param("reactionType") String reactionType
    );

    @Query(value = """
            SELECT post_id AS "postId", COUNT(*) AS "reactionCount"
            FROM board_post_reactions
            WHERE post_id IN (:postIds)
              AND reaction_type = :reactionType
            GROUP BY post_id
            """, nativeQuery = true)
    List<PostReactionCount> countForPosts(
            @Param("postIds") Collection<Long> postIds,
            @Param("reactionType") String reactionType
    );

    @Query(value = """
            SELECT post_id
            FROM board_post_reactions
            WHERE reactor_pet_id = :reactorPetId
              AND post_id IN (:postIds)
              AND reaction_type = :reactionType
            """, nativeQuery = true)
    List<Long> findReactedPostIds(
            @Param("reactorPetId") Long reactorPetId,
            @Param("postIds") Collection<Long> postIds,
            @Param("reactionType") String reactionType
    );

    interface PostReactionCount {
        Long getPostId();

        long getReactionCount();
    }
}
