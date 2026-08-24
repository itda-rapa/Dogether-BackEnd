package itda.dashboard.repository;

import itda.dashboard.dto.AdminDashboardResponse.RecentItemResponse;
import itda.dashboard.dto.AdminDashboardResponse.RecentItemSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminDashboardJdbcRepository {

    private static final int RECENT_ITEM_LIMIT = 10;

    /**
     * Dashboard의 핵심 count는 도메인 테이블마다 한 번만 읽는다. 동일 테이블의 전체/기간
     * count를 scalar subquery로 따로 실행하지 않고 FILTER aggregate로 함께 계산한다.
     */
    static final String COUNTS_SQL = """
            select
                users.users_total, users.users_new,
                pets.pets_total, pets.pets_new,
                setlogs.setlogs_total, setlogs.setlogs_new,
                board_posts.board_posts_total, board_posts.board_posts_new,
                reports.reports_created, reports.reports_open,
                risk.detected_users, safety.open_cases,
                storage.cleanup_pending, storage.cleanup_retry, storage.cleanup_failed
              from (
                  select
                      count(*) filter (
                          where role = 'USER' and account_status <> 'WITHDRAWN'
                      ) as users_total,
                      count(*) filter (
                          where role = 'USER' and account_status <> 'WITHDRAWN'
                            and created_at >= ? and created_at < ?
                      ) as users_new
                    from users
              ) users
              cross join (
                  select
                      count(*) filter (
                          where status <> 'DELETED' and deleted_at is null
                      ) as pets_total,
                      count(*) filter (
                          where status <> 'DELETED' and deleted_at is null
                            and created_at >= ? and created_at < ?
                      ) as pets_new
                    from pets
              ) pets
              cross join (
                  select
                      count(*) filter (
                          where status = 'VISIBLE' and is_seed = false
                      ) as setlogs_total,
                      count(*) filter (
                          where status = 'VISIBLE' and is_seed = false
                            and created_at >= ? and created_at < ?
                      ) as setlogs_new
                    from setlogs
              ) setlogs
              cross join (
                  select
                      count(*) filter (
                          where status = 'PUBLISHED' and deleted_at is null
                      ) as board_posts_total,
                      count(*) filter (
                          where status = 'PUBLISHED' and deleted_at is null
                            and created_at >= ? and created_at < ?
                      ) as board_posts_new
                    from board_posts
              ) board_posts
              cross join (
                  select
                      count(*) filter (
                          where created_at >= ? and created_at < ?
                      ) as reports_created,
                      count(*) filter (where status = 'OPEN') as reports_open
                    from reports
              ) reports
              cross join (
                  select count(distinct actor_user_id) filter (
                          where occurred_at >= ? and occurred_at < ?
                      ) as detected_users
                    from risk_signal_events
              ) risk
              cross join (
                  select count(*) filter (
                          where status in ('OPEN', 'REVIEWING')
                      ) as open_cases
                    from safety_review_cases
              ) safety
              cross join (
                  select
                      count(*) filter (where status = 'PENDING') as cleanup_pending,
                      count(*) filter (where status = 'RETRY') as cleanup_retry,
                      count(*) filter (where status = 'FAILED') as cleanup_failed
                    from storage_delete_jobs
              ) storage
            """;

    private final JdbcTemplate jdbc;

    public DashboardCounts findCounts(Instant fromInclusive, Instant toExclusive) {
        Timestamp from = Timestamp.from(fromInclusive);
        Timestamp to = Timestamp.from(toExclusive);
        return jdbc.queryForObject(COUNTS_SQL, AdminDashboardJdbcRepository::mapCounts,
                from, to, from, to, from, to, from, to, from, to, from, to);
    }

    public Map<String, Long> findSignalCounts(Instant fromInclusive, Instant toExclusive) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("""
                select signal_type, count(*) as signal_count
                  from risk_signal_events
                 where occurred_at >= ? and occurred_at < ?
                 group by signal_type
                 order by signal_type
                """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("signal_type"),
                        resultSet.getLong("signal_count")),
                Timestamp.from(fromInclusive), Timestamp.from(toExclusive))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    public List<RecentItemResponse> findRecentItems() {
        return jdbc.query("""
                select source, id, status, subject_user_id, reason, created_at
                  from (
                      (select 'REPORT' as source, id, status,
                              reported_user_id as subject_user_id,
                              reason_code as reason, created_at
                         from reports
                        order by created_at desc, id desc
                        limit ?)
                      union all
                      (select 'SAFETY_CASE' as source, id, status,
                              subject_user_id, primary_signal_type as reason, created_at
                         from safety_review_cases
                        order by created_at desc, id desc
                        limit ?)
                  ) recent
                 order by created_at desc, source asc, id desc
                 limit ?
                """, AdminDashboardJdbcRepository::mapRecentItem,
                RECENT_ITEM_LIMIT, RECENT_ITEM_LIMIT, RECENT_ITEM_LIMIT);
    }

    private static DashboardCounts mapCounts(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new DashboardCounts(
                resultSet.getLong("users_total"), resultSet.getLong("users_new"),
                resultSet.getLong("pets_total"), resultSet.getLong("pets_new"),
                resultSet.getLong("setlogs_total"), resultSet.getLong("setlogs_new"),
                resultSet.getLong("board_posts_total"), resultSet.getLong("board_posts_new"),
                resultSet.getLong("reports_created"), resultSet.getLong("reports_open"),
                resultSet.getLong("detected_users"), resultSet.getLong("open_cases"),
                resultSet.getLong("cleanup_pending"), resultSet.getLong("cleanup_retry"),
                resultSet.getLong("cleanup_failed"));
    }

    private static RecentItemResponse mapRecentItem(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new RecentItemResponse(
                RecentItemSource.valueOf(resultSet.getString("source")),
                resultSet.getLong("id"), resultSet.getString("status"),
                resultSet.getLong("subject_user_id"), resultSet.getString("reason"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    public record DashboardCounts(
            long usersTotal,
            long usersNew,
            long petsTotal,
            long petsNew,
            long setlogsTotal,
            long setlogsNew,
            long boardPostsTotal,
            long boardPostsNew,
            long reportsCreated,
            long reportsOpen,
            long detectedUsers,
            long openCases,
            long cleanupPending,
            long cleanupRetry,
            long cleanupFailed
    ) {
    }
}
