package itda.meetingsuggestion.service;

import itda.meetingcard.ai.AiDraftResult;
import itda.meetingsuggestion.service.MeetingSuggestionScanClaimService.ClaimedScan;
import itda.meetingsuggestion.support.CandidateNormalizer;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suggestion 저장과 기존 MeetingCard 중복 판정, Scan 완료 확정.
 *
 * <p>저장은 fingerprint {@code ON CONFLICT DO NOTHING} 이므로 같은 의미 후보가 같은
 * Scan 에서 중복 반환되거나 retry 재응답으로 다시 와도 한 건만 저장된다. DB UNIQUE
 * {@code uk_meeting_suggestion_fingerprint} 가 최종 방어선이지만, fingerprint 는 stale
 * worker fencing 의 대체 수단이 아니다.
 *
 * <p>저장 불변식: {@code scan.status == 'PROCESSING' AND scan.claim_token == 현재 worker
 * claimToken} 일 때만 Suggestion 을 저장할 수 있다.
 *
 * <p>{@link #saveCandidatesAndComplete} 는 Suggestion 저장과 Scan {@code COMPLETED} 전이를
 * <b>하나의 짧은 트랜잭션</b>으로 원자화한다. AI HTTP 호출이 끝난 뒤에만 호출되므로
 * 트랜잭션을 여는 동안 AI 응답을 기다리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MeetingSuggestionStore {

    /** 기존 약속 중복 판정 허용 오차(제품 규칙 §8). 경계 60분은 중복이다. */
    private static final Duration DUPLICATE_TOLERANCE = Duration.ofMinutes(60);

    private final JdbcTemplate jdbc;
    private final MeetingSuggestionScanClaimService claims;

    /**
     * 후보 저장과 Scan {@code COMPLETED} 확정을 한 트랜잭션으로 수행한다.
     *
     * <ol>
     *   <li>Scan 행을 {@code FOR UPDATE} 로 잠근 뒤 {@code PROCESSING + claimToken}
     *       소유권을 검증한다. 소유권이 없으면 한 건도 저장하지 않고 상태도 바꾸지 않은 채
     *       {@link SaveResult#fencedResult()} 를 반환한다.</li>
     *   <li>후보별로 기존 OPEN MeetingCard 중복 검사 후 fingerprint 멱등 INSERT.</li>
     *   <li>같은 트랜잭션에서 Scan 을 {@code COMPLETED} 로 전이한다.</li>
     * </ol>
     *
     * <p>후보 0건·전부 invalid skip·전부 기존 약속 중복인 경우에도 Scan 은 COMPLETED 로
     * 확정된다(정상 완료). 저장 뒤 COMPLETED 전이가 실패하면 예외로 전체 트랜잭션이
     * rollback 되어 Suggestion 도 남지 않고 Scan 은 기존 PROCESSING 상태를 유지한다.
     *
     * <p>중복 판정과 저장은 {@link AiDraftResult.Candidate#combinedInstant()} 를 사용한다.
     * {@code combinedInstant == null} 후보는 date/time 문자열이 있어도 저장하지 않는다.
     */
    @Transactional
    public SaveResult saveCandidatesAndComplete(ClaimedScan scan,
                                                List<AiDraftResult.Candidate> candidates,
                                                ZoneId zone) {
        if (!ownsScan(scan)) {
            return SaveResult.fencedResult();
        }
        int saved = 0;
        for (AiDraftResult.Candidate candidate : candidates) {
            if (candidate.combinedInstant() == null) {
                continue;
            }
            if (existsOpenMeetingCardNear(scan.roomId(), candidate.combinedInstant(), zone)) {
                continue;
            }
            saved += insertIfAbsent(scan.id(), candidate);
        }
        if (!claims.markCompleted(scan)) {
            // 잠금을 잡은 상태에서 읽은 소유권이므로 실패할 수 없지만, 방어적으로
            // rollback 한다. Suggestion 이 남지 않고 Scan 은 PROCESSING 을 유지한다.
            throw new IllegalStateException("scan completion failed: scanId=" + scan.id());
        }
        return SaveResult.saved(saved);
    }

    /**
     * Scan 행을 잠그고 현재 worker 의 claim 소유권을 검증한다.
     *
     * <p>{@code FOR UPDATE} 로 잠근 뒤 상태/토큰을 읽으므로, 검증과 후보 INSERT 사이에
     * 다른 worker 가 재claim 하거나 완료 상태를 확정할 수 없다.
     */
    private boolean ownsScan(ClaimedScan scan) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, claim_token
                  FROM meeting_suggestion_scans
                 WHERE id = ?
                 FOR UPDATE
                """, scan.id());
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.getFirst();
        return "PROCESSING".equals(row.get("status"))
                && scan.claimToken().equals(row.get("claim_token"));
    }

    /**
     * 후보와 겹치는 기존 OPEN 약속이 있으면 중복이다.
     *
     * <ul>
     *   <li>{@code meeting_cards.status = 'OPEN'} 만 본다. CANCELED 는 중복이 아니다.</li>
     *   <li>같은 방 + KST 약속 날짜 동일 + meetAt 시간 차이 60분 이내</li>
     * </ul>
     */
    private boolean existsOpenMeetingCardNear(long roomId, Instant meetAt, ZoneId zone) {
        LocalDate appointmentDate = meetAt.atZone(zone).toLocalDate();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM meeting_cards card
                     WHERE card.room_id = ?
                       AND card.status = 'OPEN'
                       AND (card.meet_at AT TIME ZONE ?)::date = ?
                       AND card.meet_at >= ?
                       AND card.meet_at <= ?
                )
                """, Boolean.class,
                roomId, zone.getId(), Date.valueOf(appointmentDate),
                Timestamp.from(meetAt.minus(DUPLICATE_TOLERANCE)),
                Timestamp.from(meetAt.plus(DUPLICATE_TOLERANCE))));
    }

    private int insertIfAbsent(long scanId, AiDraftResult.Candidate candidate) {
        String normalizedDate = CandidateNormalizer.normalizeDate(candidate.date());
        String normalizedTime = CandidateNormalizer.normalizeTime(candidate.time());
        String normalizedPlace = CandidateNormalizer.normalizePlace(candidate.place());
        String fingerprint = CandidateNormalizer.fingerprint(
                scanId, candidate.cardType(), candidate.date(), candidate.time(), candidate.place());
        return jdbc.update("""
                INSERT INTO meeting_suggestions
                    (scan_id, fingerprint, card_type, meet_date, meet_time, place_text)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) DO NOTHING
                """,
                scanId, fingerprint,
                candidate.cardType() == null ? null : candidate.cardType().name(),
                normalizedDate, normalizedTime, normalizedPlace);
    }

    /**
     * 저장 시도 결과. {@code fenced == true} 면 한 건도 저장하지 않았고 Scan 상태도
     * 바뀌지 않았으며, 호출자는 상태 확정을 시도하지 말고 종료해야 한다.
     */
    public record SaveResult(boolean fenced, int saved) {

        public static SaveResult saved(int count) {
            return new SaveResult(false, count);
        }

        public static SaveResult fencedResult() {
            return new SaveResult(true, 0);
        }
    }
}
