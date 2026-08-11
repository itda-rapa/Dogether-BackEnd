package itda.friend.service;

import itda.friend.dto.response.FriendRequestResponse;
import java.util.Objects;

public sealed interface FriendRequestActionResult
        permits FriendRequestActionResult.Accepted,
        FriendRequestActionResult.Rejected,
        FriendRequestActionResult.Terminal {

    record Accepted(
            FriendRequestResponse response
    ) implements FriendRequestActionResult {

        public Accepted {
            Objects.requireNonNull(response, "response must not be null");
        }
    }

    record Rejected(
            FriendRequestResponse response
    ) implements FriendRequestActionResult {

        public Rejected {
            Objects.requireNonNull(response, "response must not be null");
        }
    }

    enum Terminal implements FriendRequestActionResult {
        CANCELED,
        EXPIRED
    }
}
