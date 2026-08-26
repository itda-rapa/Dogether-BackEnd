package itda.meetingverification.repository;

import itda.meetingverification.domain.Meeting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    /** 카드당 0..1(uk_meeting_card). */
    Optional<Meeting> findByMeetingCardId(Long meetingCardId);
}
