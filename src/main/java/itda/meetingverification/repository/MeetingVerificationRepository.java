package itda.meetingverification.repository;

import itda.meetingverification.domain.MeetingVerification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingVerificationRepository extends JpaRepository<MeetingVerification, Long> {

    /** 전역 멱등키(uk_meeting_verification_client_request). 동일 Pet 재요청 식별용. */
    Optional<MeetingVerification> findByClientRequestId(UUID clientRequestId);

    /** Pet 당 제출 1건(uk_meeting_verification_pet). */
    Optional<MeetingVerification> findByMeetingCardIdAndParticipantPetId(
            Long meetingCardId, Long participantPetId);

    boolean existsByMeetingCardIdAndParticipantPetId(Long meetingCardId, Long participantPetId);
}
