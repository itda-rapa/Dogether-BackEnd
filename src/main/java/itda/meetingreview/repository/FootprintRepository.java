package itda.meetingreview.repository;

import itda.meetingreview.domain.Footprint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FootprintRepository extends JpaRepository<Footprint, Long> {

    /** 일일 중복 판단·조회(uk_footprint_pet_date). */
    Optional<Footprint> findByReceiverPetIdAndEarnedDate(Long receiverPetId, LocalDate earnedDate);

    /** 멱등 재요청 시 이 Meeting·Pet 의 발자국 조회(uk_footprint_meeting_pet). */
    Optional<Footprint> findByMeetingIdAndReceiverPetId(Long meetingId, Long receiverPetId);

    /**
     * 같은 Pet·같은 날짜 발자국이 없을 때만 삽입한다(uk_footprint_pet_date 최종 방어선).
     *
     * <p>동시 요청이 먼저 커밋해 일일 중복이 벌어진 경우 {@code ON CONFLICT DO NOTHING} 이
     * 문장 단위로 충돌을 흡수한다(0 반환). PostgreSQL 은 문장 오류 시 트랜잭션 전체가
     * aborted 되므로, 충돌을 예외로 내보내면 후기까지 롤백된다. ON CONFLICT 를 쓰면
     * "후기는 정상 저장, 발자국은 기존 한 건 재사용"으로 수렴할 수 있다.
     *
     * <p>{@code ON CONFLICT} 에 대상 제약을 명시하지 않는 형태는 H2(테스트)·PostgreSQL
     * 양쪽에서 동작한다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO footprints (meeting_id, receiver_pet_id, counterpart_pet_id,
                                    earned_date, created_at, updated_at)
            VALUES (:meetingId, :receiverPetId, :counterpartPetId, :earnedDate, now(), now())
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfDailyAbsent(@Param("meetingId") Long meetingId,
                            @Param("receiverPetId") Long receiverPetId,
                            @Param("counterpartPetId") Long counterpartPetId,
                            @Param("earnedDate") LocalDate earnedDate);

    /**
     * 내 Active Pet 발자국 첫 페이지. (createdAt DESC, id DESC).
     * SetlogRepository 와 같은 첫 페이지/이후 페이지 분리 패턴이다.
     */
    @Query("""
            SELECT footprint
            FROM Footprint footprint
            WHERE footprint.receiverPetId = :receiverPetId
            ORDER BY footprint.createdAt DESC, footprint.id DESC
            """)
    List<Footprint> findReceivedFirstPage(
            @Param("receiverPetId") Long receiverPetId,
            Pageable pageable);

    /** 내 Active Pet 발자국 이후 페이지. (createdAt DESC, id DESC) 커서. */
    @Query("""
            SELECT footprint
            FROM Footprint footprint
            WHERE footprint.receiverPetId = :receiverPetId
              AND (
                  footprint.createdAt < :cursorCreatedAt
                  OR (footprint.createdAt = :cursorCreatedAt AND footprint.id < :cursorId)
              )
            ORDER BY footprint.createdAt DESC, footprint.id DESC
            """)
    List<Footprint> findReceivedAfter(
            @Param("receiverPetId") Long receiverPetId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
