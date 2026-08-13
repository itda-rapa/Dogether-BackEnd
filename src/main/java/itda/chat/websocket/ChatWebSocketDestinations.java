package itda.chat.websocket;

public final class ChatWebSocketDestinations {

    public static final String ENDPOINT = "/ws";
    public static final String APPLICATION_PREFIX = "/app";
    public static final String USER_PREFIX = "/user";
    public static final String CHAT_MESSAGES = "/queue/chat/messages";
    public static final String CHAT_ERRORS = "/queue/errors";
    public static final String USER_CHAT_MESSAGES = USER_PREFIX + CHAT_MESSAGES;
    public static final String USER_CHAT_ERRORS = USER_PREFIX + CHAT_ERRORS;
    public static final String DIRECT_SEND_PREFIX = APPLICATION_PREFIX + "/chat/direct/rooms/";

    private ChatWebSocketDestinations() {
    }
}
