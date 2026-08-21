package itda.chat.domain;

public enum MessageType {
    TEXT,
    CARD,
    IMAGE,
    VIDEO,
    SETLOG_SHARE,
    SYSTEM;

    public boolean isUserSettable() {
        return this == TEXT || this == IMAGE || this == VIDEO || this == SETLOG_SHARE;
    }
}