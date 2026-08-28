ALTER TABLE route_requests
    ADD COLUMN IF NOT EXISTS waypoint_node_ids BIGINT[] NOT NULL DEFAULT '{}';

UPDATE route_requests
SET waypoint_node_ids = ARRAY[waypoint_node_id]
WHERE waypoint_node_id IS NOT NULL
  AND cardinality(waypoint_node_ids) = 0;
