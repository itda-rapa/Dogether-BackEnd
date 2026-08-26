package itda.meetingsuggestion.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Scan 이 분석할 KST 전날 시간 창.
 *
 * <p>실행 시각 기준으로 {@code sourceDate} 는 전날, {@code referenceDate} 는 실행일이다.
 * 분석 대상 메시지는 {@code [sourceDate 00:00, sourceDate + 1day 00:00)} (KST) 다.
 *
 * <p>retry 는 {@link #of(LocalDate, LocalDate, ZoneId)} 로 Scan 에 저장된 날짜로부터
 * 창을 다시 만들므로 referenceDate 를 실행일로 재계산하지 않는다.
 */
public record SourceDateWindow(
        LocalDate sourceDate,
        LocalDate referenceDate,
        Instant windowStart,
        Instant windowEnd
) {

    /**
     * 실행 시각 기준 창. 2026-08-25 07:00 KST 실행이면 sourceDate=2026-08-24,
     * referenceDate=2026-08-25 다.
     */
    public static SourceDateWindow forRunAt(Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return of(today.minusDays(1), today, zone);
    }

    /**
     * 저장된 sourceDate/referenceDate 로부터 창을 만든다. retry 는 이 팩토리를 사용한다.
     */
    public static SourceDateWindow of(LocalDate sourceDate, LocalDate referenceDate, ZoneId zone) {
        return new SourceDateWindow(
                sourceDate,
                referenceDate,
                sourceDate.atStartOfDay(zone).toInstant(),
                sourceDate.plusDays(1).atStartOfDay(zone).toInstant());
    }
}
