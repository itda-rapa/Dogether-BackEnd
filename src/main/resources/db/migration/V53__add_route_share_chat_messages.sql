ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS shared_route_id UUID REFERENCES route_requests(id);

CREATE INDEX IF NOT EXISTS idx_chat_message_shared_route
    ON chat_messages (shared_route_id);

ALTER TABLE chat_messages DROP CONSTRAINT ck_chat_message_type;
ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_message_type
    CHECK (type IN ('TEXT', 'CARD', 'IMAGE', 'VIDEO', 'SETLOG_SHARE', 'ROUTE_SHARE', 'MAP', 'SYSTEM'));

ALTER TABLE chat_messages DROP CONSTRAINT ck_chat_message_payload;
ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_message_payload CHECK (
    (type = 'TEXT' AND sender_type = 'PET'
        AND body IS NOT NULL AND BTRIM(body) <> ''
        AND meeting_card_id IS NULL AND shared_setlog_id IS NULL AND shared_route_id IS NULL
        AND map_trigger_message_id IS NULL AND map_category IS NULL AND map_facilities_json IS NULL)
    OR
    (type = 'CARD' AND sender_type = 'PET'
        AND body IS NULL AND meeting_card_id IS NOT NULL
        AND shared_setlog_id IS NULL AND shared_route_id IS NULL
        AND map_trigger_message_id IS NULL AND map_category IS NULL AND map_facilities_json IS NULL)
    OR
    (type = 'SYSTEM' AND sender_type = 'SYSTEM'
        AND body IS NOT NULL AND BTRIM(body) <> ''
        AND meeting_card_id IS NULL AND shared_setlog_id IS NULL AND shared_route_id IS NULL
        AND map_trigger_message_id IS NULL AND map_category IS NULL AND map_facilities_json IS NULL)
    OR
    (type IN ('IMAGE', 'VIDEO') AND sender_type = 'PET'
        AND body IS NULL AND meeting_card_id IS NULL
        AND shared_setlog_id IS NULL AND shared_route_id IS NULL
        AND map_trigger_message_id IS NULL AND map_category IS NULL AND map_facilities_json IS NULL)
    OR
    (type = 'SETLOG_SHARE' AND sender_type = 'PET'
        AND body IS NULL AND meeting_card_id IS NULL
        AND shared_setlog_id IS NOT NULL AND shared_route_id IS NULL
        AND map_trigger_message_id IS NULL AND map_category IS NULL AND map_facilities_json IS NULL)
    OR
    (type = 'ROUTE_SHARE' AND sender_type = 'PET'
        AND body IS NULL AND meeting_card_id IS NULL
        AND shared_setlog_id IS NULL AND shared_route_id IS NOT NULL
        AND map_trigger_message_id IS NULL AND map_category IS NULL AND map_facilities_json IS NULL)
    OR
    (type = 'MAP' AND sender_type = 'PET'
        AND body IS NULL AND meeting_card_id IS NULL
        AND shared_setlog_id IS NULL AND shared_route_id IS NULL
        AND map_trigger_message_id IS NOT NULL
        AND map_category IS NOT NULL AND BTRIM(map_category) <> ''
        AND map_facilities_json IS NOT NULL AND BTRIM(map_facilities_json) <> '')
);
