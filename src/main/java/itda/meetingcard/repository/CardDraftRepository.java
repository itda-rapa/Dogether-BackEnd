package itda.meetingcard.repository;

import itda.meetingcard.domain.CardDraft;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardDraftRepository extends JpaRepository<CardDraft, Long> {

    @Modifying(flushAutomatically = true)
    @Query("delete from CardDraft d where d.roomId = :roomId")
    int deleteByRoomId(@Param("roomId") long roomId);
}
