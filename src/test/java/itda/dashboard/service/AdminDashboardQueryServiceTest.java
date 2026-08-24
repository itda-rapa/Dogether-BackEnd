package itda.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.dashboard.dto.AdminDashboardResponse.RecentItemResponse;
import itda.dashboard.dto.AdminDashboardResponse.RecentItemSource;
import itda.dashboard.repository.AdminDashboardJdbcRepository;
import itda.dashboard.repository.AdminDashboardJdbcRepository.DashboardCounts;
import itda.safety.service.AdminSafetyAuthorizationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AdminDashboardQueryServiceTest {

    private static final long ADMIN_ID = 99L;
    private static final Instant FROM = Instant.parse("2026-08-17T15:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-24T15:00:00Z");

    private AdminSafetyAuthorizationService authorization;
    private AdminDashboardJdbcRepository repository;
    private AdminDashboardQueryService service;

    @BeforeEach
    void setUp() {
        authorization = mock(AdminSafetyAuthorizationService.class);
        repository = mock(AdminDashboardJdbcRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T14:59:59Z"), ZoneOffset.UTC);
        service = new AdminDashboardQueryService(authorization, repository, clock);
    }

    @Test
    void activeAdminReceivesAllRepositoryAggregatesUsingOneResolvedPeriod() {
        DashboardCounts counts = new DashboardCounts(
                10, 2, 20, 3, 30, 4, 40, 5,
                6, 7, 8, 9, 11, 12, 13);
        List<RecentItemResponse> recentItems = List.of(new RecentItemResponse(
                RecentItemSource.SAFETY_CASE, 81L, "OPEN", 701L,
                "USER_BLOCKED", Instant.parse("2026-08-24T08:30:00Z")));
        when(repository.findCounts(FROM, TO)).thenReturn(counts);
        when(repository.findSignalCounts(FROM, TO))
                .thenReturn(Map.of("USER_BLOCKED", 4L, "GREETING_EXPIRED", 2L));
        when(repository.findRecentItems()).thenReturn(recentItems);

        var response = service.get(ADMIN_ID, null, null);

        assertThat(response.period().from()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(response.period().to()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(response.period().zoneId()).isEqualTo("Asia/Seoul");
        assertThat(response.users().total()).isEqualTo(10);
        assertThat(response.users().newInPeriod()).isEqualTo(2);
        assertThat(response.pets().newInPeriod()).isEqualTo(3);
        assertThat(response.setlogs().newInPeriod()).isEqualTo(4);
        assertThat(response.boardPosts().newInPeriod()).isEqualTo(5);
        assertThat(response.reports().createdInPeriod()).isEqualTo(6);
        assertThat(response.reports().open()).isEqualTo(7);
        assertThat(response.safety().detectedUsers()).isEqualTo(8);
        assertThat(response.safety().openCases()).isEqualTo(9);
        assertThat(response.safety().signalsByType())
                .containsEntry("USER_BLOCKED", 4L)
                .containsEntry("GREETING_EXPIRED", 2L);
        assertThat(response.storageCleanup().pending()).isEqualTo(11);
        assertThat(response.storageCleanup().retry()).isEqualTo(12);
        assertThat(response.storageCleanup().failed()).isEqualTo(13);
        assertThat(response.recentItems()).isEqualTo(recentItems);

        InOrder ordered = inOrder(authorization, repository);
        ordered.verify(authorization).requireActiveAdmin(ADMIN_ID);
        ordered.verify(repository).findCounts(FROM, TO);
        ordered.verify(repository).findSignalCounts(FROM, TO);
        ordered.verify(repository).findRecentItems();
    }

    @Test
    void anInvalidPeriodFailsWithoutExecutingAnyAggregateQuery() {
        assertThatThrownBy(() -> service.get(
                ADMIN_ID, LocalDate.of(2026, 8, 24), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_DATE_RANGE));

        verify(authorization).requireActiveAdmin(ADMIN_ID);
        verify(repository, never()).findCounts(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).findSignalCounts(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).findRecentItems();
    }

    @Test
    void authorizationFailureStopsBeforePeriodAndRepositoryWork() {
        BusinessException forbidden = new BusinessException(ErrorCode.FORBIDDEN);
        org.mockito.Mockito.doThrow(forbidden)
                .when(authorization).requireActiveAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.get(ADMIN_ID, null, null)).isSameAs(forbidden);

        verify(repository, never()).findCounts(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).findSignalCounts(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).findRecentItems();
    }
}
