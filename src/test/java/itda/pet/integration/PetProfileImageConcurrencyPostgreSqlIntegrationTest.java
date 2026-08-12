package itda.pet.integration;

import static org.assertj.core.api.Assertions.assertThat;

import itda.media.domain.Media;
import itda.media.repository.MediaRepository;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
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
class PetProfileImageConcurrencyPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PetRepository petRepository;

    @Autowired
    private MediaRepository mediaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentInitialProfileAssignmentsCommitExactlyOneVersionedUpdate()
            throws Exception {
        long ownerId = createUser();
        long petId = createPet(ownerId);
        List<Long> mediaIds = List.of(
                createMedia(ownerId, "UPLOADED"),
                createMedia(ownerId, "COMPLETED")
        );
        CountDownLatch bothLoadedWithoutProfile = new CountDownLatch(2);
        CountDownLatch allowFlush = new CountDownLatch(1);
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        List<AssignmentOutcome> outcomes = runConcurrently(index -> {
            try {
                transaction.executeWithoutResult(status -> {
                    Pet pet = petRepository
                            .findByIdWithOwnerAndProfileAsset(petId)
                            .orElseThrow();
                    Media media = mediaRepository.findById(mediaIds.get(index))
                            .orElseThrow();
                    assertThat(pet.getProfileAsset()).isNull();
                    pet.setInitialProfileAsset(media);
                    bothLoadedWithoutProfile.countDown();
                    await(bothLoadedWithoutProfile);
                    await(allowFlush);
                    petRepository.flush();
                });
                return AssignmentOutcome.committedResult();
            } catch (ObjectOptimisticLockingFailureException exception) {
                return AssignmentOutcome.conflictResult();
            }
        }, bothLoadedWithoutProfile, allowFlush);

        assertThat(outcomes.stream()
                .filter(AssignmentOutcome::committed)
                .count()).isEqualTo(1);
        assertThat(outcomes.stream()
                .filter(AssignmentOutcome::optimisticConflict)
                .count()).isEqualTo(1);

        Long finalProfileMediaId = jdbcTemplate.queryForObject(
                "select profile_asset_id from pets where id = ?",
                Long.class,
                petId
        );
        assertThat(finalProfileMediaId).isIn(mediaIds);
        assertThat(jdbcTemplate.queryForObject(
                "select version from pets where id = ?",
                Long.class,
                petId
        )).isEqualTo(1L);

        List<MediaState> mediaStates = jdbcTemplate.query("""
                select id, status, deleted_at is null as not_deleted
                  from media
                 where id in (?, ?)
                 order by id
                """,
                (resultSet, rowNumber) -> new MediaState(
                        resultSet.getLong("id"),
                        resultSet.getString("status"),
                        resultSet.getBoolean("not_deleted")
                ),
                mediaIds.get(0),
                mediaIds.get(1)
        );
        assertThat(mediaStates).extracting(MediaState::status)
                .containsExactly("UPLOADED", "COMPLETED");
        assertThat(mediaStates).allMatch(MediaState::notDeleted);
    }

    private List<AssignmentOutcome> runConcurrently(
            IndexedAction action,
            CountDownLatch bothLoaded,
            CountDownLatch allowFlush
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AssignmentOutcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                int worker = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return action.run(worker);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(bothLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            allowFlush.countDown();

            List<AssignmentOutcome> outcomes = new ArrayList<>();
            for (Future<AssignmentOutcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            allowFlush.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .isTrue();
        }
    }

    private long createUser() {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .toUpperCase();
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
                suffix + "@example.com",
                "보호자#" + suffix.substring(0, 8)
        );
    }

    private long createPet(long ownerId) {
        String tagSuffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 4)
                .toUpperCase();
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
                "반려견#" + tagSuffix
        );
    }

    private long createMedia(long ownerId, String status) {
        return jdbcTemplate.queryForObject("""
                insert into media (
                    media_type,
                    path,
                    status,
                    user_id,
                    file_size
                ) values ('IMAGE', ?, ?, ?, 1024)
                returning id
                """,
                Long.class,
                "pet-profile/" + UUID.randomUUID(),
                status,
                ownerId
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out at concurrent test barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @FunctionalInterface
    private interface IndexedAction {

        AssignmentOutcome run(int index);
    }

    private record AssignmentOutcome(
            boolean committed,
            boolean optimisticConflict
    ) {

        private static AssignmentOutcome committedResult() {
            return new AssignmentOutcome(true, false);
        }

        private static AssignmentOutcome conflictResult() {
            return new AssignmentOutcome(false, true);
        }
    }

    private record MediaState(long id, String status, boolean notDeleted) {
    }
}
