ALTER TABLE meeting_cards
    ADD COLUMN participant_count INTEGER;

UPDATE meeting_cards card
   SET participant_count = GREATEST(
       1,
       (SELECT COUNT(*) FROM meeting_participants participant
         WHERE participant.meeting_card_id = card.id)
   );

ALTER TABLE meeting_cards
    ALTER COLUMN participant_count SET NOT NULL,
    ADD CONSTRAINT ck_meeting_card_participant_count
        CHECK (participant_count BETWEEN 1 AND 1000);
