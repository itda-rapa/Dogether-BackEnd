package itda.dashboard.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DashboardPeriodTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-24T14:59:59Z"), ZoneOffset.UTC);

    @Test
    void omittedDatesResolveToTheSevenKoreanCalendarDaysIncludingToday() {
        DashboardPeriod period = DashboardPeriod.resolve(null, null, CLOCK);

        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(period.zoneId()).isEqualTo("Asia/Seoul");
        assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-08-17T15:00:00Z"));
        assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-08-24T15:00:00Z"));
    }

    @Test
    void defaultTodayChangesAtKoreanMidnightRatherThanUtcMidnight() {
        Clock koreanMidnight = Clock.fixed(
                Instant.parse("2026-08-24T15:00:00Z"), ZoneOffset.UTC);

        DashboardPeriod period = DashboardPeriod.resolve(null, null, koreanMidnight);

        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 8, 25));
    }

    @Test
    void explicitDatesUseAnInclusiveNinetyDayRangeAndExclusiveUtcEnd() {
        DashboardPeriod period = DashboardPeriod.resolve(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 29), CLOCK);

        assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-05-31T15:00:00Z"));
        assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-08-29T15:00:00Z"));
    }

    @Test
    void ninetyOneCalendarDaysAreRejected() {
        assertError(
                ErrorCode.DATE_RANGE_TOO_LARGE,
                () -> DashboardPeriod.resolve(
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 30), CLOCK));
    }

    @Test
    void reversedDatesAreRejected() {
        assertError(
                ErrorCode.INVALID_DATE_RANGE,
                () -> DashboardPeriod.resolve(
                        LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 24), CLOCK));
    }

    @Test
    void supplyingOnlyOneDateIsRejectedInsteadOfSilentlyChangingTheRequest() {
        assertError(
                ErrorCode.INVALID_DATE_RANGE,
                () -> DashboardPeriod.resolve(LocalDate.of(2026, 8, 18), null, CLOCK));
        assertError(
                ErrorCode.INVALID_DATE_RANGE,
                () -> DashboardPeriod.resolve(null, LocalDate.of(2026, 8, 24), CLOCK));
    }

    @Test
    void aDateWhoseExclusiveEndCannotBeRepresentedIsRejected() {
        assertError(
                ErrorCode.INVALID_DATE_RANGE,
                () -> DashboardPeriod.resolve(LocalDate.MAX, LocalDate.MAX, CLOCK));
    }

    private static void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
