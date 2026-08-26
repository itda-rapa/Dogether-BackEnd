package itda.meetingsuggestion;

import itda.meetingcard.ai.MeetingDraftAiProperties;
import java.time.Duration;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 앱 시작 시 Scheduler 설정이 제품 계약(매일 07:00 Asia/Seoul 고정)과 일치하는지
 * 검증한다.
 *
 * <p>기능이 비활성({@code app.meeting-suggestion.enabled=false})이면 검증을 수행하지
 * 않는다. Scheduler 가 동작하지 않으므로 zone/cron/lease 설정이 잘못돼도 기존 앱 기동을
 * 막지 않는다.
 *
 * <p>기능이 활성이면 세 가지를 검증한다.
 * <ul>
 *   <li><b>KST zone</b>: {@code app.meeting-suggestion.zone}(전날 범위·메시지 시각·중복
 *       판정 KST 날짜)과 {@code app.meeting-card.ai.zone}(후보 date/time 을
 *       {@code combinedInstant} 로 파싱하는 존)이 <b>각각</b> {@code Asia/Seoul} 이어야
 *       한다. 둘 다 같기만 하면 non-KST 로도 통과할 수 있으므로 KST 정본과 각각 비교해
 *       거부한다. 다르면 같은 후보의 "KST 날짜"가 두 존에서 서로 다르게 계산된다.</li>
 *   <li><b>07:00 cron</b>: {@code app.meeting-suggestion.scheduler-cron} 은 정확히
 *       {@code 0 0 7 * * *} (매일 07:00 Asia/Seoul) 이어야 한다. 공백 포함 정확히
 *       일치해야 하며, 다른 시간으로 바꾸면 제품 계약에서 벗어난다.</li>
 *   <li><b>lease</b>: {@code app.meeting-suggestion.lease} 는 보수적 전체 HTTP 대기
 *       budget 보다 엄격히 길어야 한다. {@code HttpMeetingDraftAiClient} 는 connect 와
 *       read timeout 을 각각 {@code app.meeting-card.ai.timeout} 으로 두므로 전체 요청
 *       budget 은 {@code 2 x timeout} 이다. lease 가 이보다 짧거나 같으면 AI 호출 중
 *       lease 가 만료돼 새 claim holder 가 같은 Scan 을 중복 처리한다(fingerprint
 *       멱등성으로 데이터는 보호되지만 AI 호출이 중복되고 attempts 가 부풀어 조기
 *       FAILED_FINAL 로 갈 수 있다).</li>
 * </ul>
 * 잘못된 조합은 시작 시 명확한 오류로 실패한다.
 *
 * <p>timeout 기본값은 {@link MeetingDraftAiProperties} 한 곳에만 있고 여기서는 실제
 * 바인딩된 값을 그대로 사용한다(별도 기본값 복제 없음).
 */
@Component
@RequiredArgsConstructor
public class MeetingSuggestionConfigurationValidator implements InitializingBean {

    /** 제품 계약 정본: 매일 07:00 Asia/Seoul. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String MORNING_CRON = "0 0 7 * * *";

    private final MeetingSuggestionProperties properties;
    private final MeetingDraftAiProperties aiProperties;

    @Override
    public void afterPropertiesSet() {
        if (!properties.enabled()) {
            return;
        }
        if (!KST.equals(properties.zone())) {
            throw new IllegalStateException(
                    "app.meeting-suggestion.zone (" + properties.zone()
                            + ") must be Asia/Seoul: the morning suggestion scheduler contract "
                            + "fixes daily 07:00 KST scanning (windows, sentAt, duplicate-check "
                            + "KST date)");
        }
        if (!KST.equals(aiProperties.zone())) {
            throw new IllegalStateException(
                    "app.meeting-card.ai.zone (" + aiProperties.zone()
                            + ") must be Asia/Seoul: candidate instants are parsed in this zone "
                            + "and the scheduler contract is KST-based");
        }
        if (!MORNING_CRON.equals(properties.schedulerCron())) {
            throw new IllegalStateException(
                    "app.meeting-suggestion.scheduler-cron ('" + properties.schedulerCron()
                            + "') must be exactly '" + MORNING_CRON
                            + "' (daily 07:00 Asia/Seoul); other schedules break the contract");
        }
        // HttpMeetingDraftAiClient 는 connect/read timeout 을 각각 적용하므로 전체
        // HTTP 대기 budget 은 2 x timeout 이다.
        Duration conservativeBudget = aiProperties.timeout().multipliedBy(2);
        if (properties.lease().compareTo(conservativeBudget) <= 0) {
            throw new IllegalStateException(
                    "app.meeting-suggestion.lease (" + properties.lease()
                            + ") must be strictly longer than the conservative AI HTTP wait budget "
                            + "(2 x app.meeting-card.ai.timeout = " + conservativeBudget
                            + "); otherwise a scan can be reclaimed while its AI call is in flight");
        }
    }
}
