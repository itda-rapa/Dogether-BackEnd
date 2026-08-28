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
 * 지나 EXPIRED 전이 + raw scrub + Confirmation Code invalidation 이 하나의 transaction 으로
 * commit 되는지 실제 DB 로 확인한다.
 */
@SpringBootTest
class MeetingVerificationExpiryServiceTest {

    @Autowired
    private MeetingVerificationExpiryService expiryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table meeting_confirmation_codes");
        jdbcTemplate.execute("truncate table meeting_verifications");
        jdbcTemplate.execute("truncate table meetings");
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
    @DisplayName("미확정 CODE_REQUIRED 도 EXPIRED 로 전이되고 활성 Confirmation Code 가 무효화된다")
    void expiresCodeRequiredAndInvalidatesCode() {
        long cardId = insertCard(1L, Instant.now().minus(2, ChronoUnit.HOURS));
        insertVerification(1L, cardId, 11L, "CODE_REQUIRED", null, null, null, null);
        insertConfirmationCode(1L, cardId, 11L, "hash", Instant.now().plusSeconds(300));

        MeetingVerificationExpiryService.ExpiryResult result = expiryService.runOnce();

        assertThat(result.expired()).isEqualTo(1);
        assertThat(queryStatus(1L)).isEqualTo("EXPIRED");
        assertThat(queryRaw(1L, "latitude")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select invalidated_at from meeting_confirmation_codes where id = 1",
                Object.class)).isNotNull();
    }

    @Test
    @DisplayName("확정 Meeting(GPS/CODE)이 있는 카드는 만료·무효화하지 않는다")
    void skipsConfirmedCards() {
        long gpsCard = insertCard(1L, Instant.now().minus(2, ChronoUnit.HOURS));
        insertVerification(1L, gpsCard, 11L, "CODE_REQUIRED", null, null, null, null);
        insertMeeting(1L, gpsCard, "GPS", Instant.now().minusSeconds(10), 42.7);

        long codeCard = insertCard(2L, Instant.now().minus(2, ChronoUnit.HOURS));
        insertVerification(2L, codeCard, 11L, "CODE_REQUIRED", null, null, null, null);
        insertMeeting(2L, codeCard, "CODE", Instant.now().minusSeconds(10), null);
        insertConfirmationCode(2L, codeCard, 11L, "hash", Instant.now().plusSeconds(300));

        MeetingVerificationExpiryService.ExpiryResult result = expiryService.runOnce();

        assertThat(result.expired()).isZero();
        assertThat(queryStatus(1L)).isEqualTo("CODE_REQUIRED");
        assertThat(queryStatus(2L)).isEqualTo("CODE_REQUIRED");
        assertThat(jdbcTemplate.queryForObject(
                "select invalidated_at from meeting_confirmation_codes where id = 2",
                Object.class)).isNull();
    }

    @Test
    @DisplayName("ACCEPTED/REJECTED 는 만료 worker 가 바꾸지 않는다")
    void leavesAcceptedAndRejectedUntouched() {
        long acceptedCard = insertCard(1L, Instant.now().minus(2, ChronoUnit.HOURS));
        long rejectedCard = insertCard(2L, Instant.now().minus(2, ChronoUnit.HOURS));
        insertVerification(1L, acceptedCard, 11L, "ACCEPTED", null, null, null, null);
        insertVerification(2L, rejectedCard, 11L, "REJECTED", null, null, null, null);

        MeetingVerificationExpiryService.ExpiryResult result = expiryService.runOnce();

        assertThat(result.expired()).isZero();
        assertThat(queryStatus(1L)).isEqualTo("ACCEPTED");
        assertThat(queryStatus(2L)).isEqualTo("REJECTED");
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

    private void insertConfirmationCode(long id, long cardId, long issuerPetId, String hash,
                                        Instant expiresAt) {
        jdbcTemplate.update("""
                insert into meeting_confirmation_codes
                    (id, meeting_card_id, issuer_pet_id, code_hash, expires_at, failed_attempts,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, 0, now(), now())
                """, id, cardId, issuerPetId, hash, expiresAt);
    }

    private void insertMeeting(long id, long cardId, String method, Instant confirmedAt,
                               Double distanceMeters) {
        jdbcTemplate.update("""
                insert into meetings
                    (id, meeting_card_id, verification_method, confirmed_at, distance_meters,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, now(), now())
                """, id, cardId, method, confirmedAt, distanceMeters);
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
