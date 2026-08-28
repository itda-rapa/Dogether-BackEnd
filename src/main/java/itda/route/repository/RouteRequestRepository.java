package itda.route.repository;

import itda.route.dto.RouteResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RouteRequestRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RouteRequestRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void create(UUID id, long userId, long petId, String activityType,
                       String priorityType, BigDecimal speedKmh, long startNodeId,
                       List<Long> waypointNodeIds, long destinationNodeId,
                       BigDecimal ownerWeightKg, BigDecimal petWeightKg,
                       BigDecimal petCoefficient, Instant departureAt) {
        jdbcTemplate.update("""
                INSERT INTO route_requests (
                    id, owner_user_id, pet_id, status, activity_type, priority_type,
                    speed_kmh, start_node_id, waypoint_node_id, waypoint_node_ids, destination_node_id,
                    owner_weight_kg, pet_weight_kg, pet_coefficient, departure_at
                ) VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?,
                          COALESCE(string_to_array(NULLIF(?, ''), ',')::bigint[], '{}'::bigint[]),
                          ?, ?, ?, ?, ?)
                """, id, userId, petId, activityType, priorityType, speedKmh,
                startNodeId, waypointNodeIds.isEmpty() ? null : waypointNodeIds.getFirst(),
                waypointNodeIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                destinationNodeId, ownerWeightKg, petWeightKg, petCoefficient,
                Timestamp.from(departureAt));
    }

    public void createRoundTrip(UUID id, long userId, long petId, String activityType,
                                String priorityType, BigDecimal speedKmh, long startNodeId,
                                long targetDistanceMeters, BigDecimal ownerWeightKg,
                                BigDecimal petWeightKg, BigDecimal petCoefficient,
                                Instant departureAt) {
        jdbcTemplate.update("""
                INSERT INTO route_requests (
                    id, owner_user_id, pet_id, status, activity_type, priority_type,
                    speed_kmh, start_node_id, waypoint_node_ids, destination_node_id,
                    owner_weight_kg, pet_weight_kg, pet_coefficient, departure_at,
                    route_mode, target_distance_m
                ) VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?, '{}'::bigint[], ?, ?, ?, ?, ?,
                          'ROUND_TRIP', ?)
                """, id, userId, petId, activityType, priorityType, speedKmh,
                startNodeId, startNodeId, ownerWeightKg, petWeightKg, petCoefficient,
                Timestamp.from(departureAt), targetDistanceMeters);
    }

    public boolean allNodesExist(List<Long> nodeIds) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT node_id)
                FROM network_node
                WHERE node_id IN (%s) AND geom IS NOT NULL
                """.formatted(String.join(",", nodeIds.stream().map(ignored -> "?").toList())),
                Long.class, nodeIds.toArray());
        return count != null && count == nodeIds.stream().distinct().count();
    }

    public Optional<RouteResponse> findOwned(UUID id, long userId) {
        return jdbcTemplate.query("""
                SELECT id, status, activity_type, priority_type, speed_kmh,
                       start_node_id, waypoint_node_ids, destination_node_id,
                       route_geojson::text, total_distance_m, owner_calories_kcal,
                       pet_calories_kcal, average_slope, duration_minutes,
                       facilities_json::text, error_code, created_at, completed_at
                       , departure_at, environment_info::text, saved_at
                FROM route_requests
                WHERE id = ? AND owner_user_id = ?
                """, (rs, rowNum) -> map(rs), id, userId).stream().findFirst();
    }

    public Optional<RouteResponse> findSharedInRoom(UUID id, long roomId) {
        return jdbcTemplate.query("""
                SELECT route.id, route.status, route.activity_type, route.priority_type,
                       route.speed_kmh, route.start_node_id, route.waypoint_node_ids,
                       route.destination_node_id, route.route_geojson::text,
                       route.total_distance_m, route.owner_calories_kcal,
                       route.pet_calories_kcal, route.average_slope, route.duration_minutes,
                       route.facilities_json::text, route.error_code, route.created_at,
                       route.completed_at, route.departure_at, route.environment_info::text,
                       route.saved_at
                  FROM route_requests route
                 WHERE route.id = ? AND route.status = 'COMPLETED'
                   AND EXISTS (
                       SELECT 1 FROM chat_messages message
                        WHERE message.room_id = ?
                          AND message.type = 'ROUTE_SHARE'
                          AND message.shared_route_id = route.id
                   )
                """, (rs, rowNum) -> map(rs), id, roomId).stream().findFirst();
    }

    public List<RouteResponse> findAllOwned(long userId) {
        return jdbcTemplate.query("""
                SELECT id, status, activity_type, priority_type, speed_kmh,
                       start_node_id, waypoint_node_ids, destination_node_id,
                       route_geojson::text, total_distance_m, owner_calories_kcal,
                       pet_calories_kcal, average_slope, duration_minutes,
                       facilities_json::text, error_code, created_at, completed_at,
                       departure_at, environment_info::text, saved_at
                FROM route_requests
                WHERE owner_user_id = ?
                  AND saved_at IS NOT NULL
                  AND COALESCE(environment_info ->> 'mockDataset', '') <> 'route-heatmap-frequency-v1'
                ORDER BY saved_at DESC
                LIMIT 50
                """, (rs, rowNum) -> map(rs), userId);
    }

    /**
     * Completed routes aggregated only by geometry. The result deliberately
     * contains no request, user or pet identifiers so it is safe to use as a
     * shared popularity overlay.
     */
    public JsonNode findSavedRouteHeatmap() {
        String json = jdbcTemplate.queryForObject("""
                SELECT jsonb_build_object(
                    'type', 'FeatureCollection',
                    'features', COALESCE(jsonb_agg(jsonb_build_object(
                        'type', 'Feature',
                        'geometry', aggregated.geometry,
                        'properties', jsonb_build_object('usageCount', aggregated.usage_count)
                    ) ORDER BY aggregated.usage_count), '[]'::jsonb)
                )::text
                FROM (
                    SELECT ST_AsGeoJSON(segment)::jsonb AS geometry,
                           count(*) AS usage_count
                    FROM (
                        SELECT ST_Normalize(ST_SnapToGrid(
                                   (ST_DumpSegments(ST_GeomFromGeoJSON(
                                       recent.route_geojson -> 'geometry'
                                   ))).geom,
                                   0.00001
                               )) AS segment
                        FROM (
                            SELECT route_geojson
                            FROM route_requests
                            WHERE status = 'COMPLETED'
                              AND route_geojson -> 'geometry' IS NOT NULL
                            ORDER BY completed_at DESC
                            LIMIT 1000
                        ) recent
                    ) route_segments
                    WHERE NOT ST_IsEmpty(segment)
                    GROUP BY segment
                    ORDER BY usage_count DESC
                    LIMIT 20000
                ) aggregated
                """, String.class);
        return json(json);
    }

    public void updateEnvironment(UUID id, JsonNode environmentInfo) {
        jdbcTemplate.update("""
                UPDATE route_requests SET environment_info = ?::jsonb
                WHERE id = ? AND (
                    environment_info IS NULL
                    OR environment_info ->> 'status' <> 'AVAILABLE'
                )
                """, environmentInfo.toString(), id);
    }

    public boolean saveOwnedCompleted(UUID id, long userId) {
        return jdbcTemplate.update("""
                UPDATE route_requests
                SET saved_at = COALESCE(saved_at, now())
                WHERE id = ? AND owner_user_id = ? AND status = 'COMPLETED'
                """, id, userId) > 0;
    }

    public void markFailed(UUID id, String errorCode) {
        jdbcTemplate.update("""
                UPDATE route_requests
                SET status = 'FAILED', error_code = ?, completed_at = now()
                WHERE id = ? AND status = 'QUEUED'
                """, errorCode, id);
    }

    public boolean isCompletedAndOwned(UUID id, long userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM route_requests
                    WHERE id = ? AND owner_user_id = ? AND status = 'COMPLETED'
                      AND saved_at IS NOT NULL
                )
                """, Boolean.class, id, userId);
        return Boolean.TRUE.equals(exists);
    }

    public boolean isAvailableForMeeting(UUID id, long userId, long roomId) {
        Boolean available = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM route_requests route
                     WHERE route.id = ?
                       AND route.status = 'COMPLETED'
                       AND (
                            route.owner_user_id = ?
                            OR EXISTS (
                                SELECT 1
                                  FROM chat_messages message
                                 WHERE message.room_id = ?
                                   AND message.type = 'ROUTE_SHARE'
                                   AND message.shared_route_id = route.id
                            )
                       )
                )
                """, Boolean.class, id, userId, roomId);
        return Boolean.TRUE.equals(available);
    }

    private RouteResponse map(ResultSet rs) throws SQLException {
        return new RouteResponse(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("activity_type"), rs.getString("priority_type"),
                rs.getBigDecimal("speed_kmh"), rs.getLong("start_node_id"),
                longArray(rs, "waypoint_node_ids"), rs.getLong("destination_node_id"),
                json(rs.getString("route_geojson")), rs.getBigDecimal("total_distance_m"),
                rs.getBigDecimal("owner_calories_kcal"), rs.getBigDecimal("pet_calories_kcal"),
                rs.getBigDecimal("average_slope"), rs.getBigDecimal("duration_minutes"),
                json(rs.getString("facilities_json")), instant(rs, "departure_at"),
                json(rs.getString("environment_info")), instant(rs, "saved_at"),
                rs.getString("error_code"),
                instant(rs, "created_at"), instant(rs, "completed_at"));
    }

    private List<Long> longArray(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        return java.util.Arrays.stream(values).map(value -> ((Number) value).longValue()).toList();
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private JsonNode json(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored route JSON is invalid", exception);
        }
    }
}
