package itda.chat.repository;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.RoomType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByTypeAndPetLowIdAndPetHighId(RoomType type, long petLowId, long petHighId);

    /**
     * Atomic INSERT ... ON CONFLICT DO NOTHING.
     * Fire-and-forget: returns row count (1 = inserted, 0 = conflict existed).
     * Follow with a query to retrieve the room safely.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO chat_rooms (type, status, origin, pet_low_id, pet_high_id)
            VALUES (CAST(:type AS VARCHAR), CAST(:status AS VARCHAR), CAST(:origin AS VARCHAR), :lowId, :highId)
            ON CONFLICT (pet_low_id, pet_high_id) WHERE type = 'DIRECT' DO NOTHING
            """, nativeQuery = true)
    int insertDirectRoomOnConflict(@Param("type") String type, @Param("status") String status,
                                   @Param("origin") String origin, @Param("lowId") long lowId,
                                   @Param("highId") long highId);

    /**
     * Atomically update last_message_at / updated_at using GREATEST so
     * a late-committing concurrent message never pushes the timestamp backwards.
     */
    @Modifying
    @Query(value = """
            UPDATE chat_rooms
            SET last_message_at = GREATEST(COALESCE(last_message_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP),
                updated_at = GREATEST(updated_at, CURRENT_TIMESTAMP)
            WHERE id = :roomId
            """, nativeQuery = true)
    void touchLastMessageAt(@Param("roomId") long roomId);
}
