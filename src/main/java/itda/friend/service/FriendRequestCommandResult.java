package itda.friend.service;

import itda.friend.dto.response.FriendRequestResponse;

public record FriendRequestCommandResult(
        FriendRequestResponse response,
        Outcome outcome
) {

    public boolean created() {
        return outcome == Outcome.CREATED;
    }

    public enum Outcome {
        CREATED,
        AUTO_ACCEPTED
    }
}
