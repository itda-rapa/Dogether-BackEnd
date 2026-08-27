package itda.meetingverification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link MeetingVerificationRepository#findMeetingStatus} 단일 projection query 를 H2 에서 실제
 * 실행해 검증한다. GET status 가 두 SELECT 의 조합이 아니라 한 SQL statement 로 내 제출·상대
 * 제출·Meeting 을 읽는지 확인한다.
 */
@SpringBootTest
class MeetingVerificationProjectionTest {

    @Autowired
    private MeetingVerificationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table meetings");
        jdbcTemplate.execute("truncate table meeting_verifications");
        jdbcTemplate.execute("truncate table meeting_cards");
    }

    @Test
    @DisplayName("projection 은 내 제출·상대 제출·Meeting 을 한 번에 조합한다")
    void projectionCombinesVerificationsAndMeeting() {
        insertCard(1L);
        insertVerification(1L, 1L, 11L, "ACCEPTED");
        insertVerification(2L, 1L, 22L, "ACCEPTED");
        insertMeeting(1L, 1L, "GPS", 42.7);

        MeetingVerificationRepository.MeetingStatusProjection snapshot =
                repository.findMeetingStatus(1L, 11L).orElseThrow();

        assertThat(snapshot.getMyStatus()).isEqualTo("ACCEPTED");
        assertThat(snapshot.getCounterpartStatus()).isEqualTo("ACCEPTED");
        assertThat(snapshot.getMeetingId()).isEqualTo(1L);
        assertThat(snapshot.getVerificationMethod()).isEqualTo("GPS");
        assertThat(snapshot.getConfirmedAtEpochMillis()).isNotNull();
        assertThat(snapshot.getDistanceMeters()).isEqualTo(42.7);
    }

    @Test
    @DisplayName("projection 은 제출·Meeting 이 없으면 전부 null 이다")
    void projectionReturnsNullsWhenNothingSubmitted() {
        insertCard(1L);

        MeetingVerificationRepository.MeetingStatusProjection snapshot =
                repository.findMeetingStatus(1L, 11L).orElseThrow();

        assertThat(snapshot.getMyStatus()).isNull();
        assertThat(snapshot.getCounterpartStatus()).isNull();
        assertThat(snapshot.getMeetingId()).isNull();
        assertThat(snapshot.getVerificationMethod()).isNull();
        assertThat(snapshot.getConfirmedAtEpochMillis()).isNull();
        assertThat(snapshot.getDistanceMeters()).isNull();
    }

    @Test
    @DisplayName("projection 은 조회자 Pet 을 기준으로 내/상대를 구분한다")
    void projectionDistinguishesMineFromCounterpart() {
        insertCard(1L);
        insertVerification(1L, 1L, 11L, "SUBMITTED");
        insertVerification(2L, 1L, 22L, "CODE_REQUIRED");

        MeetingVerificationRepository.MeetingStatusProjection mine =
                repository.findMeetingStatus(1L, 11L).orElseThrow();
        MeetingVerificationRepository.MeetingStatusProjection counterpartView =
                repository.findMeetingStatus(1L, 22L).orElseThrow();

        assertThat(mine.getMyStatus()).isEqualTo("SUBMITTED");
        assertThat(mine.getCounterpartStatus()).isEqualTo("CODE_REQUIRED");
        assertThat(counterpartView.getMyStatus()).isEqualTo("CODE_REQUIRED");
        assertThat(counterpartView.getCounterpartStatus()).isEqualTo("SUBMITTED");
    }

    private void insertCard(long id) {
        jdbcTemplate.update("""
                insert into meeting_cards
                    (id, room_id, creator_pet_id, card_type, place_text, meet_at, status,
                     created_at, updated_at)
                values (?, 1, 11, 'WALK', '중앙공원', ?, 'OPEN', now(), now())
                """, id, Instant.parse("2026-07-30T01:00:00Z"));
    }

    private void insertVerification(long id, long cardId, long petId, String status) {
        jdbcTemplate.update("""
                insert into meeting_verifications
                    (id, meeting_card_id, participant_pet_id, status, latitude, longitude,
                     accuracy_meters, captured_at, submitted_at, client_request_id,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now(), ?, now(), now())
                """, id, cardId, petId, status, null, null, null, null, UUID.randomUUID());
    }

    private void insertMeeting(long id, long cardId, String method, Double distanceMeters) {
        jdbcTemplate.update("""
                insert into meetings
                    (id, meeting_card_id, verification_method, confirmed_at, distance_meters,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, now(), now())
                """, id, cardId, method, Instant.parse("2026-07-30T01:05:00Z"), distanceMeters);
    }
}
