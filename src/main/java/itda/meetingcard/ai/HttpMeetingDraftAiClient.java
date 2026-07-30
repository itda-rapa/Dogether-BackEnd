package itda.meetingcard.ai;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI 서버 {@code POST /api/v1/meeting-drafts/extract} 의 원시 HTTP 호출을 담당한다.
 *
 * <p>이 클래스는 도메인 로직을 포함하지 않으며, HTTP 상태 코드와 응답 본문만 처리한다.
 * 여섯 가지 결과 매핑은 {@link MeetingCardAiAdapter} 가 담당한다.
 */
class HttpMeetingDraftAiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMeetingDraftAiClient.class);

    private final RestClient restClient;

    HttpMeetingDraftAiClient(String baseUrl, Duration timeout) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(timeout))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }

    /**
     * AI 서버에 POST 요청을 보내고 응답 본문을 역직렬화해 반환한다.
     *
     * @param request 전송할 요청 본문
     * @return 역직렬화된 응답 리스트 (null 또는 빈 리스트 가능)
     * @throws HttpMeetingDraftAiClientException HTTP 502, 504 등 처리 가능한 오류
     * @throws RuntimeException                   연결 실패, 타임아웃, 역직렬화 오류 등
     */
    List<AiExtractResponse> call(AiExtractRequest request) {
        log.debug("Calling AI extract: room_id={}", request.roomId());
        return restClient.post()
                .uri("/api/v1/meeting-drafts/extract")
                .body(request)
                .retrieve()
                .onStatus(status -> status.value() == 504,
                        (req, resp) -> {
                            throw HttpMeetingDraftAiClientException.timeout();
                        })
                .onStatus(status -> status.value() == 502,
                        (req, resp) -> {
                            throw HttpMeetingDraftAiClientException.modelError("HTTP 502");
                        })
                .body(new ParameterizedListTypeReference());
    }

    /**
     * RestClient 가 List<AiExtractResponse> 역직렬화에 사용하는 타입 참조.
     */
    private static class ParameterizedListTypeReference
            extends ParameterizedTypeReference<List<AiExtractResponse>> {
    }

    /**
     * HTTP 상태 코드에 따라 fallback reason 을 전달하는 내부 예외.
     */
    static class HttpMeetingDraftAiClientException extends RuntimeException {
        private final transient itda.meetingcard.domain.CardDraftFallbackReason fallbackReason;

        HttpMeetingDraftAiClientException(String message,
                                          itda.meetingcard.domain.CardDraftFallbackReason fallbackReason) {
            super(message);
            this.fallbackReason = fallbackReason;
        }

        itda.meetingcard.domain.CardDraftFallbackReason getFallbackReason() {
            return fallbackReason;
        }

        static HttpMeetingDraftAiClientException timeout() {
            return new HttpMeetingDraftAiClientException(
                    "AI server timeout (504)", itda.meetingcard.domain.CardDraftFallbackReason.TIMEOUT);
        }

        static HttpMeetingDraftAiClientException modelError(String detail) {
            return new HttpMeetingDraftAiClientException(
                    "AI model error: " + detail, itda.meetingcard.domain.CardDraftFallbackReason.MODEL_ERROR);
        }
    }
}