ALTER TABLE route_requests
    ADD COLUMN departure_at TIMESTAMPTZ,
    ADD COLUMN environment_info JSONB;

COMMENT ON COLUMN route_requests.environment_info IS
    'Optional informational weather/air-quality snapshot. Never participates in route cost.';

