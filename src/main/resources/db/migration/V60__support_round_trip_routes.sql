ALTER TABLE route_requests
    ADD COLUMN IF NOT EXISTS route_mode VARCHAR(20) NOT NULL DEFAULT 'VIA',
    ADD COLUMN IF NOT EXISTS target_distance_m NUMERIC(14, 3);

ALTER TABLE route_requests
    DROP CONSTRAINT IF EXISTS ck_route_distinct_endpoints;

ALTER TABLE route_requests
    ADD CONSTRAINT ck_route_mode
        CHECK (route_mode IN ('VIA', 'ROUND_TRIP')),
    ADD CONSTRAINT ck_route_target_distance
        CHECK (
            (route_mode = 'VIA' AND target_distance_m IS NULL AND start_node_id <> destination_node_id)
            OR
            (route_mode = 'ROUND_TRIP' AND target_distance_m BETWEEN 500 AND 50000
                AND start_node_id = destination_node_id)
        );

COMMENT ON COLUMN route_requests.route_mode IS
    'VIA: start/waypoints/destination, ROUND_TRIP: start plus target distance returning to start.';
COMMENT ON COLUMN route_requests.target_distance_m IS
    'Requested total distance for ROUND_TRIP. The calculator accepts a practical network-dependent tolerance.';
