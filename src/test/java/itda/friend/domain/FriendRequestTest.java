package itda.friend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FriendRequestTest {

    private static final Instant REQUESTED_AT =
            Instant.parse("2026-07-30T00:00:00Z");
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-08-06T00:00:00Z");

    @Test
    void createsPendingRequestWithProvidedTimes() {
        FriendRequest request = FriendRequest.createPending(
                1L,
                2L,
                REQUESTED_AT,
                EXPIRES_AT
        );

        assertThat(request.getRequesterPetId()).isEqualTo(1L);
        assertThat(request.getTargetPetId()).isEqualTo(2L);
        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        assertThat(request.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(request.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(request.getRespondedAt()).isNull();
    }

    @Test
    void rejectsInvalidCreationArguments() {
        assertThatThrownBy(() -> FriendRequest.createPending(
                null,
                2L,
                REQUESTED_AT,
                EXPIRES_AT
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FriendRequest.createPending(
                1L,
                1L,
                REQUESTED_AT,
                EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FriendRequest.createPending(
                1L,
                2L,
                REQUESTED_AT,
                REQUESTED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsPendingRequestAtProvidedTime() {
        FriendRequest request = pending();
        Instant respondedAt = REQUESTED_AT.plusSeconds(60);

        request.accept(respondedAt);

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(request.getRespondedAt()).isEqualTo(respondedAt);
    }

    @Test
    void expiresPendingRequestWithoutRespondedAt() {
        FriendRequest request = pending();

        request.expire();

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.EXPIRED);
        assertThat(request.getRespondedAt()).isNull();
    }

    @Test
    void rejectsPendingRequestAtProvidedTime() {
        FriendRequest request = pending();
        Instant respondedAt = REQUESTED_AT.plusSeconds(60);

        request.reject(respondedAt);

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.REJECTED);
        assertThat(request.getRespondedAt()).isEqualTo(respondedAt);
    }

    @Test
    void cancelsPendingRequestAtProvidedTime() {
        FriendRequest request = pending();
        Instant respondedAt = REQUESTED_AT.plusSeconds(60);

        request.cancel(respondedAt);

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.CANCELED);
        assertThat(request.getRespondedAt()).isEqualTo(respondedAt);
    }

    @Test
    void treatsExpirationBoundaryAsExpired() {
        FriendRequest request = pending();

        assertThat(request.isPendingAt(EXPIRES_AT.minusNanos(1))).isTrue();
        assertThat(request.isPendingAt(EXPIRES_AT)).isFalse();
        assertThat(request.isExpiredAt(EXPIRES_AT)).isTrue();
        assertThat(request.isExpiredAt(EXPIRES_AT.plusNanos(1))).isTrue();
    }

    @Test
    void rejectsTerminalStateTransitions() {
        FriendRequest accepted = pending();
        accepted.accept(REQUESTED_AT.plusSeconds(1));

        assertThatThrownBy(accepted::expire)
                .isInstanceOf(IllegalStateException.class);

        FriendRequest expired = pending();
        expired.expire();

        assertThatThrownBy(() -> expired.accept(REQUESTED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        FriendRequest rejected = pending();
        rejected.reject(REQUESTED_AT.plusSeconds(1));

        assertThatThrownBy(() -> rejected.cancel(
                REQUESTED_AT.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class);

        FriendRequest canceled = pending();
        canceled.cancel(REQUESTED_AT.plusSeconds(1));

        assertThatThrownBy(() -> canceled.reject(
                REQUESTED_AT.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class);
    }

    private FriendRequest pending() {
        return FriendRequest.createPending(
                1L,
                2L,
                REQUESTED_AT,
                EXPIRES_AT
        );
    }
}
