package itda.dashboard.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import itda.dashboard.dto.AdminDashboardResponse.RecentItemResponse;
import itda.dashboard.repository.AdminDashboardJdbcRepository.DashboardCounts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AdminDashboardJdbcRepositoryQueryCountTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void oneDashboardLoadUsesExactlyThreeAggregateQueries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DashboardCounts emptyCounts = new DashboardCounts(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(emptyCounts);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn((List) List.of(Map.entry("USER_BLOCKED", 1L)))
                .thenReturn((List) List.<RecentItemResponse>of());
        AdminDashboardJdbcRepository repository = new AdminDashboardJdbcRepository(jdbc);
        Instant from = Instant.parse("2026-08-17T15:00:00Z");
        Instant to = Instant.parse("2026-08-24T15:00:00Z");

        repository.findCounts(from, to);
        repository.findSignalCounts(from, to);
        repository.findRecentItems();

        verify(jdbc).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }
}
