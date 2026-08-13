package itda.meetingcard.ai;

import itda.meetingcard.domain.CardDraftFallbackReason;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 추출을 {@link MeetingDraftAiClient} 계약으로 감싸는 어댑터.
 *
 * <p>여섯 가지 AI 응답을 모두 {@link AiDraftResult} 로 매핑하며, 어떤 경우에도 예외를 던지지 않는다.
 *
 * <h3>6-outcome table</h3>
 * <table>
 *   <tr><th>AI outcome</th><th>fallbackReason</th><th>extracted fields</th></tr>
 *   <tr><td>full or partial extraction</td><td>null</td><td>whatever came back, nulls kept</td></tr>
 *   <tr><td>200 with []</td><td>null</td><td>all null</td></tr>
 *   <tr><td>unknown/unmappable meeting_type</td><td>null</td><td>cardType null, other fields kept</td></tr>
 *   <tr><td>HTTP 504 or local 5s timeout</td><td>TIMEOUT</td><td>all null</td></tr>
 *   <tr><td>HTTP 502, connection failure, malformed</td><td>MODEL_ERROR</td><td>all null</td></tr>
 *   <tr><td>more than one array element</td><td>null</td><td>all candidates kept in order</td></tr>
 * </table>
 */
@Component
public class MeetingCardAiAdapter implements MeetingDraftAiClient {

    private static final Logger log = LoggerFactory.getLogger(MeetingCardAiAdapter.class);

    private final HttpMeetingDraftAiClient httpClient;
    private final ZoneId zoneId;

    // 생성자가 둘이라 Spring 이 어느 쪽을 쓸지 알 수 없어 기본 생성자를 찾다가 실패한다.
    // 주입 대상을 명시한다.
    @Autowired
    public MeetingCardAiAdapter(
            @Value("${app.meeting-card.ai.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${app.meeting-card.ai.timeout:5s}") java.time.Duration timeout,
            @Value("${app.meeting-card.ai.zone:Asia/Seoul}") ZoneId zoneId) {
        this.httpClient = new HttpMeetingDraftAiClient(baseUrl, timeout);
        this.zoneId = zoneId;
    }

    /**
     * 테스트에서 시간대를 제어할 수 있는 생성자.
     */
    MeetingCardAiAdapter(HttpMeetingDraftAiClient httpClient, ZoneId zoneId) {
        this.httpClient = httpClient;
        this.zoneId = zoneId;
    }

    @Override
    public AiDraftResult extract(AiDraftCommand command) {
        AiExtractRequest request = toRequest(command);
        try {
            List<AiExtractResponse> raw = httpClient.call(request);
            return mapResponse(raw);
        } catch (HttpMeetingDraftAiClient.HttpMeetingDraftAiClientException e) {
            log.warn("AI extract client error: {}", e.getMessage());
            return AiDraftResult.fallback(e.getFallbackReason());
        } catch (Exception e) {
            log.warn("AI extract unexpected error", e);
            return AiDraftResult.fallback(CardDraftFallbackReason.MODEL_ERROR);
        }
    }

    private AiExtractRequest toRequest(AiDraftCommand command) {
        List<AiExtractMessage> messages = command.messages().stream()
                .map(m -> new AiExtractMessage(m.sender(), m.content(), m.sentAt()))
                .toList();
        return new AiExtractRequest(command.roomId(), command.referenceDate().toString(), messages);
    }

    AiDraftResult mapResponse(List<AiExtractResponse> raw) {
        return AiDraftResultMapper.map(raw, zoneId);
    }
}
