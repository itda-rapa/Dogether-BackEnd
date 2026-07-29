package itda.setlog.domain;

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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Entity
@Table(name = "setlog_reactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SetlogReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "setlog_id", nullable = false)
    private Setlog setlog;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reactor_pet_id", nullable = false)
    private Pet reactorPet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReactionType type;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private SetlogReaction(
            Setlog setlog,
            Pet reactorPet,
            ReactionType type
    ) {
        this.setlog = setlog;
        this.reactorPet = reactorPet;
        this.type = type;
    }

    public static SetlogReaction create(
            Setlog setlog,
            Pet reactorPet,
            ReactionType type
    ) {
        return new SetlogReaction(setlog, reactorPet, type);
    }
}
