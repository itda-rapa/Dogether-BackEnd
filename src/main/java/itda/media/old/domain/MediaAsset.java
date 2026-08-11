package itda.media.old.domain;

/*
import itda.common.BaseEntity;
import itda.media.domain.MediaStatus;
import itda.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "media_assets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaAsset extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MediaPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MediaStatus status;

    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(nullable = false)
    private long version;

    private MediaAsset(
            User owner,
            MediaPurpose purpose,
            String objectKey,
            String contentType,
            long sizeBytes,
            Instant expiresAt
    ) {
        this.owner = owner;
        this.purpose = purpose;
        this.status = MediaStatus.PENDING;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.expiresAt = expiresAt;
    }

    public static MediaAsset pending(
            User owner,
            MediaPurpose purpose,
            String objectKey,
            String contentType,
            long sizeBytes,
            Instant expiresAt
    ) {
        return new MediaAsset(
                owner,
                purpose,
                objectKey,
                contentType,
                sizeBytes,
                expiresAt
        );
    }

    public boolean belongsTo(Long userId) {
        return owner.getId().equals(userId);
    }

    public void markUploaded() {
        status = MediaStatus.UPLOADED;
    }

    public void markExpired() {
        status = MediaStatus.EXPIRED;
    }

    public void requestDeletion() {
        if (status != MediaStatus.DELETED) {
            status = MediaStatus.DELETE_REQUESTED;
        }
    }

    public void markDeleted() {
        status = MediaStatus.DELETED;
    }
}
*/
