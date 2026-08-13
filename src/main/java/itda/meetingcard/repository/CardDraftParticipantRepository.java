package itda.meetingcard.repository;

import itda.meetingcard.domain.CardDraftParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardDraftParticipantRepository extends JpaRepository<CardDraftParticipant, Long> {

    List<CardDraftParticipant> findByCardDraftIdOrderByIdAsc(Long cardDraftId);
}
