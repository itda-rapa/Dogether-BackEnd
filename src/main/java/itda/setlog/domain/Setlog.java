package itda.setlog.domain;

import itda.common.BaseEntity;
import itda.media.domain.Media;
import itda.pet.domain.Pet;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "setlogs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Setlog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_pet_id", nullable = false)
    private Pet authorPet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable = false)
    private Media media;

    @Column(length = 500)
    private String caption;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SetlogStatus status;

    @Column(name = "reaction_cute_count", nullable = false)
    private int cuteCount;

    @Column(name = "reaction_like_count", nullable = false)
    private int likeCount;

    @Column(name = "is_seed", nullable = false)
    private boolean seed;

    private Setlog(
            Pet authorPet,
            Media media,
            String caption,
            boolean seed
    ) {
        this.authorPet = authorPet;
        this.media = media;
        this.caption = caption;
        this.status = SetlogStatus.VISIBLE;
        this.cuteCount = 0;
        this.likeCount = 0;
        this.seed = seed;
    }

    public static Setlog createSeed(
            Pet authorPet,
            Media media,
            String caption
    ) {
        return new Setlog(authorPet, media, caption, true);
    }

    public static Setlog createUser(
            Pet authorPet,
            Media media,
            String caption
    ) {
        return new Setlog(authorPet, media, caption, false);
    }

    public void incrementReaction(ReactionType type) {
        switch (type) {
            case CUTE -> cuteCount++;
            case LIKE -> likeCount++;
        }
    }

    public void decrementReaction(ReactionType type) {
        switch (type) {
            case CUTE -> cuteCount = Math.max(0, cuteCount - 1);
            case LIKE -> likeCount = Math.max(0, likeCount - 1);
        }
    }
}
