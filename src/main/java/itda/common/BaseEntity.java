package itda.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Marks this aggregate as changed when a domain change is represented outside of its own
     * table. Subclasses must expose a domain-specific operation rather than calling this directly.
     */
    protected final void touchUpdatedAt() {
        Instant now = Instant.now();
        updatedAt = updatedAt == null || now.isAfter(updatedAt)
                ? now
                : updatedAt.plusNanos(1);
    }
}
