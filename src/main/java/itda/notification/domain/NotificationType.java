package itda.notification.domain;

public enum NotificationType {
    OPEN_CHAT_INVITE,
    BOARD_POST_LIKE,
    BOARD_POST_HELPFUL,
    BOARD_COMMENT_HELPFUL,
    BOARD_COMMENT_CREATED,
    BOARD_REPLY_CREATED,
    SETLOG_LIKE,
    SETLOG_CUTE;

    public boolean isReaction() {
        return this == BOARD_POST_LIKE
                || this == BOARD_POST_HELPFUL
                || this == BOARD_COMMENT_HELPFUL
                || this == SETLOG_LIKE
                || this == SETLOG_CUTE;
    }
}
