CREATE INDEX IF NOT EXISTS idx_network_node_geom
    ON network_node USING GIST (geom);

CREATE INDEX IF NOT EXISTS idx_network_link_geom
    ON network_link USING GIST (geom);

CREATE INDEX IF NOT EXISTS idx_network_node_node_id
    ON network_node (node_id);

CREATE INDEX IF NOT EXISTS idx_toilet_geom_geography
    ON toilet USING GIST ((geom::geography));

CREATE INDEX IF NOT EXISTS idx_poopbag_geom_geography
    ON poopbag USING GIST ((geom::geography));

CREATE INDEX IF NOT EXISTS idx_water_fountain_geom_geography
    ON water_fountain USING GIST ((geom::geography));

CREATE TABLE route_requests (
    id UUID PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pet_id BIGINT NOT NULL REFERENCES pets(id),
    status VARCHAR(20) NOT NULL,
    activity_type VARCHAR(20) NOT NULL,
    priority_type VARCHAR(20) NOT NULL,
    speed_kmh NUMERIC(5, 2) NOT NULL,
    start_node_id BIGINT NOT NULL,
    waypoint_node_id BIGINT,
    destination_node_id BIGINT NOT NULL,
    owner_weight_kg NUMERIC(6, 2) NOT NULL,
    pet_weight_kg NUMERIC(6, 2),
    pet_coefficient NUMERIC(3, 1),
    route_geom geometry(Geometry, 4326),
    route_geojson JSONB,
    total_distance_m NUMERIC(14, 3),
    owner_calories_kcal NUMERIC(14, 3),
    pet_calories_kcal NUMERIC(14, 3),
    average_slope NUMERIC(12, 8),
    duration_minutes NUMERIC(12, 3),
    facilities_json JSONB,
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_route_request_status CHECK
        (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_route_activity_type CHECK
        (activity_type IN ('WALK', 'RUN', 'CYCLE')),
    CONSTRAINT ck_route_priority_type CHECK
        (priority_type IN ('GREEN', 'SLOPE', 'ROAD', 'AMENITY', 'OBSTRUCTION')),
    CONSTRAINT ck_route_speed_positive CHECK (speed_kmh > 0),
    CONSTRAINT ck_route_distinct_endpoints CHECK (start_node_id <> destination_node_id)
);

CREATE INDEX idx_route_requests_owner_created
    ON route_requests (owner_user_id, created_at DESC);

CREATE INDEX idx_route_requests_status_created
    ON route_requests (status, created_at);

CREATE INDEX idx_route_requests_geom
    ON route_requests USING GIST (route_geom);

COMMENT ON COLUMN route_requests.owner_weight_kg IS
    'Request-time snapshot. Defaults to 70kg when users.weight_kg is null.';

