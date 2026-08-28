package itda.meetingverification.repository;

import itda.meetingverification.domain.MeetingConfirmationCode;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingConfirmationCodeRepository extends JpaRepository<MeetingConfirmationCode, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MeetingConfirmationCode c where c.meetingCardId = :cardId")
    Optional<MeetingConfirmationCode> findByMeetingCardIdForUpdate(@Param("cardId") Long cardId);

    /** 만료 worker 가 카드 행 잠금 뒤 활성 코드를 무효화한다. 이미 무효화된 코드는 건드리지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MeetingConfirmationCode c set c.invalidatedAt = :now "
            + "where c.meetingCardId = :cardId and c.invalidatedAt is null")
    int invalidateByMeetingCardId(@Param("cardId") Long cardId, @Param("now") Instant now);
}
