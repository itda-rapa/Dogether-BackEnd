package itda.friend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class FriendMigrationPostgreSqlIntegrationTest {

    private static final String FRIEND_MIGRATION_VERSION = "9";
    private static final String FRIEND_LIST_MIGRATION_VERSION = "15";
    private static final Instant FUTURE =
            Instant.parse("2026-08-05T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from friendships");
        jdbcTemplate.update("delete from friend_requests");
        jdbcTemplate.update("update users set active_pet_id = null");
        jdbcTemplate.update("delete from pets");
        jdbcTemplate.update("delete from media_assets");
        jdbcTemplate.update("delete from refresh_tokens");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void appliesFriendMigrationAndValidatesHibernateSchema() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().all()))
                .anyMatch(migration ->
                        migration.getVersion() != null
                                && FRIEND_MIGRATION_VERSION.equals(
                                migration.getVersion().getVersion()
                        )
                                && migration.getState()
                                == MigrationState.SUCCESS
                );
        assertThat(Arrays.stream(flyway.info().all()))
                .anyMatch(migration ->
                        migration.getVersion() != null
                                && FRIEND_LIST_MIGRATION_VERSION.equals(
                                migration.getVersion().getVersion()
                        )
                                && migration.getState()
                                == MigrationState.SUCCESS
                );
        assertThat(jdbcTemplate.queryForObject(
                "select to_regclass('public.friend_requests')",
                String.class
        )).isEqualTo("friend_requests");
        assertThat(jdbcTemplate.queryForObject(
                "select to_regclass('public.friendships')",
                String.class
        )).isEqualTo("friendships");
    }

    @Test
    void createsGeneratedPairColumnsAndExpectedValues() {
        Long ownerId = createUser();
        Long lowPetId = createPet(ownerId);
        Long highPetId = createPet(ownerId);

        Long requestId = insertFriendRequest(
                highPetId,
                lowPetId,
                "PENDING",
                FUTURE
        );

        Map<String, Object> pair = jdbcTemplate.queryForMap("""
                select pair_low_id, pair_high_id
                  from friend_requests
                 where id = ?
                """, requestId);
        assertThat(((Number) pair.get("pair_low_id")).longValue())
                .isEqualTo(lowPetId);
        assertThat(((Number) pair.get("pair_high_id")).longValue())
                .isEqualTo(highPetId);

        List<Map<String, Object>> generatedColumns =
                jdbcTemplate.queryForList("""
                        select column_name, is_generated, generation_expression
                          from information_schema.columns
                         where table_schema = 'public'
                           and table_name = 'friend_requests'
                           and column_name in ('pair_low_id', 'pair_high_id')
                         order by column_name
                        """);
        assertThat(generatedColumns).hasSize(2);
        assertThat(generatedColumns)
                .allSatisfy(column ->
                        assertThat(column.get("is_generated"))
                                .isEqualTo("ALWAYS")
                );
        assertThat(generationExpression(generatedColumns, "pair_low_id"))
                .containsIgnoringCase("least")
                .contains("requester_pet_id")
                .contains("target_pet_id");
        assertThat(generationExpression(generatedColumns, "pair_high_id"))
                .containsIgnoringCase("greatest")
                .contains("requester_pet_id")
                .contains("target_pet_id");
    }

    @Test
    void createsNamedConstraintsAndRelationshipIndexes() {
        List<String> friendRequestConstraints = constraints("friend_requests");
        assertThat(friendRequestConstraints).contains(
                "fk_friend_requests_requester_pet",
                "fk_friend_requests_target_pet",
                "ck_friend_request_self",
                "ck_friend_request_status"
        );

        List<String> friendshipConstraints = constraints("friendships");
        assertThat(friendshipConstraints).contains(
                "fk_friendships_pet_low",
                "fk_friendships_pet_high",
                "ck_friendship_pair",
                "uk_friendship_pair"
        );

        assertThat(indexDefinition("uk_friend_request_pending_pair"))
                .contains("(pair_low_id, pair_high_id)")
                .containsIgnoringCase("WHERE")
                .containsIgnoringCase("PENDING");
        assertThat(indexDefinition(
                "ix_friend_request_pending_requester_target_expires"
        ))
                .contains("(requester_pet_id, target_pet_id, expires_at)")
                .containsIgnoringCase("PENDING");
        assertThat(indexDefinition(
                "ix_friend_request_pending_target_requester_expires"
        ))
                .contains("(target_pet_id, requester_pet_id, expires_at)")
                .containsIgnoringCase("PENDING");
        assertThat(indexDefinition("ix_friendship_pet_high_low"))
                .contains("(pet_high_id, pet_low_id)");
        assertThat(indexDefinition(
                "ix_friend_request_pending_target_requested_id"
        ))
                .contains("(target_pet_id, requested_at DESC, id DESC)")
                .containsIgnoringCase("WHERE")
                .containsIgnoringCase("PENDING");
        assertThat(indexDefinition(
                "ix_friend_request_pending_requester_requested_id"
        ))
                .contains("(requester_pet_id, requested_at DESC, id DESC)")
                .containsIgnoringCase("WHERE")
                .containsIgnoringCase("PENDING");
    }

    @Test
    void enforcesFriendRequestForeignKeysSelfCheckAndStatusCheck() {
        Long ownerId = createUser();
        Long firstPetId = createPet(ownerId);
        Long secondPetId = createPet(ownerId);

        assertIntegrityViolation(() ->
                insertFriendRequest(
                        Long.MAX_VALUE,
                        secondPetId,
                        "PENDING",
                        FUTURE
                )
        );
        assertIntegrityViolation(() ->
                insertFriendRequest(
                        firstPetId,
                        Long.MAX_VALUE,
                        "PENDING",
                        FUTURE
                )
        );
        assertIntegrityViolation(() ->
                insertFriendRequest(
                        firstPetId,
                        firstPetId,
                        "PENDING",
                        FUTURE
                )
        );
        assertIntegrityViolation(() ->
                insertFriendRequest(
                        firstPetId,
                        secondPetId,
                        "INVALID",
                        FUTURE
                )
        );
    }

    @Test
    void preventsReversePendingDuplicateButAllowsNewPendingAfterClosure() {
        Long ownerId = createUser();
        Long firstPetId = createPet(ownerId);
        Long secondPetId = createPet(ownerId);
        Long firstRequestId = insertFriendRequest(
                firstPetId,
                secondPetId,
                "PENDING",
                FUTURE
        );

        assertIntegrityViolation(() ->
                insertFriendRequest(
                        secondPetId,
                        firstPetId,
                        "PENDING",
                        FUTURE
                )
        );

        assertThat(jdbcTemplate.update("""
                update friend_requests
                   set status = 'REJECTED',
                       responded_at = CURRENT_TIMESTAMP
                 where id = ?
                """, firstRequestId)).isOne();
        assertThat(insertFriendRequest(
                secondPetId,
                firstPetId,
                "PENDING",
                FUTURE
        )).isNotNull();
    }

    @Test
    void enforcesSortedUniqueFriendshipPairAndForeignKeys() {
        Long ownerId = createUser();
        Long lowPetId = createPet(ownerId);
        Long highPetId = createPet(ownerId);

        assertThat(insertFriendship(lowPetId, highPetId)).isNotNull();
        assertIntegrityViolation(() ->
                insertFriendship(lowPetId, highPetId)
        );
        assertIntegrityViolation(() ->
                insertFriendship(highPetId, lowPetId)
        );
        assertIntegrityViolation(() ->
                insertFriendship(lowPetId, Long.MAX_VALUE)
        );
    }

    private String generationExpression(
            List<Map<String, Object>> generatedColumns,
            String columnName
    ) {
        return generatedColumns.stream()
                .filter(column ->
                        columnName.equals(column.get("column_name"))
                )
                .map(column -> (String) column.get("generation_expression"))
                .findFirst()
                .orElseThrow();
    }

    private List<String> constraints(String tableName) {
        return jdbcTemplate.queryForList("""
                select constraint_name
                  from information_schema.table_constraints
                 where table_schema = 'public'
                   and table_name = ?
                """, String.class, tableName);
    }

    private String indexDefinition(String indexName) {
        return jdbcTemplate.queryForObject("""
                select indexdef
                  from pg_indexes
                 where schemaname = 'public'
                   and indexname = ?
                """, String.class, indexName);
    }

    private Long createUser() {
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
                ) values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113165000')
                returning id
                """,
                Long.class,
                unique + "@example.com",
                "보호자#" + unique.substring(0, 8)
        );
    }

    private Long createPet(Long ownerId) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                insert into pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status
                ) values (?, ?, '반려견', 'ACTIVE')
                returning id
                """,
                Long.class,
                ownerId,
                "반려견#" + unique.substring(0, 4).toUpperCase()
        );
    }

    private Long insertFriendRequest(
            Long requesterPetId,
            Long targetPetId,
            String status,
            Instant expiresAt
    ) {
        return jdbcTemplate.queryForObject("""
                insert into friend_requests (
                    requester_pet_id,
                    target_pet_id,
                    status,
                    expires_at
                ) values (?, ?, ?, ?)
                returning id
                """,
                Long.class,
                requesterPetId,
                targetPetId,
                status,
                expiresAt.atOffset(ZoneOffset.UTC)
        );
    }

    private Long insertFriendship(Long petLowId, Long petHighId) {
        return jdbcTemplate.queryForObject("""
                insert into friendships (pet_low_id, pet_high_id)
                values (?, ?)
                returning id
                """,
                Long.class,
                petLowId,
                petHighId
        );
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void assertIntegrityViolation(ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
