package itda.meetingverification.service;

import itda.meetingverification.MeetingVerificationProperties;
import itda.meetingverification.repository.MeetingConfirmationCodeRepository;
import itda.meetingverification.repository.MeetingVerificationRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 약속 시간창({@code meetAt + meetingTimeWindow})이 지난 미확정 제출(SUBMITTED·CODE_REQUIRED)을
 * EXPIRED 로 전이하고 raw 위치를 scrub 한다. raw GPS 가 이미 null 인 CODE_REQUIRED 는 상태만
 * 전이하며, 해당 카드의 활성 Confirmation Code 도 invalidatedAt 을 기록해 재사용 불가하게 한다.
 * ACCEPTED/REJECTED 는 건드리지 않는다.
 *
 * <p>확정 Meeting(GPS 또는 CODE)이 이미 존재하는 카드는 만료 대상에서 제외해, CODE_REQUIRED 를
 * EXPIRED 로 바꾸거나 Confirmation Code 를 무효화하지 않고 기존 확정 응답을 유지한다.
 *
 * <p>{@code runOnce()} 는 {@code @Transactional} 로 EXPIRED 전이·raw scrub·code invalidation 을
 * 하나의 transaction 으로 commit 한다. Scheduler 는 transaction 밖에서 이 public method 만
 * 호출한다.
 *
 * <p>만료 후보 카드를 {@code FOR UPDATE SKIP LOCKED} 로 선점(잠금)한 뒤 해당 카드의
 * 미확정 행만 expire+scrub 하고 code 를 무효화한다. 이 카드 행 잠금은 GPS submit · Code 서비스의
 * Pair → Card 경계와 같은 잠금이므로, Expiry worker 와 최종 제출이 같은 카드의 verification/
 * code 를 경쟁하지 않는다. lock 순서는 Card → Code row/update 로 Code 서비스와 동일해
 * deadlock cycle 을 만들지 않는다. 여러 worker 가 같은 카드를 중복 선점하지 않는다.
 *
 * <p>실행 주기·배치는 {@link MeetingVerificationProperties.Expiry} typed configuration 으로
 * 주입한다({@code @Value} 사용 없음). {@code batchSize >= 1}, {@code delay > 0} 는
 * application context startup 단계에서 검증되므로 worker 실행 시점에 잘못된 설정을 발견하지
 * 않는다. 대형 catch-up·retention 기능은 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MeetingVerificationExpiryService {

    private final MeetingVerificationRepository meetingVerificationRepository;
    private final MeetingConfirmationCodeRepository meetingConfirmationCodeRepository;
    private final MeetingVerificationProperties properties;
    private final Clock clock;

    @Transactional
    public ExpiryResult runOnce() {
        return runOnce(clock.instant());
    }

    @Transactional
    public ExpiryResult runOnce(Instant now) {
        Instant cutoff = now.minus(properties.meetingTimeWindow());
        int expired = 0;
        for (Long cardId : meetingVerificationRepository.findExpiryCandidateCardIds(
                cutoff, properties.expiry().batchSize())) {
            expired += meetingVerificationRepository.expireUnconfirmedByCard(cardId);
            meetingConfirmationCodeRepository.invalidateByMeetingCardId(cardId, now);
        }
        return new ExpiryResult(expired);
    }

    public record ExpiryResult(int expired) {
    }
}
