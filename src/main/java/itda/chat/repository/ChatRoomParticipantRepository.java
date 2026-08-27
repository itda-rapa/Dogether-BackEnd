package itda.chat.repository;

import itda.chat.domain.ChatRoomParticipant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomParticipantRepository extends JpaRepository<ChatRoomParticipant, Long> {

    @Modifying(flushAutomatically = true)
    @Query("delete from ChatRoomParticipant p where p.room.id = :roomId")
    int deleteByRoomId(@Param("roomId") long roomId);

    List<ChatRoomParticipant> findByRoomId(long roomId);

    Optional<ChatRoomParticipant> findByRoomIdAndPetId(long roomId, long petId);

    boolean existsByRoomIdAndPetId(long roomId, long petId);

    boolean existsByRoomIdAndPetIdAndLeftAtIsNull(long roomId, long petId);

    @Query("""
            select participant.room.id
            from ChatRoomParticipant participant
            where participant.petId = :petId
              and participant.leftAt is null
              and participant.room.id in :roomIds
            """)
    List<Long> findActiveRoomIdsByPetIdAndRoomIdIn(
            @Param("petId") long petId,
            @Param("roomIds") Collection<Long> roomIds);

    long countByRoomIdAndLeftAtIsNull(long roomId);
}
