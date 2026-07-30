package itda.interaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import itda.interaction.dto.InteractionPairContext;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class InteractionPairLockPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private InteractionPairLockService lockService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean(name = "init")
    private CommandLineRunner adminBootstrapRunner;

    private ExecutorService executor;
    private Long sourceUserId;
    private Long targetUserId;
    private Long sourcePetId;
    private Long targetPetId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE user_blocks, pets, users
                RESTART IDENTITY CASCADE
                """);
        sourceUserId = createUser("source");
        targetUserId = createUser("target");
        sourcePetId = createPet(sourceUserId, "sourcePet");
        targetPetId = createPet(targetUserId, "targetPet");
        jdbcTemplate.update(
                "UPDATE users SET active_pet_id = ? WHERE id = ?",
                sourcePetId,
                sourceUserId
        );
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void mandatoryServiceRejectsCallsWithoutAnExistingTransaction() {
        assertThatThrownBy(() ->
                lockService.lockInteractionPair(sourcePetId, targetPetId)
        ).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void mandatoryServiceParticipatesInAnExistingTransaction() {
        InteractionPairContext result = new TransactionTemplate(transactionManager).execute(
                status -> lockService.lockInteractionPair(sourcePetId, targetPetId)
        );

        assertThat(result).isNotNull();
        assertThat(result.sourceUser().userId()).isEqualTo(sourceUserId);
        assertThat(result.targetUser().userId()).isEqualTo(targetUserId);
        assertThat(result.sourcePet().petId()).isEqualTo(sourcePetId);
        assertThat(result.targetPet().petId()).isEqualTo(targetPetId);
    }

    @Test
    void sameOwnerDifferentPetsLocksOneDistinctUserAndBothPets() {
        Long secondPetId = createPet(sourceUserId, "secondPet");

        InteractionPairContext result = new TransactionTemplate(transactionManager).execute(
                status -> lockService.lockInteractionPair(sourcePetId, secondPetId)
        );

        assertThat(result).isNotNull();
        assertThat(result.sourceUser().userId()).isEqualTo(sourceUserId);
        assertThat(result.targetUser().userId()).isEqualTo(sourceUserId);
        assertThat(result.sourcePet().petId()).isEqualTo(sourcePetId);
        assertThat(result.targetPet().petId()).isEqualTo(secondPetId);
    }

    @Test
    void ownerProjectionIncludesSoftDeletedTargetPetAndSnapshotsRawState() {
        jdbcTemplate.update(
                "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?",
                targetUserId
        );
        jdbcTemplate.update(
                """
                UPDATE pets
                SET status = 'DELETED', deleted_at = now()
                WHERE id = ?
                """,
                targetPetId
        );

        InteractionPairContext result = new TransactionTemplate(transactionManager).execute(
                status -> lockService.lockInteractionPair(sourcePetId, targetPetId)
        );

        assertThat(result).isNotNull();
        assertThat(result.targetUser().accountStatus().name()).isEqualTo("SUSPENDED");
        assertThat(result.targetPet().status().name()).isEqualTo("DELETED");
        assertThat(result.targetPet().deletedAt()).isNotNull();
    }

    @Test
    void userLockRemainsHeldUntilTheOuterTransactionEnds() throws Exception {
        assertCompetingRowLockWaits(RowLockTarget.USERS, sourceUserId);
    }

    @Test
    void petLockRemainsHeldUntilTheOuterTransactionEnds() throws Exception {
        assertCompetingRowLockWaits(RowLockTarget.PETS, sourcePetId);
    }

    @Test
    void oppositePetDirectionsUseTheSameOrderWithoutDeadlock() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<InteractionPairContext> forward = submitPairLock(
                sourcePetId,
                targetPetId,
                ready,
                start
        );
        Future<InteractionPairContext> reverse = submitPairLock(
                targetPetId,
                sourcePetId,
                ready,
                start
        );

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(forward.get(10, TimeUnit.SECONDS).sourcePet().petId())
                .isEqualTo(sourcePetId);
        assertThat(reverse.get(10, TimeUnit.SECONDS).sourcePet().petId())
                .isEqualTo(targetPetId);
    }

    private void assertCompetingRowLockWaits(
            RowLockTarget target,
            Long rowId
    ) throws Exception {
        CountDownLatch pairLocked = new CountDownLatch(1);
        CountDownLatch releasePair = new CountDownLatch(1);
        CountDownLatch competitorReady = new CountDownLatch(1);

        Future<Void> owner = executor.submit(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                lockService.lockInteractionPair(sourcePetId, targetPetId);
                pairLocked.countDown();
                awaitLatch(releasePair);
            });
            return null;
        });

        assertThat(pairLocked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Long> competitor = executor.submit(() -> {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '500ms'");
                competitorReady.countDown();
                return lockRow(target, rowId);
            });
        });

        ExecutionException competitorFailure;
        try {
            assertThat(competitorReady.await(10, TimeUnit.SECONDS)).isTrue();
            competitorFailure = assertThrows(
                    ExecutionException.class,
                    () -> competitor.get(10, TimeUnit.SECONDS)
            );
            SQLException postgresFailure = findPostgreSqlException(competitorFailure);
            assertThat(postgresFailure.getSQLState()).isEqualTo("55P03");
        } finally {
            releasePair.countDown();
        }
        owner.get(10, TimeUnit.SECONDS);

        Long lockedAfterRelease = new TransactionTemplate(transactionManager).execute(
                status -> lockRow(target, rowId)
        );
        assertThat(lockedAfterRelease).isEqualTo(rowId);
    }

    private Long lockRow(RowLockTarget target, Long rowId) {
        return jdbcTemplate.queryForObject(target.sql(), Long.class, rowId);
    }

    private SQLException findPostgreSqlException(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && cause.getClass().getName()
                            .equals("org.postgresql.util.PSQLException")) {
                return sqlException;
            }
            cause = cause.getCause();
        }
        throw new AssertionError(
                "Expected a PostgreSQL lock timeout in the exception cause chain",
                failure
        );
    }

    private Future<InteractionPairContext> submitPairLock(
            Long firstPetId,
            Long secondPetId,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return executor.submit(() -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return new TransactionTemplate(transactionManager).execute(
                    status -> lockService.lockInteractionPair(firstPetId, secondPetId)
            );
        });
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while holding interaction pair locks");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding interaction pair locks", exception);
        }
    }

    private Long createUser(String tag) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) VALUES (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500')
                RETURNING id
                """,
                Long.class,
                unique + "@example.com",
                tag + "#" + unique.substring(0, 8)
        );
    }

    private Long createPet(Long ownerId, String nickname) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
                INSERT INTO pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status
                ) VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id
                """,
                Long.class,
                ownerId,
                nickname + "#" + unique.substring(0, 4).toUpperCase(),
                nickname
        );
    }

    private enum RowLockTarget {
        USERS("SELECT id FROM users WHERE id = ? FOR UPDATE"),
        PETS("SELECT id FROM pets WHERE id = ? FOR UPDATE");

        private final String sql;

        RowLockTarget(String sql) {
            this.sql = sql;
        }

        String sql() {
            return sql;
        }
    }
}
