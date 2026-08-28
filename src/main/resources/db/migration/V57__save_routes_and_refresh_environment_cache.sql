ALTER TABLE route_requests
    ADD COLUMN IF NOT EXISTS saved_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_route_requests_owner_saved
    ON route_requests (owner_user_id, saved_at DESC)
    WHERE saved_at IS NOT NULL;

-- Earlier route environment lookups used projected x/y columns as WGS84 coordinates.
-- Remove only failed cached values so they are recomputed from network_node.geom.
UPDATE route_requests
SET environment_info = NULL
WHERE environment_info ->> 'status' = 'UNAVAILABLE';
