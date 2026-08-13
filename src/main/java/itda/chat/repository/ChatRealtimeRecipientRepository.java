package itda.chat.repository;

import itda.chat.domain.ChatRoomParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ChatRealtimeRecipientRepository extends Repository<ChatRoomParticipant, Long> {

    @Query(value = """
            SELECT DISTINCT recipient_user.id
              FROM chat_rooms room
              JOIN chat_room_participants participant
                ON participant.room_id = room.id
               AND participant.left_at IS NULL
              JOIN pets recipient_pet
                ON recipient_pet.id = participant.pet_id
              JOIN users recipient_user
                ON recipient_user.id = recipient_pet.owner_user_id
             WHERE room.id = :roomId
               AND recipient_user.account_status = 'ACTIVE'
               AND recipient_user.active_pet_id = participant.pet_id
               AND recipient_pet.status = 'ACTIVE'
               AND recipient_pet.deleted_at IS NULL
               AND (
                   (:senderPetId IS NOT NULL AND participant.pet_id = :senderPetId)
                   OR NOT EXISTS (
                   SELECT 1
                     FROM chat_room_participants other_participant
                     JOIN pets other_pet
                       ON other_pet.id = other_participant.pet_id
                     JOIN user_blocks ub
                       ON (ub.blocker_user_id = recipient_user.id
                           AND ub.blocked_user_id = other_pet.owner_user_id)
                       OR (ub.blocker_user_id = other_pet.owner_user_id
                           AND ub.blocked_user_id = recipient_user.id)
                    WHERE other_participant.room_id = room.id
                      AND other_participant.left_at IS NULL
                      AND other_participant.pet_id <> participant.pet_id
                   )
               )
            """, nativeQuery = true)
    List<Long> findActiveRecipientUserIds(
            @Param("roomId") long roomId,
            @Param("senderPetId") Long senderPetId
    );
}
