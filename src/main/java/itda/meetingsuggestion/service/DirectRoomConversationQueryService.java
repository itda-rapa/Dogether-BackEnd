package itda.meetingsuggestion.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 제안 대상 DIRECT 방 대화 조회.
 *
 * <p>Block / leftAt 판정 의미는 기존 Chat·MeetingCard 조회와 같다.
 * <ul>
 *   <li>Block: 양 Pet 소유 User 쌍의 어느 방향이든 {@code user_blocks} 에 있으면 제외</li>
 *   <li>leftAt: 양쪽 participant 모두 {@code left_at IS NULL} 이어야 대상</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DirectRoomConversationQueryService {

    private static final int MAX_AI_MESSAGES = 30;

    private final JdbcTemplate jdbc;

    /**
     * Scan 생성 시점 이후 방이 Block 되거나 참가자가 나갔을 수 있으므로 AI 호출 직전에
     * 다시 확인한다. 제외됐으면 AI 를 부르지 않고 Scan 은 정상 COMPLETED 다.
     */
    public boolean isEligible(long roomId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM chat_rooms room
                      JOIN pets low_pet  ON low_pet.id = room.pet_low_id
                      JOIN pets high_pet ON high_pet.id = room.pet_high_id
                     WHERE room.id = ?
                       AND room.type = 'DIRECT'
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
                )
                """, Boolean.class, roomId));
    }

    /**
     * 분석 창 내에서 TEXT 를 보낸 Pet 집합. 양쪽 Pet 이 각각 TEXT 1건 이상인지 판정한다.
     */
    public Set<Long> textSenderPetIds(long roomId, Instant windowStart, Instant windowEnd) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT sender_pet_id
                  FROM chat_messages message
                 WHERE message.room_id = ?
                   AND message.type = 'TEXT'
                   AND message.sender_type = 'PET'
                   AND message.created_at >= ?
                   AND message.created_at < ?
                """, Long.class, roomId,
                Timestamp.from(windowStart), Timestamp.from(windowEnd)));
    }

    /**
     * TEXT 만 거른 뒤 실제 생성 시각 기준 최신 최대 30건을 최신순으로 반환한다.
     * 호출부가 시간 ASC 로 되돌려 AI 에 넘긴다. CARD·SYSTEM·IMAGE·VIDEO·SETLOG_SHARE 는
     * 여기서 제외된다.
     *
     * <p>최신 판정은 메시지 ID 가 아니라 {@code created_at} 이며, 동일 시각은 id 로
     * 결정적으로 정렬한다. id 순서와 created_at 순서가 다를 수 있으므로 반드시
     * {@code created_at DESC, id DESC} 여야 한다.
     */
    public List<TextMessageRow> latestTextMessages(long roomId, Instant windowStart, Instant windowEnd) {
        return jdbc.query("""
                SELECT id, sender_pet_id, body, created_at
                  FROM chat_messages message
                 WHERE message.room_id = ?
                   AND message.type = 'TEXT'
                   AND message.sender_type = 'PET'
                   AND message.created_at >= ?
                   AND message.created_at < ?
                 ORDER BY message.created_at DESC, message.id DESC
                 LIMIT ?
                """, DirectRoomConversationQueryService::mapTextMessage,
                roomId, Timestamp.from(windowStart), Timestamp.from(windowEnd), MAX_AI_MESSAGES);
    }

    private static TextMessageRow mapTextMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TextMessageRow(
                resultSet.getLong("id"),
                resultSet.getLong("sender_pet_id"),
                resultSet.getString("body"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    public record TextMessageRow(
            long id,
            long senderPetId,
            String body,
            Instant createdAt
    ) {
    }
}
