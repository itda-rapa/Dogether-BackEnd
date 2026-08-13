package itda.chat.websocket;

public final class ChatWebSocketEventType {

    public static final String CHAT_MESSAGE_CREATED = "CHAT_MESSAGE_CREATED";
    public static final String CHAT_ERROR = "CHAT_ERROR";
    public static final String CHAT_SEND_ACK = "CHAT_SEND_ACK";

    private ChatWebSocketEventType() {
    }
}
