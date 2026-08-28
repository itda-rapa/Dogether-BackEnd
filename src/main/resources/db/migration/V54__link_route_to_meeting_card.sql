ALTER TABLE meeting_cards
    ADD COLUMN IF NOT EXISTS route_request_id UUID REFERENCES route_requests(id);

CREATE INDEX IF NOT EXISTS idx_meeting_card_route_request
    ON meeting_cards (route_request_id);
