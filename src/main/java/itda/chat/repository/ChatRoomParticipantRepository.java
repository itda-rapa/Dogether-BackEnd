package itda.chat.repository;

import itda.chat.domain.ChatRoomParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomParticipantRepository extends JpaRepository<ChatRoomParticipant, Long> {

    @Modifying(flushAutomatically = true)
    @Query("delete from ChatRoomParticipant p where p.room.id = :roomId")
    int deleteByRoomId(@Param("roomId") long roomId);

    List<ChatRoomParticipant> findByRoomId(long roomId);

    boolean existsByRoomIdAndPetId(long roomId, long petId);

    boolean existsByRoomIdAndPetIdAndLeftAtIsNull(long roomId, long petId);
}
