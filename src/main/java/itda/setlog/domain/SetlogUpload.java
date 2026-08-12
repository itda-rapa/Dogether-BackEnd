package itda.setlog.domain;

import itda.common.BaseEntity;
import itda.pet.domain.Pet;
import itda.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "setlog_uploads")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SetlogUpload extends BaseEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pet_id", nullable = false)
    private Pet pet;

    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "expected_size", nullable = false)
    private long expectedSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SetlogUploadStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    private SetlogUpload(
            UUID id,
            User owner,
            Pet pet,
            String objectKey,
            String contentType,
            long expectedSize,
            Instant expiresAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.pet = Objects.requireNonNull(pet);
        this.objectKey = Objects.requireNonNull(objectKey);
        this.contentType = Objects.requireNonNull(contentType);
        this.expectedSize = expectedSize;
        this.status = SetlogUploadStatus.PRESIGNED;
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public static SetlogUpload presigned(
            UUID id,
            User owner,
            Pet pet,
            String objectKey,
            String contentType,
            long expectedSize,
            Instant expiresAt
    ) {
        if (expectedSize <= 0) {
            throw new IllegalArgumentException("expectedSize must be positive");
        }
        return new SetlogUpload(
                id,
                owner,
                pet,
                objectKey,
                contentType,
                expectedSize,
                expiresAt
        );
    }
}
