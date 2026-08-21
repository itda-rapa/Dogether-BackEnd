package itda.petverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
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
class PetVerificationMigrationPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("update users set active_pet_id = null");
        jdbc.update("delete from pet_verifications");
        jdbc.update("delete from pets");
        jdbc.update("delete from users");
    }

    @Test
    void migrationIsAppliedWithHibernateValidationAndContractualConstraints() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().all())).anyMatch(migration -> migration.getVersion() != null
                && "26.1".equals(migration.getVersion().getVersion())
                && migration.getState() == MigrationState.SUCCESS);
        assertThat(jdbc.queryForList("""
                select constraint_name from information_schema.table_constraints
                 where table_schema = 'public' and table_name = 'pet_verifications'
                """, String.class)).contains("fk_pet_verifications_pet", "uk_pet_verifications_pet",
                "uk_pet_verifications_registration_number_hmac", "ck_pet_verifications_registration_number_hmac",
                "ck_pet_verifications_provider", "ck_pet_verifications_device_type", "ck_pet_verifications_sex");
        assertThat(jdbc.queryForObject("""
                select data_type from information_schema.columns
                 where table_schema = 'public' and table_name = 'pet_verifications' and column_name = 'provider'
                """, String.class)).isEqualTo("character varying");
        assertThat(jdbc.queryForObject("""
                select confdeltype from pg_constraint
                 where conname = 'fk_pet_verifications_pet'
                """, String.class)).isNotEqualTo("c");
    }

    @Test
    void globallyUniqueCanonicalHmacSurvivesSoftDeleteAndInvalidSnapshotsAreRejected() {
        Long firstPet = pet(createUser(), "첫째#A1B2");
        Long secondPet = pet(createUser(), "둘째#C3D4");
        String hmac = "a".repeat(64);
        verification(firstPet, hmac, "IMPLANTED", "FEMALE");
        jdbc.update("update pets set status = 'DELETED', deleted_at = ? where id = ?",
                OffsetDateTime.now(ZoneOffset.UTC), firstPet);

        assertIntegrityViolation(() -> verification(secondPet, hmac, "TAG", "MALE"));
        assertIntegrityViolation(() -> verification(secondPet, "not-a-hmac", "TAG", "MALE"));
        assertIntegrityViolation(() -> verification(secondPet, "b".repeat(64), "OTHER", "MALE"));
        assertIntegrityViolation(() -> verification(secondPet, "c".repeat(64), "TAG", "OTHER"));

        verification(secondPet, "d".repeat(64), null, null);
        assertIntegrityViolation(() -> verification(secondPet, "e".repeat(64), null, null));
        assertIntegrityViolation(() -> jdbc.update("""
                insert into pet_verifications (pet_id, provider, registration_number_hmac, verified_at)
                values (?, 'UNSUPPORTED_PROVIDER', ?, CURRENT_TIMESTAMP)
                """, pet(createUser(), "셋째#E5F6"), "f".repeat(64)));
    }

    private Long createUser() {
        String id = UUID.randomUUID().toString().replace("-", "");
        return jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, 'encoded', 'Synthetic Owner', ?, 'USER', 'ACTIVE', '4113111500') returning id
                """, Long.class, id + "@example.test", "owner#" + id.substring(0, 8));
    }

    private Long pet(Long ownerId, String publicTag) {
        return jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status, personality_tags)
                values (?, ?, 'Synthetic Pet', 'ACTIVE', '[]'::jsonb) returning id
                """, Long.class, ownerId, publicTag);
    }

    private void verification(Long petId, String hmac, String deviceType, String sex) {
        jdbc.update("""
                insert into pet_verifications (pet_id, provider, registration_number_hmac, device_type, sex, verified_at)
                values (?, 'ANIMAL_INFO_V3', ?, ?, ?, CURRENT_TIMESTAMP)
                """, petId, hmac, deviceType, sex);
    }

    private void assertIntegrityViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(DataIntegrityViolationException.class);
    }
}
