package itda.media.domain;

import itda.common.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "storage_delete_jobs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_storage_delete_jobs_object_key_reason",
                columnNames = {"object_key", "reason"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorageDeleteJob extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "object_key", nullable = false, length = 512) private String objectKey;
    @Column(name = "object_version_id", length = 1024) private String objectVersionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private StorageDeleteReason reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private StorageDeleteJobStatus status;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_retry_at", nullable = false) private Instant nextRetryAt;
    @Column(name = "last_error", length = 500) private String lastError;
    @Column(name = "claim_token") private java.util.UUID claimToken;
    @Column(name = "claimed_at") private Instant claimedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "deleted_once_at") private Instant deletedOnceAt;

    private StorageDeleteJob(String key, String versionId, StorageDeleteReason reason, Instant eligibleAt) {
        this.objectKey = java.util.Objects.requireNonNull(key);
        this.objectVersionId = versionId;
        this.reason = java.util.Objects.requireNonNull(reason);
        this.status = StorageDeleteJobStatus.PENDING;
        this.nextRetryAt = java.util.Objects.requireNonNull(eligibleAt);
    }

    public static StorageDeleteJob pending(String key, String versionId, StorageDeleteReason reason, Instant eligibleAt) {
        return new StorageDeleteJob(key, versionId, reason, eligibleAt);
    }
}
