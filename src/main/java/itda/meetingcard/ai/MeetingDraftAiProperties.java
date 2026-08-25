package itda.meetingcard.ai;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * AI 약속 초안 추출 서버 설정. 기본값은 한 곳에서만 정의한다.
 *
 * <p>{@link MeetingCardAiAdapter}(HttpMeetingDraftAiClient)와
 * {@code OpenChatDraftRequestService}가 이 설정을 공유하고, M3 약속 제안 스케줄러는
 * {@code timeout} 을 기준으로 lease 검증을 수행한다. 별도 기본값을 중복 정의하지 않는다.
 */
@Validated
@ConfigurationProperties("app.meeting-card.ai")
public record MeetingDraftAiProperties(
        @DefaultValue("http://127.0.0.1:8000") String baseUrl,
        @NotNull @DefaultValue("5s") Duration timeout,
        @NotNull @DefaultValue("Asia/Seoul") ZoneId zone
) {
}
