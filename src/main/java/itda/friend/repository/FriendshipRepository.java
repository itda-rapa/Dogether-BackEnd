package itda.friend.repository;

import itda.friend.domain.Friendship;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    @Query(value = """
            SELECT
                friendship.pet_low_id AS petLowId,
                friendship.pet_high_id AS petHighId
            FROM friendships friendship
            WHERE (
                friendship.pet_low_id = :sourcePetId
                AND friendship.pet_high_id IN (:targetPetIds)
            ) OR (
                friendship.pet_high_id = :sourcePetId
                AND friendship.pet_low_id IN (:targetPetIds)
            )
            """, nativeQuery = true)
    List<FriendshipRelationshipRow> findRelationships(
            @Param("sourcePetId") Long sourcePetId,
            @Param("targetPetIds") Collection<Long> targetPetIds
    );

    interface FriendshipRelationshipRow {

        Long getPetLowId();

        Long getPetHighId();
    }
}
