package itda.chat.repository;

import itda.chat.domain.ChatRoomParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomParticipantRepository extends JpaRepository<ChatRoomParticipant, Long> {

    List<ChatRoomParticipant> findByRoomId(long roomId);

    boolean existsByRoomIdAndPetId(long roomId, long petId);

    boolean existsByRoomIdAndPetIdAndLeftAtIsNull(long roomId, long petId);
}