-- Chat-room lifecycle maintenance scans these predicates repeatedly.
CREATE INDEX ix_reports_room_id
    ON reports (room_id);

CREATE INDEX ix_greetings_sent_expiry
    ON greetings (expires_at, id)
    WHERE status = 'SENT';

CREATE INDEX ix_chat_rooms_active_direct_last_message
    ON chat_rooms (last_message_at, id)
    WHERE type = 'DIRECT' AND status = 'ACTIVE';
