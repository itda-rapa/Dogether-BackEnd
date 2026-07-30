package itda.meetingcard.ai;

import java.time.LocalDate;
import java.util.List;

/**
 * AI 어댑터에 전달하는 추출 명령.
 *
 * @param roomId        채팅방 DB id (10진수 문자열)
 * @param referenceDate Asia/Seoul 기준 오늘
 * @param messages      해당 방의 최근 메시지 목록
 */
public record AiDraftCommand(
        String roomId,
        LocalDate referenceDate,
        List<AiMessage> messages
) {

    /**
     * 명령에 포함되는 메시지 한 건.
     *
     * @param sender  Pet 의 DB id (발화자 구분용)
     * @param content 메시지 본문
     * @param sentAt  Asia/Seoul 오프셋이 포함된 ISO-8601 문자열
     */
    public record AiMessage(
            String sender,
            String content,
            String sentAt
    ) {
    }
}