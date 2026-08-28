package itda.route.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiChatRouteJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public AiChatRouteJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(UUID routeRequestId, long roomId, long userId, long petId) {
        jdbcTemplate.update("""
                INSERT INTO ai_chat_route_jobs (
                    route_request_id, room_id, requester_user_id, requester_pet_id
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (route_request_id) DO NOTHING
                """, routeRequestId, roomId, userId, petId);
    }

    public List<ReadyJob> findReady(int limit) {
        return jdbcTemplate.query("""
                SELECT job.route_request_id, job.room_id, job.requester_user_id,
                       route.status AS route_status, route.error_code
                  FROM ai_chat_route_jobs job
                  JOIN route_requests route ON route.id = job.route_request_id
                 WHERE job.status = 'PROCESSING'
                   AND route.status IN ('COMPLETED', 'FAILED')
                 ORDER BY job.created_at
                 LIMIT ?
                """, (rs, rowNum) -> new ReadyJob(
                        rs.getObject("route_request_id", UUID.class),
                        rs.getLong("room_id"),
                        rs.getLong("requester_user_id"),
                        rs.getString("route_status"),
                        rs.getString("error_code")), limit);
    }

    public void markShared(UUID routeRequestId) {
        jdbcTemplate.update("""
                UPDATE ai_chat_route_jobs
                   SET status = 'SHARED', completed_at = now(), last_error = NULL
                 WHERE route_request_id = ? AND status = 'PROCESSING'
                """, routeRequestId);
    }

    public void markFailed(UUID routeRequestId, String error) {
        jdbcTemplate.update("""
                UPDATE ai_chat_route_jobs
                   SET status = 'FAILED', completed_at = now(), last_error = ?
                 WHERE route_request_id = ? AND status = 'PROCESSING'
                """, error, routeRequestId);
    }

    public record ReadyJob(UUID routeRequestId, long roomId, long requesterUserId,
                           String routeStatus, String errorCode) { }
}
