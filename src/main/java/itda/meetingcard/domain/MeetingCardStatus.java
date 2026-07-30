package itda.meetingcard.domain;

/**
 * 약속 카드 상태. {@code OPEN -> CANCELED} 단방향이며 M1 에 {@code CONFIRMED} 는 없다
 * (03_M1_상태전이.md).
 */
public enum MeetingCardStatus {
    OPEN,
    CANCELED
}
