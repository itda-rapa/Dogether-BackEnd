package itda.meetingcard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "card_draft_participants")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardDraftParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_draft_id", nullable = false)
    private Long cardDraftId;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    public CardDraftParticipant(Long cardDraftId, Long petId) {
        this.cardDraftId = cardDraftId;
        this.petId = petId;
    }
}
