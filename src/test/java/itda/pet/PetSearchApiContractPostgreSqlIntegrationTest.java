package itda.pet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class PetSearchApiContractPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sourceUserId;
    private Long targetUserId;
    private Long targetPetId;
    private String targetPublicTag;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate user_blocks, friendships, friend_requests, pets, users
                restart identity cascade
                """);
        sourceUserId = createUser("source", "ACTIVE");
        targetUserId = createUser("target", "ACTIVE");
        targetPublicTag = "몽이#A7K2";
        targetPetId = createPet(
                targetUserId,
                targetPublicTag,
                "몽이"
        );
    }

    @Test
    void searchesExactPublicTagForL1() throws Exception {
        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("Pet 검색이 완료되었습니다."))
                .andExpect(jsonPath("$.data.petId").value(targetPetId))
                .andExpect(jsonPath("$.data.publicTag")
                        .value(targetPublicTag))
                .andExpect(jsonPath("$.data.nickname").value("몽이"))
                .andExpect(jsonPath("$.data.profileUrl").isEmpty())
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.relationship").isEmpty())
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    void stripsCharacterWhitespaceBeforeExactSearch() throws Exception {
        search(" \u3000" + targetPublicTag + "\u3000 ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.petId").value(targetPetId));
    }

    @Test
    void acceptsThirtyCodePointsAfterStrip() throws Exception {
        String thirtyCodePoints = "가".repeat(25) + "#B8M3";
        assertThat(thirtyCodePoints.codePointCount(
                0,
                thirtyCodePoints.length()
        )).isEqualTo(30);
        Long petId = createPet(
                targetUserId,
                thirtyCodePoints,
                "가".repeat(25)
        );

        search("  " + thirtyCodePoints + "  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.petId").value(petId));
    }

    @Test
    void doesNotStripNoBreakSpace() throws Exception {
        assertThat(Character.isWhitespace(0x00A0)).isFalse();

        search("\u00A0" + targetPublicTag + "\u00A0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void preservesInternalSpace() throws Exception {
        String publicTag = "우리 몽이#B8M3";
        Long petId = createPet(targetUserId, publicTag, "우리 몽이");

        search(publicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.petId").value(petId));
        search("우리몽이#B8M3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void doesNotPartiallyOrCaseInsensitivelyMatch() throws Exception {
        search("몽이#A7K")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        search("몽이#a7k2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void doesNotNormalizeUnicode() throws Exception {
        String composed = "가#B8M3";
        String decomposed = "가#B8M3";
        createPet(targetUserId, composed, "가");

        search(decomposed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void hidesOwnPet() throws Exception {
        String publicTag = "보리#B8M3";
        createPet(sourceUserId, publicTag, "보리");

        search(publicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "DELETED"})
    void hidesNonActivePet(String statusValue) throws Exception {
        if ("DELETED".equals(statusValue)) {
            jdbcTemplate.update("""
                    update pets
                       set status = 'DELETED', deleted_at = CURRENT_TIMESTAMP
                     where id = ?
                    """, targetPetId);
        } else {
            jdbcTemplate.update(
                    "update pets set status = 'SUSPENDED' where id = ?",
                    targetPetId
            );
        }

        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "WITHDRAWN"})
    void hidesNonActiveOwner(String accountStatus) throws Exception {
        if ("WITHDRAWN".equals(accountStatus)) {
            jdbcTemplate.update("""
                    update users
                       set account_status = 'WITHDRAWN',
                           withdrawn_at = CURRENT_TIMESTAMP
                     where id = ?
                    """, targetUserId);
        } else {
            jdbcTemplate.update(
                    "update users set account_status = ? where id = ?",
                    accountStatus,
                    targetUserId
            );
        }

        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void preservesSourceToTargetBlockWhileHidingTarget() throws Exception {
        insertBlock(sourceUserId, targetUserId);
        long pairCountBefore = blockCount(sourceUserId, targetUserId);
        long reverseCountBefore = blockCount(targetUserId, sourceUserId);
        assertThat(pairCountBefore).isOne();
        assertThat(reverseCountBefore).isZero();

        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        assertThat(blockCount(sourceUserId, targetUserId))
                .isEqualTo(pairCountBefore);
        assertThat(blockCount(targetUserId, sourceUserId))
                .isEqualTo(reverseCountBefore);
    }

    @Test
    void preservesTargetToSourceBlockWhileHidingTarget() throws Exception {
        insertBlock(targetUserId, sourceUserId);
        long pairCountBefore = blockCount(targetUserId, sourceUserId);
        long reverseCountBefore = blockCount(sourceUserId, targetUserId);
        assertThat(pairCountBefore).isOne();
        assertThat(reverseCountBefore).isZero();

        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        assertThat(blockCount(targetUserId, sourceUserId))
                .isEqualTo(pairCountBefore);
        assertThat(blockCount(sourceUserId, targetUserId))
                .isEqualTo(reverseCountBefore);
    }

    @Test
    void returnsNoneForL2WithoutRelationship() throws Exception {
        activateSourcePet();

        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relationship").value("NONE"));
    }

    @Test
    void returnsRequestSentForActivePending() throws Exception {
        Long sourcePetId = activateSourcePet();
        insertPending(sourcePetId, targetPetId, Instant.now().plusSeconds(3600));

        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relationship")
                        .value("REQUEST_SENT"));
    }

    @Test
    void returnsRequestReceivedForActivePending() throws Exception {
        Long sourcePetId = activateSourcePet();
        insertPending(targetPetId, sourcePetId, Instant.now().plusSeconds(3600));

        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relationship")
                        .value("REQUEST_RECEIVED"));
    }

    @Test
    void returnsFriendForFriendship() throws Exception {
        Long sourcePetId = activateSourcePet();
        jdbcTemplate.update("""
                insert into friendships (pet_low_id, pet_high_id)
                values (?, ?)
                """,
                Math.min(sourcePetId, targetPetId),
                Math.max(sourcePetId, targetPetId)
        );
        long pairCountBefore = friendshipCount(sourcePetId, targetPetId);
        assertThat(pairCountBefore).isOne();

        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relationship").value("FRIEND"));

        assertThat(friendshipCount(sourcePetId, targetPetId))
                .isEqualTo(pairCountBefore);
    }

    @Test
    void ignoresExpiredPendingWithoutChangingDatabase() throws Exception {
        Long sourcePetId = activateSourcePet();
        Long requestId = insertPending(
                sourcePetId,
                targetPetId,
                Instant.now().minusSeconds(1)
        );
        Map<String, Object> before = friendRequestState(requestId);

        search(targetPublicTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relationship").value("NONE"));

        assertThat(friendRequestState(requestId)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from friendships",
                Long.class
        )).isZero();
    }

    @Test
    void validatesMissingBlankAndTooLongValues() throws Exception {
        mockMvc.perform(get("/pets/search")
                        .with(user(principal())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
        search(" \u3000\t ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
        search("가".repeat(26) + "#A7K2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/pets/search")
                        .queryParam("publicTag", targetPublicTag))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private ResultActions search(String publicTag) throws Exception {
        return mockMvc.perform(get("/pets/search")
                .with(user(principal()))
                .queryParam("publicTag", publicTag));
    }

    private CurrentUser principal() {
        return new CurrentUser(
                sourceUserId,
                "source@example.com",
                Role.USER
        );
    }

    private Long activateSourcePet() {
        Long sourcePetId = createPet(
                sourceUserId,
                "보리#C9N4",
                "보리"
        );
        jdbcTemplate.update(
                "update users set active_pet_id = ? where id = ?",
                sourcePetId,
                sourceUserId
        );
        return sourcePetId;
    }

    private Long insertPending(
            Long requesterPetId,
            Long targetPetId,
            Instant expiresAt
    ) {
        return jdbcTemplate.queryForObject("""
                insert into friend_requests (
                    requester_pet_id,
                    target_pet_id,
                    status,
                    expires_at
                ) values (?, ?, 'PENDING', ?)
                returning id
                """,
                Long.class,
                requesterPetId,
                targetPetId,
                expiresAt.atOffset(ZoneOffset.UTC)
        );
    }

    private Map<String, Object> friendRequestState(Long requestId) {
        return jdbcTemplate.queryForMap("""
                select status, responded_at, expires_at, updated_at
                  from friend_requests
                 where id = ?
                """, requestId);
    }

    private void insertBlock(Long blockerUserId, Long blockedUserId) {
        jdbcTemplate.update("""
                insert into user_blocks (blocker_user_id, blocked_user_id)
                values (?, ?)
                """, blockerUserId, blockedUserId);
    }

    private long blockCount(Long blockerUserId, Long blockedUserId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                  from user_blocks
                 where blocker_user_id = ?
                   and blocked_user_id = ?
                """,
                Long.class,
                blockerUserId,
                blockedUserId
        );
    }

    private long friendshipCount(Long firstPetId, Long secondPetId) {
        long petLowId = Math.min(firstPetId, secondPetId);
        long petHighId = Math.max(firstPetId, secondPetId);

        return jdbcTemplate.queryForObject("""
                select count(*)
                  from friendships
                 where pet_low_id = ?
                   and pet_high_id = ?
                """,
                Long.class,
                petLowId,
                petHighId
        );
    }

    private Long createUser(String label, String accountStatus) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                insert into users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) values (?, 'encoded', ?, ?, 'USER', ?, '4113111500')
                returning id
                """,
                Long.class,
                label + unique + "@example.com",
                label,
                label + "#" + unique.substring(0, 8),
                accountStatus
        );
    }

    private Long createPet(
            Long ownerUserId,
            String publicTag,
            String nickname
    ) {
        return jdbcTemplate.queryForObject("""
                insert into pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status
                ) values (?, ?, ?, 'ACTIVE')
                returning id
                """,
                Long.class,
                ownerUserId,
                publicTag,
                nickname
        );
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
