package itda.chat.websocket;

import java.security.Principal;

public record WebSocketPrincipal(Long userId) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
