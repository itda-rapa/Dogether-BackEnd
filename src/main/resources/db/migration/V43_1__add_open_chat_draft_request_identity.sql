ALTER TABLE card_drafts
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS candidate_index INTEGER;

CREATE UNIQUE INDEX IF NOT EXISTS ux_card_drafts_open_chat_request_candidate
    ON card_drafts (request_id, candidate_index)
    WHERE request_id IS NOT NULL;
