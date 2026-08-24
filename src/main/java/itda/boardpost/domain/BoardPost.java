package itda.boardpost.domain;

import itda.common.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "board_posts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardPost extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "board_id", nullable = false) private Long boardId;
    @Column(name = "author_user_id", nullable = false) private Long authorUserId;
    @Column(name = "author_pet_id", nullable = false) private Long authorPetId;
    @Column(name = "neighborhood_code", nullable = false, length = 20) private String neighborhoodCode;
    @Column(nullable = false, length = 120) private String title;
    @Column(nullable = false, columnDefinition = "text") private String content;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PostStatus status;
    @Version @Column(nullable = false) private long version;
    @Column(name = "deleted_at") private Instant deletedAt;

    private BoardPost(Long boardId, Long authorUserId, Long authorPetId, String neighborhoodCode, String title, String content) {
        this.boardId = boardId; this.authorUserId = authorUserId; this.authorPetId = authorPetId;
        this.neighborhoodCode = neighborhoodCode; this.title = title; this.content = content;
        this.status = PostStatus.PUBLISHED;
    }
    public static BoardPost publish(Long boardId, Long authorUserId, Long authorPetId, String neighborhoodCode, String title, String content) {
        return new BoardPost(boardId, authorUserId, authorPetId, neighborhoodCode, title, content);
    }
    public boolean change(String title, String content) {
        boolean changed = !Objects.equals(this.title, title) || !Objects.equals(this.content, content);
        if (changed) { this.title = title; this.content = content; }
        return changed;
    }
    public void markAttachmentsChanged() {
        touchUpdatedAt();
    }
    public void delete(Instant deletedAt) { this.status = PostStatus.DELETED; this.deletedAt = deletedAt; }
}
