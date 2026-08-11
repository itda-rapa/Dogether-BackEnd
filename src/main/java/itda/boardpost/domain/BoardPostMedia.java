package itda.boardpost.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "board_post_media")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardPostMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "media_id", nullable = false)
    private Long mediaId;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private BoardPostMedia(Long postId, Long mediaId, int displayOrder) {
        this.postId = postId;
        this.mediaId = mediaId;
        this.displayOrder = displayOrder;
    }

    public static BoardPostMedia attach(Long postId, Long mediaId, int displayOrder) {
        return new BoardPostMedia(postId, mediaId, displayOrder);
    }

    @PrePersist
    private void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
