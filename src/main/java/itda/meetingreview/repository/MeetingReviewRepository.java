package itda.meetingreview.repository;

import itda.meetingreview.domain.MeetingReview;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingReviewRepository extends JpaRepository<MeetingReview, Long> {

    /** 전역 멱등키(uk_meeting_review_client_request). 동일 Pet 재요청 식별용. */
    Optional<MeetingReview> findByClientRequestId(UUID clientRequestId);

    /** 같은 (meeting, reviewer_pet) 후기 존재 확인(uk_meeting_review_pet). */
    Optional<MeetingReview> findByMeetingIdAndReviewerPetId(Long meetingId, Long reviewerPetId);
}
