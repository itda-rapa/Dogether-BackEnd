package itda.friend.domain;

import itda.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "friend_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FriendRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_pet_id", nullable = false)
    private Long requesterPetId;

    @Column(name = "target_pet_id", nullable = false)
    private Long targetPetId;

    @Column(
            name = "pair_low_id",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Long pairLowId;

    @Column(
            name = "pair_high_id",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Long pairHighId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FriendRequestStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    private FriendRequest(
            Long requesterPetId,
            Long targetPetId,
            Instant requestedAt,
            Instant expiresAt
    ) {
        this.requesterPetId = Objects.requireNonNull(
                requesterPetId,
                "requesterPetId must not be null"
        );
        this.targetPetId = Objects.requireNonNull(
                targetPetId,
                "targetPetId must not be null"
        );
        this.requestedAt = Objects.requireNonNull(
                requestedAt,
                "requestedAt must not be null"
        );
        this.expiresAt = Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );
        if (requesterPetId.equals(targetPetId)) {
            throw new IllegalArgumentException(
                    "requesterPetId and targetPetId must be different"
            );
        }
        if (!expiresAt.isAfter(requestedAt)) {
            throw new IllegalArgumentException(
                    "expiresAt must be after requestedAt"
            );
        }
        this.status = FriendRequestStatus.PENDING;
    }

    public static FriendRequest createPending(
            Long requesterPetId,
            Long targetPetId,
            Instant requestedAt,
            Instant expiresAt
    ) {
        return new FriendRequest(
                requesterPetId,
                targetPetId,
                requestedAt,
                expiresAt
        );
    }

    public void accept(Instant respondedAt) {
        requirePending();
        this.respondedAt = Objects.requireNonNull(
                respondedAt,
                "respondedAt must not be null"
        );
        this.status = FriendRequestStatus.ACCEPTED;
    }

    public void reject(Instant respondedAt) {
        requirePending();
        this.respondedAt = Objects.requireNonNull(
                respondedAt,
                "respondedAt must not be null"
        );
        this.status = FriendRequestStatus.REJECTED;
    }

    public void cancel(Instant respondedAt) {
        requirePending();
        this.respondedAt = Objects.requireNonNull(
                respondedAt,
                "respondedAt must not be null"
        );
        this.status = FriendRequestStatus.CANCELED;
    }

    public void expire() {
        requirePending();
        this.status = FriendRequestStatus.EXPIRED;
        this.respondedAt = null;
    }

    public boolean isPendingAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return status == FriendRequestStatus.PENDING
                && expiresAt.isAfter(now);
    }

    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return status == FriendRequestStatus.PENDING
                && !expiresAt.isAfter(now);
    }

    private void requirePending() {
        if (status != FriendRequestStatus.PENDING) {
            throw new IllegalStateException(
                    "FriendRequest must be PENDING"
            );
        }
    }
}
