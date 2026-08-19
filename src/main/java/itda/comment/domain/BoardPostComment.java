package itda.comment.domain;

import itda.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "board_post_comments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardPostComment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;

    @Column(name = "author_pet_id", nullable = false)
    private Long authorPetId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private BoardPostComment(
            Long postId,
            Long authorUserId,
            Long authorPetId,
            String content
    ) {
        this.postId = postId;
        this.authorUserId = authorUserId;
        this.authorPetId = authorPetId;
        this.content = content;
    }

    public static BoardPostComment create(
            Long postId,
            Long authorUserId,
            Long authorPetId,
            String content
    ) {
        return new BoardPostComment(postId, authorUserId, authorPetId, content);
    }

    public boolean changeContent(String content) {
        if (Objects.equals(this.content, content)) {
            return false;
        }
        this.content = content;
        return true;
    }

    public void delete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
