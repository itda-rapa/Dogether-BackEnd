package itda.meetingcard.service;

import itda.meetingcard.repository.MeetingCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 두 User 가 차단 관계가 되면 열린 약속 카드를 정리한다.
 *
 * <p>차단은 방을 양쪽에서 404 로 숨기므로, 정리하지 않으면 아무도 취소할 수 없는
 * {@code OPEN} 카드가 영구히 남는다. 차단이 "관계를 정리하는" 행위인데 약속만 살아 있는
 * 것은 앞뒤가 맞지 않는다.
 *
 * <p>{@code FriendBlockCleanupService} 와 같은 위치·같은 호출 시점이며, 차단과 같은
 * 트랜잭션에서 실행되어 함께 성립하거나 함께 롤백된다.
 *
 * <p>SYSTEM 메시지는 남기지 않는다. 방이 양쪽에서 이미 숨겨져 아무도 읽을 수 없고,
 * 취소 사유는 차단 이력으로 남는다.
 */
@Service
@RequiredArgsConstructor
public class MeetingCardBlockCleanupService {

    private final MeetingCardRepository meetingCardRepository;

    /**
     * @param canceledByPetId 차단을 실행한 Pet. {@code ck_meeting_card_cancel} 이 취소 흔적을
     *                        요구하므로 취소자를 비워둘 수 없다.
     * @return 취소된 카드 수
     */
    @Transactional
    public int cancelOpenCardsBetweenUsers(Long userA, Long userB, Long canceledByPetId) {
        return meetingCardRepository.cancelOpenCardsBetweenUsers(userA, userB, canceledByPetId);
    }
}
