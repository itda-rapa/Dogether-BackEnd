package itda.meetingcard.repository;

import itda.meetingcard.domain.CardDraft;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardDraftRepository extends JpaRepository<CardDraft, Long> {
}
