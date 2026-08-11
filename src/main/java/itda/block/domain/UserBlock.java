package itda.block.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_blocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blocker_user_id", nullable = false)
    private Long blockerUserId;

    @Column(name = "blocked_user_id", nullable = false)
    private Long blockedUserId;

    @Column(name = "source_pet_id")
    private Long sourcePetId;

    @Column(name = "target_pet_id")
    private Long targetPetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UserBlock(Long blockerUserId, Long blockedUserId, Long sourcePetId, Long targetPetId) {
        this.blockerUserId = blockerUserId;
        this.blockedUserId = blockedUserId;
        this.sourcePetId = sourcePetId;
        this.targetPetId = targetPetId;
        this.createdAt = Instant.now();
    }
}