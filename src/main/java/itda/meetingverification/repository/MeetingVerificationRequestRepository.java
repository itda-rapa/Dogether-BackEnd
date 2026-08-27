package itda.meetingverification.repository;

import itda.meetingverification.domain.MeetingVerificationRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingVerificationRequestRepository
        extends JpaRepository<MeetingVerificationRequest, UUID> {

    /** 전역 멱등키(pk_meeting_verification_requests). */
    Optional<MeetingVerificationRequest> findByClientRequestId(UUID clientRequestId);
}
