package itda.greeting.domain;

import itda.pet.domain.Pet;
import itda.setlog.domain.Setlog;
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
@Table(name = "greetings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Greeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_pet_id", nullable = false)
    private Pet fromPet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_pet_id", nullable = false)
    private Pet toPet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "setlog_id", nullable = false)
    private Setlog setlog;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GreetingStatus status;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Greeting(
            Pet fromPet,
            Pet toPet,
            Setlog setlog,
            Long roomId,
            Instant expiresAt
    ) {
        this.fromPet = fromPet;
        this.toPet = toPet;
        this.setlog = setlog;
        this.roomId = roomId;
        this.status = GreetingStatus.SENT;
        this.expiresAt = expiresAt;
    }

    public static Greeting send(
            Pet fromPet,
            Pet toPet,
            Setlog setlog,
            Long roomId,
            Instant expiresAt
    ) {
        return new Greeting(
                fromPet,
                toPet,
                setlog,
                roomId,
                expiresAt
        );
    }

    public void markResponded(Instant respondedAt) {
        if (status == GreetingStatus.SENT) {
            status = GreetingStatus.RESPONDED;
            this.respondedAt = respondedAt;
        }
    }
}
