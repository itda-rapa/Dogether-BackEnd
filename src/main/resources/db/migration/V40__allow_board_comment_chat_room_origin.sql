ALTER TABLE chat_rooms
    DROP CONSTRAINT ck_chat_room_origin;

ALTER TABLE chat_rooms
    ADD CONSTRAINT ck_chat_room_origin
        CHECK (origin IN ('GREETING', 'FRIEND', 'BOARD_COMMENT', 'OPEN_CHAT'));
