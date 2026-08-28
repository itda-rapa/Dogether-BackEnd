CREATE TABLE ai_chat_route_jobs (
    route_request_id UUID PRIMARY KEY REFERENCES route_requests(id) ON DELETE CASCADE,
    room_id BIGINT NOT NULL REFERENCES chat_rooms(id) ON DELETE CASCADE,
    requester_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requester_pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    last_error VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_ai_chat_route_jobs_status
        CHECK (status IN ('PROCESSING', 'SHARED', 'FAILED'))
);

CREATE INDEX idx_ai_chat_route_jobs_pending
    ON ai_chat_route_jobs (created_at)
    WHERE status = 'PROCESSING';
