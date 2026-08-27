package itda.chat.dto.request;

import jakarta.validation.constraints.NotNull;

public record PlaceIntentRequest(@NotNull Long triggerMessageId) {
}
