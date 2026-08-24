package itda.dashboard.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AdminDashboardResponse(
        PeriodResponse period,
        EntityCountResponse users,
        EntityCountResponse pets,
        EntityCountResponse setlogs,
        EntityCountResponse boardPosts,
        ReportCountResponse reports,
        SafetyCountResponse safety,
        StorageCleanupResponse storageCleanup,
        List<RecentItemResponse> recentItems
) {

    public record PeriodResponse(LocalDate from, LocalDate to, String zoneId) {
    }

    public record EntityCountResponse(long total, long newInPeriod) {
    }

    public record ReportCountResponse(long createdInPeriod, long open) {
    }

    public record SafetyCountResponse(
            long detectedUsers,
            long openCases,
            Map<String, Long> signalsByType
    ) {
    }

    public record StorageCleanupResponse(long pending, long retry, long failed) {
    }

    public record RecentItemResponse(
            RecentItemSource source,
            long id,
            String status,
            long subjectUserId,
            String reason,
            Instant createdAt
    ) {
    }

    public enum RecentItemSource {
        REPORT,
        SAFETY_CASE
    }
}
