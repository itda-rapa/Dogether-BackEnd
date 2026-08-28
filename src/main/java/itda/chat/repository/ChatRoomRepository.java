package itda.chat.repository;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.RoomType;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByTypeAndPetLowIdAndPetHighId(RoomType type, long petLowId, long petHighId);

    Page<ChatRoom> findByTypeAndOriginAndStatusAndIsPublicTrue(RoomType type,
                                                               itda.chat.domain.RoomOrigin origin,
                                                               itda.chat.domain.RoomStatus status,
                                                               Pageable pageable);

    @Query(value = """
            SELECT room.*
            FROM chat_room_participants participant
            JOIN chat_rooms room ON room.id = participant.room_id
            WHERE participant.pet_id = :activePetId
              AND participant.left_at IS NULL
              AND room.type = 'GROUP'
              AND room.origin = 'OPEN_CHAT'
              AND room.status = 'ACTIVE'
            ORDER BY COALESCE(room.last_message_at, room.created_at) DESC, room.id DESC
            """, nativeQuery = true)
    List<ChatRoom> findJoinedOpenChatRooms(@Param("activePetId") long activePetId);

    default ChatRoom findByIdOrThrow(long id){
        return findById(id).orElseThrow(
                ()-> new BusinessException(ErrorCode.CHATROOM_NOT_FOUND)
        );
    }

    /**
     * Lock the room row with {@code SELECT ... FOR UPDATE} so concurrent report creation
     * against the same room is serialized. The lock is held until the surrounding
     * transaction commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.id = :roomId")
    Optional<ChatRoom> findByIdForUpdate(@Param("roomId") Long roomId);

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
     * Restore an archived room and atomically advance its activity timestamps.
     * GREATEST prevents a late commit from moving either timestamp backwards.
     */
    @Modifying
    @Query(value = """
            UPDATE chat_rooms
            SET status = 'ACTIVE',
                archived_at = NULL,
                last_message_at = GREATEST(COALESCE(last_message_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP),
                updated_at = GREATEST(updated_at, CURRENT_TIMESTAMP)
            WHERE id = :roomId
            """, nativeQuery = true)
    void activateAndTouchLastMessageAt(@Param("roomId") long roomId);

    @Query(value = """
            SELECT r.id AS roomId,
                   r.pet_low_id AS petLowId,
                   r.pet_high_id AS petHighId
              FROM chat_rooms r
             WHERE r.type = 'DIRECT'
               AND r.status = 'ACTIVE'
               AND r.last_message_at IS NOT NULL
               AND r.last_message_at <= :cutoff
               AND EXISTS (
                   SELECT 1
                     FROM greetings g
                    WHERE g.room_id = r.id
                      AND g.status = 'RESPONDED'
               )
               AND NOT EXISTS (
                   SELECT 1
                     FROM friendships f
                    WHERE f.pet_low_id = r.pet_low_id
                      AND f.pet_high_id = r.pet_high_id
               )
             ORDER BY r.last_message_at ASC, r.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<InactiveAnsweredDirectRoom> findInactiveAnsweredDirectRooms(
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit
    );

    interface InactiveAnsweredDirectRoom {

        Long getRoomId();

        Long getPetLowId();

        Long getPetHighId();
    }

    /**
     * Room list projection row — one row per room, with last-message columns from LEFT JOIN
     * LATERAL. Used by {@code ChatQueryService.getRooms}.
     */
    @Query(value = """
            SELECT
                r.id                                     AS roomId,
                r.status                                 AS status,
                r.origin                                 AS origin,
                r.pet_low_id                             AS petLowId,
                r.pet_high_id                            AS petHighId,
                r.last_message_at                        AS lastMessageAt,
                r.created_at                             AS createdAt,
                r.updated_at                             AS updatedAt,
                lm.id                                    AS msgId,
                lm.sender_type                           AS msgSenderType,
                lm.sender_pet_id                         AS msgSenderPetId,
                lm.type                                  AS msgType,
                lm.body                                  AS msgBody,
                lm.meeting_card_id                       AS msgMeetingCardId,
                lm.client_message_id                     AS msgClientMessageId,
                lm.created_at                            AS msgCreatedAt
            FROM chat_room_participants p
            JOIN chat_rooms r ON r.id = p.room_id
            LEFT JOIN LATERAL (
                SELECT m.* FROM chat_messages m
                WHERE m.room_id = r.id
                ORDER BY m.id DESC
                LIMIT 1
            ) lm ON TRUE
            WHERE p.pet_id = :activePetId
              AND p.left_at IS NULL
              AND (:activePetId = r.pet_low_id OR :activePetId = r.pet_high_id)
              AND NOT EXISTS (
                  SELECT 1
                    FROM user_blocks ub
                    JOIN pets actor_pet
                      ON actor_pet.id = :activePetId
                    JOIN pets counterpart_pet
                      ON counterpart_pet.id = CASE
                          WHEN r.pet_low_id = :activePetId THEN r.pet_high_id
                          ELSE r.pet_low_id
                      END
                   WHERE (
                       ub.blocker_user_id = actor_pet.owner_user_id
                       AND ub.blocked_user_id = counterpart_pet.owner_user_id
                   ) OR (
                       ub.blocker_user_id = counterpart_pet.owner_user_id
                       AND ub.blocked_user_id = actor_pet.owner_user_id
                   )
              )
              AND ( CAST(:cursorActivityAt AS TIMESTAMPTZ) IS NULL
                    OR COALESCE(r.last_message_at, r.created_at) < CAST(:cursorActivityAt AS TIMESTAMPTZ)
                    OR ( COALESCE(r.last_message_at, r.created_at) = CAST(:cursorActivityAt AS TIMESTAMPTZ)
                         AND r.id < :cursorRoomId ) )
            ORDER BY COALESCE(r.last_message_at, r.created_at) DESC, r.id DESC
            LIMIT :limitPlusOne
            """, nativeQuery = true)
    java.util.List<RoomListRow> findRoomsWithLastMessage(@Param("activePetId") long activePetId,
                                                         @Param("cursorActivityAt") String cursorActivityAt,
                                                         @Param("cursorRoomId") long cursorRoomId,
                                                         @Param("limitPlusOne") int limitPlusOne);

    /**
     * Fetch a single room by id, verifying the active pet is a participant.
     */
    @Query(value = """
            SELECT
                r.id                                     AS roomId,
                r.status                                 AS status,
                r.origin                                 AS origin,
                r.pet_low_id                             AS petLowId,
                r.pet_high_id                            AS petHighId,
                r.last_message_at                        AS lastMessageAt,
                r.created_at                             AS createdAt,
                r.updated_at                             AS updatedAt,
                lm.id                                    AS msgId,
                lm.sender_type                           AS msgSenderType,
                lm.sender_pet_id                         AS msgSenderPetId,
                lm.type                                  AS msgType,
                lm.body                                  AS msgBody,
                lm.meeting_card_id                       AS msgMeetingCardId,
                lm.client_message_id                     AS msgClientMessageId,
                lm.created_at                            AS msgCreatedAt
            FROM chat_room_participants p
            JOIN chat_rooms r ON r.id = p.room_id
            LEFT JOIN LATERAL (
                SELECT m.* FROM chat_messages m
                WHERE m.room_id = r.id
                ORDER BY m.id DESC
                LIMIT 1
            ) lm ON TRUE
            WHERE p.pet_id = :activePetId
              AND p.left_at IS NULL
              AND r.id = :roomId
            """, nativeQuery = true)
    java.util.Optional<RoomListRow> findRoomById(@Param("activePetId") long activePetId,
                                                  @Param("roomId") long roomId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                  FROM chat_rooms room
                  JOIN chat_room_participants participant
                    ON participant.room_id = room.id
                   AND participant.pet_id = :activePetId
                   AND participant.left_at IS NULL
                  JOIN pets actor_pet
                    ON actor_pet.id = :activePetId
                  LEFT JOIN pets counterpart_pet
                    ON counterpart_pet.id = CASE
                        WHEN room.pet_low_id = :activePetId THEN room.pet_high_id
                        ELSE room.pet_low_id
                    END
                 WHERE room.id = :roomId
                   AND (
                       (
                           room.type = 'GROUP'
                           AND room.origin = 'OPEN_CHAT'
                           AND room.status = 'ACTIVE'
                       )
                       OR
                       (
                           room.type = 'DIRECT'
                           AND (:activePetId = room.pet_low_id OR :activePetId = room.pet_high_id)
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM user_blocks ub
                                WHERE (
                                    ub.blocker_user_id = actor_pet.owner_user_id
                                    AND ub.blocked_user_id = counterpart_pet.owner_user_id
                                ) OR (
                                    ub.blocker_user_id = counterpart_pet.owner_user_id
                                    AND ub.blocked_user_id = actor_pet.owner_user_id
                                )
                           )
                       )
                   )
            )
            """, nativeQuery = true)
    boolean existsAccessibleRoomForPet(
            @Param("roomId") long roomId,
            @Param("activePetId") long activePetId
    );

    interface RoomListRow {
        Long getRoomId();
        String getStatus();
        String getOrigin();
        Long getPetLowId();
        Long getPetHighId();
        java.time.Instant getLastMessageAt();
        java.time.Instant getCreatedAt();
        java.time.Instant getUpdatedAt();
        Long getMsgId();
        String getMsgSenderType();
        Long getMsgSenderPetId();
        String getMsgType();
        String getMsgBody();
        Long getMsgMeetingCardId();
        String getMsgClientMessageId();
        java.time.Instant getMsgCreatedAt();
    }
}
