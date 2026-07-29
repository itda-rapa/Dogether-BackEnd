package itda.pet.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
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

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class PetMigrationPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("update users set active_pet_id = null");
        jdbcTemplate.update("delete from pets");
        jdbcTemplate.update("delete from media_assets");
        jdbcTemplate.update("delete from refresh_tokens");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void appliesPetMigrationAndValidatesHibernateSchema() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().all()))
                .anyMatch(migration ->
                        migration.getVersion() != null
                                && "8".equals(
                                migration.getVersion().getVersion()
                        )
                                && migration.getState()
                                == MigrationState.SUCCESS
                );
        assertThat(jdbcTemplate.queryForObject(
                "select to_regclass('public.pets')",
                String.class
        )).isEqualTo("pets");
        assertThat(columnExists("breed_name")).isTrue();
        assertThat(columnExists("breed_code")).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                select udt_name
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = 'pets'
                   and column_name = 'personality_tags'
                """,
                String.class
        )).isEqualTo("jsonb");
    }

    @Test
    void createsNamedPetConstraintsAndPartialIndex() {
        List<String> constraints = jdbcTemplate.queryForList("""
                select constraint_name
                  from information_schema.table_constraints
                 where table_schema = 'public'
                   and table_name = 'pets'
                """,
                String.class
        );

        assertThat(constraints).contains(
                "fk_pets_owner",
                "fk_pets_profile_asset",
                "uk_pets_public_tag",
                "ck_pets_public_tag_format",
                "ck_pets_nickname",
                "ck_pets_sex",
                "ck_pets_size_code",
                "ck_pets_weight_kg",
                "ck_pets_status",
                "ck_pets_status_deleted_at"
        );
        assertThat(jdbcTemplate.queryForObject("""
                select constraint_name
                  from information_schema.table_constraints
                 where table_schema = 'public'
                   and table_name = 'users'
                   and constraint_name = 'fk_users_active_pet'
                """,
                String.class
        )).isEqualTo("fk_users_active_pet");

        String indexDefinition = jdbcTemplate.queryForObject("""
                select indexdef
                  from pg_indexes
                 where schemaname = 'public'
                   and tablename = 'pets'
                   and indexname = 'ix_pets_owner_not_deleted_created'
                """,
                String.class
        );
        assertThat(indexDefinition)
                .contains("(owner_user_id, created_at, id)")
                .containsIgnoringCase("WHERE (deleted_at IS NULL)");
    }

    @Test
    void acceptsValidStatusesNullableEnumsJsonAndForeignKeys() {
        Long ownerId = createUser();
        Long profileAssetId = createMediaAsset(ownerId, "PROFILE");
        Long activePetId = insertPet(
                ownerId,
                "몽이#A7K2",
                "몽이",
                null,
                null,
                new BigDecimal("3.25"),
                "ACTIVE",
                null,
                profileAssetId,
                "[\"친화적\",\"활발함\"]"
        );
        insertPet(
                ownerId,
                "초코#B8M3",
                "초코",
                "UNKNOWN",
                "MEDIUM",
                null,
                "SUSPENDED",
                null,
                null,
                null
        );
        insertPet(
                ownerId,
                "보리#C9N4",
                "보리",
                "MALE",
                "LARGE",
                new BigDecimal("20.00"),
                "DELETED",
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                "[]"
        );

        assertThat(jdbcTemplate.update(
                "update users set active_pet_id = ? where id = ?",
                activePetId,
                ownerId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select jsonb_typeof(personality_tags) from pets where id = ?",
                String.class,
                activePetId
        )).isEqualTo("array");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from pets where owner_user_id = ?",
                Integer.class,
                ownerId
        )).isEqualTo(3);
    }

    @Test
    void allowsWhitespaceOnlyNicknameAsDatabaseMinimumDefense() {
        Long ownerId = createUser();

        Long petId = insertPet(
                ownerId,
                "공백#D2P5",
                "   ",
                null,
                null,
                null,
                "ACTIVE",
                null,
                null,
                null
        );

        assertThat(petId).isNotNull();
    }

    @Test
    void rejectsEmptyNicknameAndInvalidEnumValues() {
        Long ownerId = createUser();

        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "빈값#E3Q6",
                "",
                null,
                null,
                null,
                "ACTIVE",
                null,
                null,
                null
        ));
        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "성별#F4R7",
                "성별",
                "INVALID",
                null,
                null,
                "ACTIVE",
                null,
                null,
                null
        ));
        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "크기#G5S8",
                "크기",
                null,
                "INVALID",
                null,
                "ACTIVE",
                null,
                null,
                null
        ));
        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "상태#H6T9",
                "상태",
                null,
                null,
                null,
                "INVALID",
                null,
                null,
                null
        ));
    }

    @Test
    void rejectsInvalidStatusDeletedAtCombinations() {
        Long ownerId = createUser();
        OffsetDateTime deletedAt = OffsetDateTime.now(ZoneOffset.UTC);

        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "활성#J7U2",
                "활성",
                null,
                null,
                null,
                "ACTIVE",
                deletedAt,
                null,
                null
        ));
        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "정지#K8V3",
                "정지",
                null,
                null,
                null,
                "SUSPENDED",
                deletedAt,
                null,
                null
        ));
        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "삭제#L9W4",
                "삭제",
                null,
                null,
                null,
                "DELETED",
                null,
                null,
                null
        ));
    }

    @Test
    void rejectsMalformedAndDuplicatePublicTags() {
        Long ownerId = createUser();

        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "잘못된#abc1",
                "잘못된",
                null,
                null,
                null,
                "ACTIVE",
                null,
                null,
                null
        ));
        insertPet(
                ownerId,
                "중복#M2X5",
                "중복",
                null,
                null,
                null,
                "ACTIVE",
                null,
                null,
                null
        );
        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "중복#M2X5",
                "다른펫",
                null,
                null,
                null,
                "ACTIVE",
                null,
                null,
                null
        ));
    }

    @Test
    void rejectsMissingOwnerProfileAssetAndActivePetForeignKeys() {
        Long ownerId = createUser();

        assertIntegrityViolation(() -> insertPet(
                Long.MAX_VALUE,
                "소유#N3Y6",
                "소유",
                null,
                null,
                null,
                "ACTIVE",
                null,
                null,
                null
        ));
        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "자산#P4Z7",
                "자산",
                null,
                null,
                null,
                "ACTIVE",
                null,
                Long.MAX_VALUE,
                null
        ));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "update users set active_pet_id = ? where id = ?",
                Long.MAX_VALUE,
                ownerId
        ));
    }

    @Test
    void rejectsNegativeAndOutOfRangeWeight() {
        Long ownerId = createUser();

        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "음수#Q5A8",
                "음수",
                null,
                null,
                new BigDecimal("-0.01"),
                "ACTIVE",
                null,
                null,
                null
        ));
        assertIntegrityViolation(() -> insertPet(
                ownerId,
                "초과#R6B9",
                "초과",
                null,
                null,
                new BigDecimal("1000.00"),
                "ACTIVE",
                null,
                null,
                null
        ));
    }

    @Test
    void activePetForeignKeyDoesNotEnforcePetOwnership() {
        Long firstOwnerId = createUser();
        Long secondOwnerId = createUser();
        Long secondOwnerPetId = insertPet(
                secondOwnerId,
                "타인#S7C2",
                "타인",
                null,
                null,
                null,
                "ACTIVE",
                null,
                null,
                null
        );

        assertThat(jdbcTemplate.update(
                "update users set active_pet_id = ? where id = ?",
                secondOwnerPetId,
                firstOwnerId
        )).isOne();
    }

    private Long createUser() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
                insert into users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500')
                returning id
                """,
                Long.class,
                unique + "@example.com",
                "보호자#" + unique.substring(0, 8)
        );
    }

    private boolean columnExists(String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = 'pets'
                   and column_name = ?
                """, Integer.class, columnName);
        return count != null && count == 1;
    }

    private Long createMediaAsset(Long ownerId, String purpose) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
                insert into media_assets (
                    owner_user_id,
                    purpose,
                    status,
                    object_key,
                    content_type,
                    size_bytes,
                    expires_at
                ) values (?, ?, 'UPLOADED', ?, 'image/jpeg', 1024,
                          CURRENT_TIMESTAMP + INTERVAL '1 hour')
                returning id
                """,
                Long.class,
                ownerId,
                purpose,
                "pet-profile/" + unique
        );
    }

    private Long insertPet(
            Long ownerId,
            String publicTag,
            String nickname,
            String sex,
            String sizeCode,
            BigDecimal weightKg,
            String status,
            OffsetDateTime deletedAt,
            Long profileAssetId,
            String personalityTags
    ) {
        return jdbcTemplate.queryForObject("""
                insert into pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    sex,
                    size_code,
                    weight_kg,
                    status,
                    deleted_at,
                    profile_asset_id,
                    personality_tags
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                returning id
                """,
                Long.class,
                ownerId,
                publicTag,
                nickname,
                sex,
                sizeCode,
                weightKg,
                status,
                deletedAt,
                profileAssetId,
                personalityTags
        );
    }

    private void assertIntegrityViolation(ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
