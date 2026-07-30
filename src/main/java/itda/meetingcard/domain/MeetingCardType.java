package itda.meetingcard.domain;

/**
 * 약속 종류. V14 의 {@code ck_meeting_card_type} 과 값이 일치해야 한다.
 *
 * <p>{@code HOSPITAL} 은 AI 가 별도 코드로 반환하기로 최종 합의된 값이며 {@code OTHER} 로
 * 접어넣지 않는다.
 */
public enum MeetingCardType {
    WALK,
    PLAY,
    HOSPITAL,
    OTHER
}
