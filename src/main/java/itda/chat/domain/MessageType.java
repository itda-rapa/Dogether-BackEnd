package itda.chat.domain;

public enum MessageType {
    TEXT,
    CARD,
    IMAGE,
    VIDEO,
    SETLOG_SHARE,
    MAP,
    SYSTEM;

    public boolean isUserSettable() {
        return this == TEXT || this == IMAGE || this == VIDEO || this == SETLOG_SHARE;
    }
}
