package itda.dashboard.service;

import itda.dashboard.dto.AdminDashboardResponse;
import itda.dashboard.dto.AdminDashboardResponse.EntityCountResponse;
import itda.dashboard.dto.AdminDashboardResponse.PeriodResponse;
import itda.dashboard.dto.AdminDashboardResponse.ReportCountResponse;
import itda.dashboard.dto.AdminDashboardResponse.SafetyCountResponse;
import itda.dashboard.dto.AdminDashboardResponse.StorageCleanupResponse;
import itda.dashboard.repository.AdminDashboardJdbcRepository;
import itda.dashboard.repository.AdminDashboardJdbcRepository.DashboardCounts;
import itda.dashboard.support.DashboardPeriod;
import itda.safety.service.AdminSafetyAuthorizationService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminDashboardQueryService {

    private final AdminSafetyAuthorizationService authorization;
    private final AdminDashboardJdbcRepository repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AdminDashboardResponse get(long adminUserId, LocalDate from, LocalDate to) {
        authorization.requireActiveAdmin(adminUserId);
        DashboardPeriod period = DashboardPeriod.resolve(from, to, clock);
        DashboardCounts counts = repository.findCounts(
                period.startInclusive(), period.endExclusive());
        Map<String, Long> signalsByType = repository.findSignalCounts(
                period.startInclusive(), period.endExclusive());

        return new AdminDashboardResponse(
                new PeriodResponse(period.from(), period.to(), period.zoneId()),
                new EntityCountResponse(counts.usersTotal(), counts.usersNew()),
                new EntityCountResponse(counts.petsTotal(), counts.petsNew()),
                new EntityCountResponse(counts.setlogsTotal(), counts.setlogsNew()),
                new EntityCountResponse(counts.boardPostsTotal(), counts.boardPostsNew()),
                new ReportCountResponse(counts.reportsCreated(), counts.reportsOpen()),
                new SafetyCountResponse(counts.detectedUsers(), counts.openCases(), signalsByType),
                new StorageCleanupResponse(counts.cleanupPending(), counts.cleanupRetry(),
                        counts.cleanupFailed()),
                repository.findRecentItems());
    }
}
