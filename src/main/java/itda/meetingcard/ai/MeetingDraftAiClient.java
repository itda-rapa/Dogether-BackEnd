package itda.meetingcard.ai;

/**
 * AI 서버에 약속 초안 추출을 요청하는 클라이언트 계약.
 */
public interface MeetingDraftAiClient {

    /**
     * AI 서버에 채팅 메시지에서 약속 정보를 추출하도록 요청한다.
     *
     * @param command 추출 명령
     * @return 추출 결과 (절대 예외를 던지지 않음)
     */
    AiDraftResult extract(AiDraftCommand command);
}