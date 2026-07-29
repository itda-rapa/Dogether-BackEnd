package itda.friend.repository;

import itda.friend.domain.Friendship;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM friendships friendship
             USING pets low_pet, pets high_pet
             WHERE low_pet.id = friendship.pet_low_id
               AND high_pet.id = friendship.pet_high_id
               AND (
                   (low_pet.owner_user_id = :userA
                       AND high_pet.owner_user_id = :userB)
                   OR
                   (low_pet.owner_user_id = :userB
                       AND high_pet.owner_user_id = :userA)
               )
            """, nativeQuery = true)
    int deleteBetweenUsers(
            @Param("userA") Long userA,
            @Param("userB") Long userB
    );

    interface FriendshipRelationshipRow {

        Long getPetLowId();

        Long getPetHighId();
    }
}
