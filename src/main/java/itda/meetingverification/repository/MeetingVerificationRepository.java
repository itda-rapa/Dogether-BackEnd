package itda.meetingverification.repository;

import itda.meetingverification.domain.MeetingVerification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingVerificationRepository extends JpaRepository<MeetingVerification, Long> {

    /** Pet 당 최신 제출 1건(uk_meeting_verification_pet). */
    Optional<MeetingVerification> findByMeetingCardIdAndParticipantPetId(
            Long meetingCardId, Long participantPetId);

    boolean existsByMeetingCardIdAndParticipantPetId(Long meetingCardId, Long participantPetId);

    /** 카드의 제출 전체(최대 2행). */
    List<MeetingVerification> findAllByMeetingCardId(Long meetingCardId);

    /**
     * GET status 용 단일 projection query. meeting_cards 를 기준으로 내 제출·상대 제출·
     * Meeting 을 한 번의 SQL statement 로 읽어, READ COMMITTED 에서도 불가능한 혼합 상태를
     * 만들지 않는다. raw 좌표는 projection 에 포함하지 않는다. confirmed_at 은 JDBC 드라이버별
     * 반환 타입(OffsetDateTime/Timestamp) 차이를 피하기 위해 epoch millis 로 읽는다.
     */
    @Query(value = """
            SELECT
                mine.status                       AS myStatus,
                counterpart.status                AS counterpartStatus,
                meeting.id                        AS meetingId,
                meeting.verification_method       AS verificationMethod,
                CAST(EXTRACT(EPOCH FROM meeting.confirmed_at) * 1000 AS BIGINT)
                                                  AS confirmedAtEpochMillis,
                meeting.distance_meters           AS distanceMeters
              FROM meeting_cards card
              LEFT JOIN meeting_verifications mine
                     ON mine.meeting_card_id = card.id
                    AND mine.participant_pet_id = :activePetId
              LEFT JOIN meeting_verifications counterpart
                     ON counterpart.meeting_card_id = card.id
                    AND counterpart.participant_pet_id <> :activePetId
              LEFT JOIN meetings meeting
                     ON meeting.meeting_card_id = card.id
             WHERE card.id = :cardId
            """, nativeQuery = true)
    Optional<MeetingStatusProjection> findMeetingStatus(
            @Param("cardId") long cardId,
            @Param("activePetId") long activePetId);

    /**
     * 만료 대상 후보 카드 선점. {@code meet_at < :cutoff} 이고 SUBMITTED 또는 CODE_REQUIRED
     * 제출이 있는 카드 중 아직 Meeting(GPS/CODE)이 없는 카드를 batch 로
     * {@code FOR UPDATE SKIP LOCKED} 잠근다. GPS submit · Confirmation Code 의 Pair → Card
     * 경계와 같은 카드 행 잠금을 공유해 EXPIRED 전이와 최종 제출을 직렬화한다. 여러 worker 가
     * 같은 카드를 중복 선점하지 않는다. 잠긴 카드 행은 이 트랜잭션이 끝날 때까지 유지된다.
     * 확정 Meeting 이 있는 카드는 재응답을 유지해야 하므로 만료 대상에서 제외한다.
     */
    @Query(value = """
            SELECT c.id
              FROM meeting_cards c
             WHERE c.meet_at < :cutoff
               AND EXISTS (
                   SELECT 1
                     FROM meeting_verifications v
                    WHERE v.meeting_card_id = c.id
                      AND v.status IN ('SUBMITTED', 'CODE_REQUIRED')
               )
               AND NOT EXISTS (
                   SELECT 1
                     FROM meetings m
                    WHERE m.meeting_card_id = c.id
               )
             ORDER BY c.id
             LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findExpiryCandidateCardIds(@Param("cutoff") Instant cutoff,
                                          @Param("batchSize") int batchSize);

    /**
     * 한 카드의 미확정 SUBMITTED·CODE_REQUIRED 제출을 EXPIRED 로 전이하고 raw 위치를 scrub
     * 한다. CODE_REQUIRED 는 raw 가 이미 null 이므로 null 대입은 no-op 이다. 호출부가 해당
     * 카드 행을 잠근 뒤 실행하므로 GPS submit · Confirmation Code 와 직렬화된다.
     * ACCEPTED/REJECTED/EXPIRED 는 건드리지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE meeting_verifications
               SET status = 'EXPIRED',
                   latitude = NULL,
                   longitude = NULL,
                   accuracy_meters = NULL,
                   captured_at = NULL,
                   updated_at = now()
             WHERE meeting_card_id = :cardId
               AND status IN ('SUBMITTED', 'CODE_REQUIRED')
            """, nativeQuery = true)
    int expireUnconfirmedByCard(@Param("cardId") long cardId);

    /**
     * CODE Meeting 확정 시 아직 종결되지 않은 SUBMITTED·CODE_REQUIRED 제출을 ACCEPTED 로
     * 전이하고 raw 위치를 scrub 한다. CODE_REQUIRED 는 raw 가 이미 null 이므로 no-op 이다.
     * 호출부가 카드 행을 잠근 뒤, Meeting INSERT 와 같은 transaction 에서 실행한다.
     * ACCEPTED/REJECTED/EXPIRED 등 이미 terminal 인 상태는 덮어쓰지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE meeting_verifications
               SET status = 'ACCEPTED',
                   latitude = NULL,
                   longitude = NULL,
                   accuracy_meters = NULL,
                   captured_at = NULL,
                   updated_at = now()
             WHERE meeting_card_id = :cardId
               AND status IN ('SUBMITTED', 'CODE_REQUIRED')
            """, nativeQuery = true)
    int acceptUnconfirmedByCard(@Param("cardId") long cardId);

    /** GET status 단일 snapshot 의 읽기 전용 projection. raw 좌표는 포함하지 않는다. */
    interface MeetingStatusProjection {

        String getMyStatus();

        String getCounterpartStatus();

        Long getMeetingId();

        String getVerificationMethod();

        Long getConfirmedAtEpochMillis();

        Double getDistanceMeters();
    }
}
