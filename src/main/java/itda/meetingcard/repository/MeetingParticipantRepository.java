package itda.meetingcard.repository;

import itda.meetingcard.domain.MeetingParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, Long> {

    /** 카드의 참여 Pet id. M1 DIRECT 방이므로 정확히 두 개여야 한다. */
    @Query("""
            SELECT p.petId
            FROM MeetingParticipant p
            WHERE p.meetingCardId = :meetingCardId
            ORDER BY p.petId
            """)
    List<Long> findPetIdsByMeetingCardId(@Param("meetingCardId") Long meetingCardId);

    /**
     * 조회·취소 권한 판단용. 방 참가자가 아니라 카드 참여자 기준이라, 방을 떠난 Pet 도
     * 자기가 참여한 카드에는 접근할 수 있다.
     */
    boolean existsByMeetingCardIdAndPetId(Long meetingCardId, Long petId);
}
