package itda.meetingreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 만남 후기·발자국 HTTP 계약(H2). 실제 DB 제약·동시성은 PostgreSQL 통합 테스트에서 검증한다.
 * 여기서는 엔드포인트·응답 envelope·오류 코드 매핑을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeetingReviewApiContractTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String PLACE_TAG = "공원";

    private long user1;
    private long user2;
    private long pet1;
    private long pet2;
    private long cardId;
    private long meetingId;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from footprints");
        jdbc.execute("delete from meeting_reviews");
        jdbc.execute("delete from meetings");
        jdbc.execute("delete from meeting_participants");
        jdbc.execute("delete from meeting_cards");
        jdbc.execute("delete from pets");
        jdbc.execute("delete from users");

        user1 = createUser("user1");
        user2 = createUser("user2");
        pet1 = createPet(user1, "펫1");
        pet2 = createPet(user2, "펫2");
        setActivePet(user1, pet1);
        setActivePet(user2, pet2);
        cardId = createCard(pet1, "OPEN");
        addParticipant(cardId, pet1);
        addParticipant(cardId, pet2);
        meetingId = createMeeting(cardId, "GPS");
    }

    @Test
    void submitsReviewAndGrantsFootprint() throws Exception {
        String body = """
                {"clientRequestId":"%s","placeTag":"%s","content":"즐겁게 산책했어요."}
                """.formatted(UUID.randomUUID(), PLACE_TAG);

        String response = performSubmit(user1, meetingId, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.meetingId").value(meetingId))
                .andExpect(jsonPath("$.data.placeTag").value(PLACE_TAG))
                .andExpect(jsonPath("$.data.content").value("즐겁게 산책했어요."))
                .andExpect(jsonPath("$.data.footprint.granted").value(true))
                .andExpect(jsonPath("$.data.footprint.duplicateDay").value(false))
                .andReturn().getResponse().getContentAsString();

        long reviewId = ((Number) JsonPath.read(response, "$.data.reviewId")).longValue();
        long footprintId = ((Number) JsonPath.read(response, "$.data.footprint.footprintId")).longValue();
        assertThat(jdbc.queryForObject("select count(*) from meeting_reviews", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from footprints", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select place_tag from meeting_reviews where id = ?", String.class, reviewId))
                .isEqualTo(PLACE_TAG);
        assertThat(jdbc.queryForObject(
                "select earned_date from footprints where id = ?", java.sql.Date.class, footprintId).toLocalDate())
                .isEqualTo(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));

        // 발자국 목록에서 이 후기의 발자국이 보인다.
        mockMvc.perform(get("/footprints").with(user(principal(user1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].footprintId").value(footprintId))
                .andExpect(jsonPath("$.data.items[0].meetingId").value(meetingId))
                .andExpect(jsonPath("$.data.items[0].counterpartPet.petId").value(pet2))
                .andExpect(jsonPath("$.data.items[0].counterpartPet.nickname").value("펫2"))
                .andExpect(jsonPath("$.data.items[0].earnedDate").isNotEmpty())
                .andExpect(jsonPath("$.data.page.hasNext").value(false))
                .andExpect(jsonPath("$.data.page.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void contentIsOptional() throws Exception {
        String body = """
                {"clientRequestId":"%s","placeTag":"%s"}
                """.formatted(UUID.randomUUID(), PLACE_TAG);

        performSubmit(user1, meetingId, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.placeTag").value(PLACE_TAG))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.footprint.granted").value(true));
    }

    @Test
    void placeTagIsRequiredAndValidated() throws Exception {
        // 누락 → 400
        performSubmit(user1, meetingId, """
                {"clientRequestId":"%s","content":"태그 없음"}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        // blank → 400
        performSubmit(user1, meetingId, """
                {"clientRequestId":"%s","placeTag":"   "}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        // 30자 초과 → 400
        performSubmit(user1, meetingId, """
                {"clientRequestId":"%s","placeTag":"%s"}
                """.formatted(UUID.randomUUID(), "가".repeat(31)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void placeTagMaxLengthIsAccepted() throws Exception {
        performSubmit(user1, meetingId, """
                {"clientRequestId":"%s","placeTag":"%s"}
                """.formatted(UUID.randomUUID(), "가".repeat(30)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.placeTag").value("가".repeat(30)));
    }

    @Test
    void sameClientRequestIdWithDifferentPlaceTagIs409() throws Exception {
        UUID clientRequestId = UUID.randomUUID();
        performSubmit(user1, meetingId, """
                {"clientRequestId":"%s","placeTag":"%s","content":"첫 후기"}
                """.formatted(clientRequestId, PLACE_TAG))
                .andExpect(status().isCreated());

        performSubmit(user1, meetingId, """
                {"clientRequestId":"%s","placeTag":"카페","content":"첫 후기"}
                """.formatted(clientRequestId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVIEW_REQUEST_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from meeting_reviews", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameClientRequestIdReplayIsIdempotent() throws Exception {
        UUID clientRequestId = UUID.randomUUID();
        String body = """
                {"clientRequestId":"%s","placeTag":"%s","content":"즐겁게 산책했어요."}
                """.formatted(clientRequestId, PLACE_TAG);

        String first = performSubmit(user1, meetingId, body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long firstReviewId = ((Number) JsonPath.read(first, "$.data.reviewId")).longValue();
        long firstFootprintId = ((Number) JsonPath.read(first, "$.data.footprint.footprintId")).longValue();
        String firstCreatedAt = JsonPath.read(first, "$.data.createdAt");
        String firstEarnedDate = JsonPath.read(first, "$.data.footprint.earnedDate");

        // 재요청은 멱등: 같은 후기를 반환하고 새 발자국은 적립하지 않는다.
        String second = performSubmit(user1, meetingId, body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(((Number) JsonPath.read(second, "$.data.reviewId")).longValue())
                .isEqualTo(firstReviewId);
        assertThat(((Number) JsonPath.read(second, "$.data.meetingId")).longValue())
                .isEqualTo(meetingId);
        assertThat(((String) JsonPath.read(second, "$.data.placeTag"))).isEqualTo(PLACE_TAG);
        assertThat(((String) JsonPath.read(second, "$.data.content")))
                .isEqualTo("즐겁게 산책했어요.");
        assertThat(((String) JsonPath.read(second, "$.data.createdAt"))).isEqualTo(firstCreatedAt);
        assertThat((boolean) JsonPath.read(second, "$.data.footprint.granted")).isFalse();
        assertThat((boolean) JsonPath.read(second, "$.data.footprint.duplicateDay")).isFalse();
        assertThat(((Number) JsonPath.read(second, "$.data.footprint.footprintId")).longValue())
                .isEqualTo(firstFootprintId);
        assertThat(((String) JsonPath.read(second, "$.data.footprint.earnedDate")))
                .isEqualTo(firstEarnedDate);
        assertThat(jdbc.queryForObject("select count(*) from meeting_reviews", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from footprints", Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateWithNewClientRequestIdIs409() throws Exception {
        UUID clientRequestId = UUID.randomUUID();
        performSubmit(user1, meetingId, """
                {"clientRequestId":"%s","placeTag":"%s","content":"첫 후기"}
                """.formatted(clientRequestId, PLACE_TAG))
                .andExpect(status().isCreated());

        performSubmit(user1, meetingId, """
                {"clientRequestId":"%s","placeTag":"%s","content":"두 번째 후기"}
                """.formatted(UUID.randomUUID(), PLACE_TAG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVIEW_ALREADY_EXISTS"));
        assertThat(jdbc.queryForObject("select count(*) from meeting_reviews", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingMeetingIs404() throws Exception {
        performSubmit(user1, 99999L, """
                {"clientRequestId":"%s","placeTag":"%s"}
                """.formatted(UUID.randomUUID(), PLACE_TAG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOT_FOUND"));
    }

    @Test
    void nonParticipantIs403() throws Exception {
        long user3 = createUser("user3");
        long pet3 = createPet(user3, "펫3");
        setActivePet(user3, pet3);

        performSubmit(user3, meetingId, """
                {"clientRequestId":"%s","placeTag":"%s"}
                """.formatted(UUID.randomUUID(), PLACE_TAG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("REVIEW_NOT_PARTICIPANT"))
                .andExpect(jsonPath("$.error.message")
                        .value("약속 참여 반려견만 후기를 작성할 수 있습니다."));
    }

    @Test
    void canceledCardWithMeetingIs409() throws Exception {
        long canceledCardId = createCard(pet1, "CANCELED");
        addParticipant(canceledCardId, pet1);
        addParticipant(canceledCardId, pet2);
        long canceledMeetingId = createMeeting(canceledCardId, "GPS");

        performSubmit(user1, canceledMeetingId, """
                {"clientRequestId":"%s","placeTag":"%s"}
                """.formatted(UUID.randomUUID(), PLACE_TAG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVIEW_CARD_NOT_OPEN"))
                .andExpect(jsonPath("$.error.message")
                        .value("취소되거나 닫힌 약속 카드에는 후기를 작성할 수 없습니다."));
    }

    @Test
    void canceledCardWithoutMeetingIs404() throws Exception {
        // 취소된 카드에는 확정 Meeting 이 생기지 않으므로 후기 대상이 없다.
        long canceledCardId = createCard(pet1, "CANCELED");
        addParticipant(canceledCardId, pet1);
        addParticipant(canceledCardId, pet2);

        performSubmit(user1, 55555L, """
                {"clientRequestId":"%s","placeTag":"%s"}
                """.formatted(UUID.randomUUID(), PLACE_TAG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOT_FOUND"));
    }

    @Test
    void missingClientRequestIdIs400() throws Exception {
        performSubmit(user1, meetingId, """
                {"placeTag":"%s","content":"요청 식별자 없음"}
                """.formatted(PLACE_TAG))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void footprintListValidatesSize() throws Exception {
        mockMvc.perform(get("/footprints").with(user(principal(user1))).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/footprints").with(user(principal(user1))).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void inactivePetCannotSubmitOrList() throws Exception {
        jdbc.update("update users set active_pet_id = null where id = ?", user1);

        performSubmit(user1, meetingId, """
                {"clientRequestId":"%s","placeTag":"%s"}
                """.formatted(UUID.randomUUID(), PLACE_TAG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        mockMvc.perform(get("/footprints").with(user(principal(user1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
    }

    private ResultActions performSubmit(long userId, long targetMeetingId, String body) throws Exception {
        return mockMvc.perform(post("/meetings/{meetingId}/reviews", targetMeetingId)
                .with(user(principal(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long createUser(String name) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into users (email, password_hash, nickname, public_tag, role,
                                   account_status, neighborhood_code, version, created_at, updated_at)
                values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113111500', 0,
                        current_timestamp, current_timestamp)
                """, name + unique.substring(0, 8) + "@test.com", name, name + unique.substring(0, 6));
        return jdbc.queryForObject("select max(id) from users", Long.class);
    }

    private long createPet(long ownerId, String name) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into pets (owner_user_id, public_tag, nickname, status, version, created_at, updated_at)
                values (?, ?, ?, 'ACTIVE', 0, current_timestamp, current_timestamp)
                """, ownerId, name + unique.substring(0, 4), name);
        return jdbc.queryForObject("select max(id) from pets", Long.class);
    }

    private void setActivePet(long userId, long petId) {
        jdbc.update("update users set active_pet_id = ? where id = ?", petId, userId);
    }

    private long createCard(long creatorPetId, String status) {
        jdbc.update("""
                insert into meeting_cards (room_id, creator_pet_id, source_draft_id, card_type,
                                           place_text, meet_at, status, canceled_by_pet_id,
                                           canceled_at, created_at, updated_at)
                values (1, ?, null, 'WALK', '중앙공원', current_timestamp, ?, null, null,
                        current_timestamp, current_timestamp)
                """, creatorPetId, status);
        return jdbc.queryForObject("select max(id) from meeting_cards", Long.class);
    }

    private void addParticipant(long targetCardId, long petId) {
        jdbc.update("""
                insert into meeting_participants (meeting_card_id, pet_id, created_at)
                values (?, ?, current_timestamp)
                """, targetCardId, petId);
    }

    private long createMeeting(long targetCardId, String method) {
        jdbc.update("""
                insert into meetings (meeting_card_id, verification_method, confirmed_at, created_at, updated_at)
                values (?, ?, current_timestamp, current_timestamp, current_timestamp)
                """, targetCardId, method);
        return jdbc.queryForObject("select max(id) from meetings", Long.class);
    }

    private CurrentUser principal(long userId) {
        return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER);
    }
}
