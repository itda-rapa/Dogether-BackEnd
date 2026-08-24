package itda.dashboard.support;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public record DashboardPeriod(
        LocalDate from,
        LocalDate to,
        String zoneId,
        Instant startInclusive,
        Instant endExclusive
) {

    public static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;

    public static DashboardPeriod resolve(LocalDate from, LocalDate to, Clock clock) {
        if (from == null && to == null) {
            to = LocalDate.now(clock.withZone(SEOUL_ZONE));
            from = to.minusDays(DEFAULT_DAYS - 1L);
        } else if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }

        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new BusinessException(ErrorCode.DATE_RANGE_TOO_LARGE);
        }

        try {
            return new DashboardPeriod(
                    from,
                    to,
                    SEOUL_ZONE.getId(),
                    from.atStartOfDay(SEOUL_ZONE).toInstant(),
                    to.plusDays(1).atStartOfDay(SEOUL_ZONE).toInstant());
        } catch (DateTimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }
    }
}
