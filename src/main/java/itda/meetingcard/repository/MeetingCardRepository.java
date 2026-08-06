package itda.meetingcard.repository;

import itda.meetingcard.domain.MeetingCard;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingCardRepository extends JpaRepository<MeetingCard, Long> {

    @Modifying(flushAutomatically = true)
    @Query("delete from MeetingCard c where c.roomId = :roomId")
    int deleteByRoomId(@Param("roomId") long roomId);

    /**
     * 취소용 조회. 행 잠금을 잡아 양쪽 Pet 이 동시에 취소해도 상태 전이가 한 번만
     * 성공하게 한다.
     *
     * <p>잠금 없이 조회한 뒤 상태를 검사하면 두 트랜잭션이 모두 {@code OPEN} 을 읽고
     * 둘 다 취소에 성공해 SYSTEM 메시지가 두 건 생긴다. 패자는
     * {@code uk_chat_message_client} 에 걸려 죽거나, 결정적 clientMessageId 를 쓰면
     * 조용히 멱등 처리되어 아무도 실패하지 않는다. 둘 다 계약 위반이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MeetingCard c WHERE c.id = :cardId")
    Optional<MeetingCard> findByIdForUpdate(@Param("cardId") Long cardId);

    /**
     * 같은 초안으로 만든 카드가 이미 있는지 확인한다. DB 의
     * {@code uk_meeting_card_source_draft} 가 최종 방어선이고 이건 사용자에게 400 을
     * 돌려주기 위한 선검사다.
     */
    boolean existsBySourceDraftId(Long sourceDraftId);

    /**
     * 차단 시 두 User 사이의 열린 카드를 모두 취소한다.
     *
     * <p>차단하면 방이 양쪽에서 404 로 숨겨지므로 아무도 그 카드를 취소할 수 없게 된다.
     * 정리하지 않으면 {@code OPEN} 상태로 영구히 남는다. Friendship 삭제·PENDING 요청
     * 취소와 같은 취급이다.
     *
     * <p>차단은 User 단위인데 방은 Pet pair 단위라, UI 에서 고른 두 Pet 만 보면 나머지
     * Pet 으로 만든 카드가 살아남는다. 그래서 pets 를 조인해 소유자 기준으로 훑는다.
     *
     * <p>{@code ck_meeting_card_cancel} 이 취소 흔적을 요구하므로 차단을 실행한 Pet 을
     * 취소자로 기록한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE meeting_cards card
               SET status = 'CANCELED',
                   canceled_by_pet_id = :canceledByPetId,
                   canceled_at = now(),
                   updated_at = now()
             WHERE card.status = 'OPEN'
               AND EXISTS (
                   SELECT 1
                     FROM chat_rooms room
                     JOIN pets low_pet ON low_pet.id = room.pet_low_id
                     JOIN pets high_pet ON high_pet.id = room.pet_high_id
                    WHERE room.id = card.room_id
                      AND (
                          (low_pet.owner_user_id = :userA
                              AND high_pet.owner_user_id = :userB)
                          OR
                          (low_pet.owner_user_id = :userB
                              AND high_pet.owner_user_id = :userA)
                      )
               )
            """, nativeQuery = true)
    int cancelOpenCardsBetweenUsers(
            @Param("userA") Long userA,
            @Param("userB") Long userB,
            @Param("canceledByPetId") Long canceledByPetId);
}
