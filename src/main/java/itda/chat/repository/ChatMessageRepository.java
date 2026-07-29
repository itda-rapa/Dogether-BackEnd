package itda.chat.repository;

import itda.chat.domain.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findByRoomIdAndClientMessageId(Long roomId, String clientMessageId);

    /**
     * Atomically insert a message and return its id — the newly written row when this caller won,
     * or the row already stored under the same idempotency key when it lost.
     *
     * <p>Deliberately NOT annotated {@code @Modifying}: that makes Spring Data call
     * {@code executeUpdate()}, and this statement is a top-level SELECT over a data-modifying CTE,
     * so the driver fails with "A result was returned when none was expected." It writes despite
     * being shaped like a query.
     *
     * <p>{@code DO UPDATE} rather than {@code DO NOTHING}, and the update is a deliberate no-op.
     * {@code DO NOTHING} yields no row on conflict, so the id has to be read back — and reading it
     * back inside this same statement cannot work: a statement sees one snapshot, taken before the
     * winning transaction committed, so the loser finds nothing and gets a null id.
     * {@code DO UPDATE} instead locks the conflicting row, waits for that transaction to finish,
     * and returns the surviving row, so every concurrent caller receives the same id.
     *
     * <p>With a null {@code clientMessageId} nothing ever conflicts ({@code NULL = NULL} is
     * unknown), so such messages always insert — which is the intent: they carry no idempotency key.
     */
    @Transactional
    @Query(value = """
            INSERT INTO chat_messages (room_id, sender_type, sender_pet_id, type, \
            body, meeting_card_id, client_message_id)
            VALUES (:roomId, CAST(:senderType AS VARCHAR), :senderPetId, CAST(:msgType AS VARCHAR), \
            :body, :meetingCardId, :clientMessageId)
            ON CONFLICT (room_id, client_message_id)
            DO UPDATE SET client_message_id = chat_messages.client_message_id
            RETURNING id, (xmax = 0) AS created
            """, nativeQuery = true)
    MessageUpsert insertMessageOnConflictWithReturning(@Param("roomId") long roomId,
                                                       @Param("senderType") String senderType,
                                                       @Param("senderPetId") Long senderPetId,
                                                       @Param("msgType") String msgType,
                                                       @Param("body") String body,
                                                       @Param("meetingCardId") Long meetingCardId,
                                                       @Param("clientMessageId") String clientMessageId);

    /**
     * Outcome of the upsert above: the surviving row's id, and whether this statement is what
     * inserted it.
     *
     * <p>{@code xmax = 0} is the PostgreSQL idiom for "this tuple was inserted, not updated" — a
     * row that took the {@code DO UPDATE} branch carries the updating transaction id in
     * {@code xmax}. Without it the caller cannot tell a fresh message from a returned duplicate.
     */
    interface MessageUpsert {
        Long getId();

        Boolean getCreated();
    }

    /**
     * Poll messages for a room, ascending by id, from afterMessageId exclusively.
     */
    @Query(value = """
            SELECT m FROM ChatMessage m
            WHERE m.room.id = :roomId
              AND m.id > :afterMessageId
            ORDER BY m.id ASC
            """)
    List<ChatMessage> findMessagesAfter(@Param("roomId") long roomId,
                                        @Param("afterMessageId") long afterMessageId,
                                        org.springframework.data.domain.Pageable pageable);
}
