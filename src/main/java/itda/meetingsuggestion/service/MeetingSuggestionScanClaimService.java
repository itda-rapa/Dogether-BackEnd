package itda.meetingsuggestion.service;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scan 생성·claim·상태 확정을 담당하는 JDBC 경계.
 *
 * <p>Risk {@code RiskSignalOutboxClaimService} 와 같은 PostgreSQL claim 패턴을 쓰되 Risk
 * 서비스/저장소/엔티티에는 결합하지 않는다.
 *
 * <ul>
 *   <li>claim: {@code FOR UPDATE SKIP LOCKED} + CTE + {@code UPDATE ... RETURNING},
 *       다중 worker 중 하나만 소유권을 얻는다.</li>
 *   <li>상태 확정: 전부 {@code claim_token} 소유권을 검증하므로 stale worker 는 새 holder 의
 *       상태를 덮어쓰지 못한다(0건 갱신 = fenced).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MeetingSuggestionScanClaimService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 전날 KST 를 분석할 Scan 을 만든다. 동일 (roomId, sourceDate) 는 DB UNIQUE
     * {@code uk_meeting_suggestion_scan} + {@code ON CONFLICT DO NOTHING} 으로 한 번만
     * 만들어진다. 반환값은 실제로 새로 만든 Scan 수다.
     *
     * <p>대상은 DIRECT 방 중 양쪽 participant 가 모두 남아 있고(leftAt IS NULL) 양방향
     * Block 이 없는 방이다. Block 은 User 기준이며 양 Pet 소유자 쌍을 검사한다
     * ({@code MeetingCardRepository.findVisibleCards} 와 같은 의미).
     */
    public int createScans(LocalDate sourceDate, LocalDate referenceDate) {
        return jdbc.update("""
                INSERT INTO meeting_suggestion_scans (room_id, source_date, reference_date, next_retry_at)
                SELECT room.id, ?, ?, ?
                  FROM chat_rooms room
                  JOIN pets low_pet  ON low_pet.id = room.pet_low_id
                  JOIN pets high_pet ON high_pet.id = room.pet_high_id
                 WHERE room.type = 'DIRECT'
                   AND EXISTS (
                       SELECT 1 FROM chat_room_participants participant
                        WHERE participant.room_id = room.id
                          AND participant.pet_id = room.pet_low_id
                          AND participant.left_at IS NULL
                   )
                   AND EXISTS (
                       SELECT 1 FROM chat_room_participants participant
                        WHERE participant.room_id = room.id
                          AND participant.pet_id = room.pet_high_id
                          AND participant.left_at IS NULL
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM user_blocks block
                        WHERE (block.blocker_user_id = low_pet.owner_user_id
                               AND block.blocked_user_id = high_pet.owner_user_id)
                           OR (block.blocker_user_id = high_pet.owner_user_id
                               AND block.blocked_user_id = low_pet.owner_user_id)
                   )
                ON CONFLICT (room_id, source_date) DO NOTHING
                """,
                Date.valueOf(sourceDate), Date.valueOf(referenceDate), Timestamp.from(clock.instant()));
    }

    /**
     * 처리할 Scan 하나를 claim 한다.
     *
     * <ul>
     *   <li>{@code PENDING} / {@code FAILED_RETRYABLE}: {@code next_retry_at} 이 지나야 한다.</li>
     *   <li>{@code PROCESSING}: lease 가 만료된 stale claim 만 재선점한다.</li>
     * </ul>
     *
     * claim 하면 attempts 가 1 증가하고 claimToken 이 교체된다.
     */
    @Transactional
    public List<ClaimedScan> claim(int batchSize, Duration lease) {
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                WITH candidates AS (
                    SELECT scan.id
                      FROM meeting_suggestion_scans scan
                     WHERE ((scan.status IN ('PENDING', 'FAILED_RETRYABLE') AND scan.next_retry_at <= ?)
                         OR (scan.status = 'PROCESSING' AND scan.claimed_at <= ?))
                     ORDER BY scan.next_retry_at, scan.id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE meeting_suggestion_scans scan
                   SET status = 'PROCESSING',
                       claim_token = ?,
                       claimed_at = ?,
                       attempts = scan.attempts + 1,
                       updated_at = ?
                  FROM candidates, chat_rooms room
                 WHERE scan.id = candidates.id
                   AND room.id = scan.room_id
                RETURNING scan.id, scan.room_id, room.pet_low_id, room.pet_high_id,
                          scan.source_date, scan.reference_date, scan.attempts, scan.claim_token
                """, MeetingSuggestionScanClaimService::mapClaimedScan,
                Timestamp.from(now), Timestamp.from(now.minus(lease)), batchSize,
                token, Timestamp.from(now), Timestamp.from(now));
    }

    /** 정상 종료. 후보 0건·모두 미완성·모두 중복도 COMPLETED 다. */
    @Transactional
    public boolean markCompleted(ClaimedScan scan) {
        Instant now = clock.instant();
        return jdbc.update("""
                UPDATE meeting_suggestion_scans
                   SET status = 'COMPLETED', completed_at = ?, last_error = NULL,
                       claim_token = NULL, claimed_at = NULL, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, Timestamp.from(now), Timestamp.from(now), scan.id(), scan.claimToken()) == 1;
    }

    /** 재시도 예약. nextRetryAt 이 지나야 다시 claim 된다. */
    @Transactional
    public boolean markRetryable(ClaimedScan scan, Instant nextRetryAt, String error) {
        Instant now = clock.instant();
        return jdbc.update("""
                UPDATE meeting_suggestion_scans
                   SET status = 'FAILED_RETRYABLE', next_retry_at = ?, last_error = ?,
                       claim_token = NULL, claimed_at = NULL, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, Timestamp.from(nextRetryAt), sanitize(error), Timestamp.from(now),
                scan.id(), scan.claimToken()) == 1;
    }

    /** 최종 실패. 더 이상 claim 되지 않는다. */
    @Transactional
    public boolean markFinal(ClaimedScan scan, String error) {
        Instant now = clock.instant();
        return jdbc.update("""
                UPDATE meeting_suggestion_scans
                   SET status = 'FAILED_FINAL', completed_at = ?, last_error = ?,
                       claim_token = NULL, claimed_at = NULL, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, Timestamp.from(now), sanitize(error), Timestamp.from(now),
                scan.id(), scan.claimToken()) == 1;
    }

    private static ClaimedScan mapClaimedScan(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClaimedScan(
                resultSet.getLong("id"),
                resultSet.getLong("room_id"),
                resultSet.getLong("pet_low_id"),
                resultSet.getLong("pet_high_id"),
                resultSet.getDate("source_date").toLocalDate(),
                resultSet.getDate("reference_date").toLocalDate(),
                resultSet.getInt("attempts"),
                resultSet.getObject("claim_token", UUID.class));
    }

    private static String sanitize(String error) {
        if (error == null) {
            return null;
        }
        return error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
    }

    /**
     * claim 으로 소유권을 얻은 Scan 스냅샷. referenceDate 는 최초 Scan 생성 시 저장된
     * 값이며 retry 에서 재계산하지 않는다.
     */
    public record ClaimedScan(
            long id,
            long roomId,
            long petLowId,
            long petHighId,
            LocalDate sourceDate,
            LocalDate referenceDate,
            int attempts,
            UUID claimToken
    ) {
    }
}
