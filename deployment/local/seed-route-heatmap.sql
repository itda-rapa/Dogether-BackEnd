-- Local/demo data only. This file is intentionally outside Flyway migrations.
--
-- It clones existing, valid saved routes so the route heatmap has an obvious
-- high/medium/low frequency distribution. Re-running is safe: rows created by
-- this dataset are removed first and recreated with deterministic UUIDs.

BEGIN;

DELETE FROM route_requests
WHERE environment_info ->> 'mockDataset' = 'route-heatmap-frequency-v1';

WITH ranked_routes AS (
    SELECT route.*,
           row_number() OVER (ORDER BY route.saved_at DESC, route.id) AS frequency_rank
    FROM route_requests route
    WHERE route.status = 'COMPLETED'
      AND route.saved_at IS NOT NULL
      AND route.route_geom IS NOT NULL
      AND route.route_geojson -> 'geometry' IS NOT NULL
      AND COALESCE(route.environment_info ->> 'mockDataset', '') <> 'route-heatmap-frequency-v1'
),
frequency_plan(frequency_rank, clone_count) AS (
    VALUES
        (1, 160), -- very hot
        (2, 60),  -- hot
        (3, 18),  -- warm
        (4, 5),   -- cool
        (5, 1)    -- cold
),
mock_rows AS (
    SELECT base.*,
           plan.frequency_rank AS mock_frequency_rank,
           clone_no
    FROM ranked_routes base
    JOIN frequency_plan plan USING (frequency_rank)
    CROSS JOIN LATERAL generate_series(1, plan.clone_count) clone_no
)
INSERT INTO route_requests (
    id,
    owner_user_id,
    pet_id,
    status,
    activity_type,
    priority_type,
    speed_kmh,
    start_node_id,
    waypoint_node_id,
    destination_node_id,
    owner_weight_kg,
    pet_weight_kg,
    pet_coefficient,
    route_geom,
    route_geojson,
    total_distance_m,
    owner_calories_kcal,
    pet_calories_kcal,
    average_slope,
    duration_minutes,
    facilities_json,
    error_code,
    created_at,
    started_at,
    completed_at,
    departure_at,
    environment_info,
    waypoint_node_ids,
    saved_at
)
SELECT md5('route-heatmap-frequency-v1:' || id::text || ':' || clone_no::text)::uuid,
       owner_user_id,
       pet_id,
       'COMPLETED',
       activity_type,
       priority_type,
       speed_kmh,
       start_node_id,
       waypoint_node_id,
       destination_node_id,
       owner_weight_kg,
       pet_weight_kg,
       pet_coefficient,
       route_geom,
       route_geojson,
       total_distance_m,
       owner_calories_kcal,
       pet_calories_kcal,
       average_slope,
       duration_minutes,
       facilities_json,
       NULL,
       now() - make_interval(mins => mock_frequency_rank * 10) + clone_no * interval '1 millisecond',
       now() - make_interval(mins => mock_frequency_rank * 10) + clone_no * interval '1 millisecond',
       now() - make_interval(mins => mock_frequency_rank * 10) + clone_no * interval '1 millisecond',
       departure_at,
       COALESCE(environment_info, '{}'::jsonb) || jsonb_build_object(
           'mockDataset', 'route-heatmap-frequency-v1',
           'mockSourceRouteId', id,
           'mockFrequencyRank', mock_frequency_rank
       ),
       waypoint_node_ids,
       now() - make_interval(mins => mock_frequency_rank * 10) + clone_no * interval '1 millisecond'
FROM mock_rows;

COMMIT;

-- Expected with five source routes: 244 mock routes, distributed 160/60/18/5/1.
SELECT environment_info ->> 'mockFrequencyRank' AS frequency_rank,
       count(*) AS mock_route_count
FROM route_requests
WHERE environment_info ->> 'mockDataset' = 'route-heatmap-frequency-v1'
GROUP BY environment_info ->> 'mockFrequencyRank'
ORDER BY (environment_info ->> 'mockFrequencyRank')::integer;
