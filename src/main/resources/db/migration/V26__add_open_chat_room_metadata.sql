ALTER TABLE chat_rooms
    ADD COLUMN title VARCHAR(120),
    ADD COLUMN description VARCHAR(500),
    ADD COLUMN owner_pet_id BIGINT,
    ADD COLUMN max_participants INT,
    ADD COLUMN is_public BOOLEAN;

ALTER TABLE chat_rooms
    DROP CONSTRAINT ck_chat_room_origin;

ALTER TABLE chat_rooms
    ADD CONSTRAINT ck_chat_room_origin
        CHECK (origin IN ('GREETING', 'FRIEND', 'OPEN_CHAT')),
    ADD CONSTRAINT ck_chat_room_open_chat_metadata
        CHECK (
            origin <> 'OPEN_CHAT'
            OR (
                type = 'GROUP'
                AND title IS NOT NULL
                AND BTRIM(title) <> ''
                AND owner_pet_id IS NOT NULL
                AND max_participants BETWEEN 2 AND 1000
                AND is_public IS NOT NULL
            )
        ),
    ADD CONSTRAINT fk_chat_room_owner_pet
        FOREIGN KEY (owner_pet_id) REFERENCES pets(id);

CREATE INDEX idx_chat_room_open_chat_list
    ON chat_rooms (status, is_public, created_at DESC, id DESC)
    WHERE type = 'GROUP' AND origin = 'OPEN_CHAT';
