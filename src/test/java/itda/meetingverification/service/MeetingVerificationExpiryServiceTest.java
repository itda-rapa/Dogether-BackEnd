package itda.meetingverification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link MeetingVerificationExpiryService} 의 실제 transaction 경계를 H2 에서 검증한다.
 *
 * <p>repository mock verify 가 아니라, 서비스 public method 가 {@code @Transactional} 경계를
 * 지나 EXPIRED 전이 + raw scrub 이 하나의 transaction 으로 commit 되는지 실제 DB 로 확인한다.
 */
@SpringBootTest
class MeetingVerificationExpiryServiceTest {

    @Autowired
    private MeetingVerificationExpiryService expiryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table meeting_verifications");
        jdbcTemplate.execute("truncate table meeting_cards");
    }

    @Test
    @DisplayName("시간창 경과 SUBMITTED 는 EXPIRED 로 전이되고 raw 좌표가 scrub 된다")
    void expiresSubmittedAndScrubsRaw() {
        long cardId = insertCard(1L, Instant.now().minus(2, ChronoUnit.HOURS));
        insertVerification(1L, cardId, 11L, "SUBMITTED", 37.5665, 126.978, 24.5,
                Instant.now().minusSeconds(10));

        MeetingVerificationExpiryService.ExpiryResult result = expiryService.runOnce();

        assertThat(result.expired()).isEqualTo(1);
        assertThat(queryStatus(1L)).isEqualTo("EXPIRED");
        assertThat(queryRaw(1L, "latitude")).isNull();
        assertThat(queryRaw(1L, "longitude")).isNull();
        assertThat(queryRaw(1L, "accuracy_meters")).isNull();
        assertThat(queryRaw(1L, "captured_at")).isNull();
    }

    @Test
    @DisplayName("CODE_REQUIRED/ACCEPTED/REJECTED 는 만료 worker 가 바꾸지 않는다")
    void leavesNonSubmittedStatesUntouched() {
        long codeCard = insertCard(1L, Instant.now().minus(2, ChronoUnit.HOURS));
        long acceptedCard = insertCard(2L, Instant.now().minus(2, ChronoUnit.HOURS));
        long rejectedCard = insertCard(3L, Instant.now().minus(2, ChronoUnit.HOURS));
        insertVerification(1L, codeCard, 11L, "CODE_REQUIRED", null, null, null, null);
        insertVerification(2L, acceptedCard, 11L, "ACCEPTED", null, null, null, null);
        insertVerification(3L, rejectedCard, 11L, "REJECTED", null, null, null, null);

        MeetingVerificationExpiryService.ExpiryResult result = expiryService.runOnce();

        assertThat(result.expired()).isZero();
        assertThat(queryStatus(1L)).isEqualTo("CODE_REQUIRED");
        assertThat(queryStatus(2L)).isEqualTo("ACCEPTED");
        assertThat(queryStatus(3L)).isEqualTo("REJECTED");
    }

    private long insertCard(long id, Instant meetAt) {
        jdbcTemplate.update("""
                insert into meeting_cards
                    (id, room_id, creator_pet_id, card_type, place_text, meet_at, status,
                     participant_count, created_at, updated_at)
                values (?, 1, 1, 'WALK', '중앙공원', ?, 'OPEN', 2, now(), now())
                """, id, meetAt);
        return id;
    }

    private void insertVerification(long id, long cardId, long petId, String status,
                                    Double latitude, Double longitude, Double accuracy,
                                    Instant capturedAt) {
        jdbcTemplate.update("""
                insert into meeting_verifications
                    (id, meeting_card_id, participant_pet_id, status, latitude, longitude,
                     accuracy_meters, captured_at, submitted_at, client_request_id,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now(), ?, now(), now())
                """, id, cardId, petId, status, latitude, longitude, accuracy, capturedAt,
                UUID.randomUUID());
    }

    private String queryStatus(long verificationId) {
        return jdbcTemplate.queryForObject(
                "select status from meeting_verifications where id = ?",
                String.class, verificationId);
    }

    private Object queryRaw(long verificationId, String column) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from meeting_verifications where id = ?",
                Object.class, verificationId);
    }
}
