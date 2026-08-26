package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.boardpost.repository.BoardPostReactionRepository;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.domain.BoardPostReactionType;
import itda.boardpost.service.BoardPostService;
import itda.pet.service.ActivePetSelectionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class BoardPostReactionPostgreSqlIntegrationTest {

    private static final AtomicLong PET_TAG_SEQUENCE = new AtomicLong();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoardPostReactionRepository reactions;
    @Autowired private BoardPostRepository posts;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private BoardPostService postService;
    @Autowired private ActivePetSelectionService activePetSelectionService;

    @Test
    void flywayCreatesTheRequiredReactionConstraintsIndexesAndNoCascadeForeignKeys() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> jdbc.update("""
                insert into board_post_reactions (post_id, reactor_pet_id, reaction_type)
                values (?, ?, 'CUTE')
                """, fixture.postId(), fixture.petId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("""
                insert into board_post_reactions (post_id, reactor_pet_id, reaction_type)
                values (?, ?, 'HELPFUL')
                """, fixture.postId(), fixture.petId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from board_post_reactions
                where post_id = ? and reactor_pet_id = ? and reaction_type = 'HELPFUL'
                """, Long.class, fixture.postId(), fixture.petId())).isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_post_reactions (post_id, reactor_pet_id, reaction_type)
                values (?, ?, 'LIKE')
                """, 999999999L, fixture.petId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_post_reactions (post_id, reactor_pet_id, reaction_type)
                values (?, ?, 'LIKE')
                """, fixture.postId(), 999999999L)).isInstanceOf(DataIntegrityViolationException.class);

        String uniqueIndex = jdbc.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = current_schema()
                  and indexname = 'uk_board_post_reactions_post_pet_type'
                """, String.class);
        String reactorIndex = jdbc.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = current_schema()
                  and indexname = 'ix_board_post_reactions_reactor_pet_post'
                """, String.class);
        String authorPetIndex = jdbc.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = current_schema()
                  and indexname = 'ix_board_posts_visible_author_pet_id'
                """, String.class);
        assertThat(uniqueIndex).contains("post_id, reactor_pet_id, reaction_type");
        assertThat(reactorIndex).contains("reactor_pet_id, post_id");
        assertThat(authorPetIndex).contains("author_pet_id, id").contains("deleted_at IS NULL");
        assertThat(deleteRule("fk_board_post_reactions_post")).isNotEqualTo("CASCADE");
        assertThat(deleteRule("fk_board_post_reactions_reactor_pet")).isNotEqualTo("CASCADE");
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = current_schema()
                  and table_name = 'board_post_reactions'
                  and column_name in ('status', 'deleted_at', 'updated_at')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = current_schema()
                  and table_name = 'board_posts'
                  and column_name like '%reaction%count%'
                """, Integer.class)).isZero();
    }

    @Test
    void nativePutDeleteCommandsAreIdempotentAndKeepRowsAsTheCountSourceOfTruth() {
        Fixture fixture = fixture();
        TransactionTemplate tx = transactionTemplate();

        Integer firstInsert = tx.execute(
                status -> reactions.insertIgnore(fixture.postId(), fixture.petId(), "LIKE")
        );
        Integer duplicateInsert = tx.execute(
                status -> reactions.insertIgnore(fixture.postId(), fixture.petId(), "LIKE")
        );
        assertThat(firstInsert).isEqualTo(1);
        assertThat(duplicateInsert).isZero();
        assertThat(reactions.countForPost(fixture.postId(), "LIKE")).isEqualTo(1);
        Integer firstDelete = tx.execute(
                status -> reactions.deleteReaction(fixture.postId(), fixture.petId(), "LIKE")
        );
        Integer missingDelete = tx.execute(
                status -> reactions.deleteReaction(fixture.postId(), fixture.petId(), "LIKE")
        );
        assertThat(firstDelete).isEqualTo(1);
        assertThat(missingDelete).isZero();
        assertThat(reactions.countForPost(fixture.postId(), "LIKE")).isZero();
    }

    @Test
    void batchCountAndActivePetPostIdQueriesReturnOnlyReactionRows() {
        Fixture first = fixture();
        Fixture second = fixture();
        Fixture third = fixture();
        TransactionTemplate tx = transactionTemplate();
        tx.executeWithoutResult(status -> {
            reactions.insertIgnore(first.postId(), first.petId(), "LIKE");
            reactions.insertIgnore(first.postId(), second.petId(), "LIKE");
            reactions.insertIgnore(second.postId(), first.petId(), "LIKE");
        });

        Map<Long, Long> counts = reactions.countForPosts(
                List.of(first.postId(), second.postId(), third.postId()), "LIKE"
        ).stream().collect(java.util.stream.Collectors.toMap(
                BoardPostReactionRepository.PostReactionCount::getPostId,
                BoardPostReactionRepository.PostReactionCount::getReactionCount
        ));
        assertThat(counts).containsEntry(first.postId(), 2L).containsEntry(second.postId(), 1L)
                .doesNotContainKey(third.postId());
        assertThat(reactions.findReactedPostIds(first.petId(),
                List.of(first.postId(), second.postId(), third.postId()), "LIKE"))
                .containsExactlyInAnyOrder(first.postId(), second.postId());
    }

    @Test
    void postSoftDeletePreservesReactionRow() {
        Fixture fixture = fixture();
        transactionTemplate().executeWithoutResult(status ->
                reactions.insertIgnore(fixture.postId(), fixture.petId(), "LIKE"));

        jdbc.update("update board_posts set status = 'DELETED', deleted_at = now() where id = ?", fixture.postId());

        assertThat(jdbc.queryForObject("select status from board_posts where id = ?", String.class, fixture.postId()))
                .isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select count(*) from board_post_reactions where post_id = ?",
                Long.class, fixture.postId())).isEqualTo(1L);
    }

    @Test
    void rollbackDoesNotLeavePartialReactionRow() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> transactionTemplate().executeWithoutResult(status -> {
            reactions.insertIgnore(fixture.postId(), fixture.petId(), "LIKE");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(reactions.countForPost(fixture.postId(), "LIKE")).isZero();
    }

    @Test
    void concurrentSamePetPutLeavesExactlyOneReactionRow() throws Exception {
        Fixture fixture = fixture();
        TransactionTemplate tx = transactionTemplate();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = List.of(
                    executor.submit(() -> insertWhenStarted(tx, fixture, ready, start)),
                    executor.submit(() -> insertWhenStarted(tx, fixture, ready, start))
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(results.stream().map(this::get).toList()).containsExactlyInAnyOrder(1, 0);
            assertThat(jdbc.queryForObject("select count(*) from board_post_reactions where post_id = ?",
                    Long.class, fixture.postId())).isEqualTo(1L);
            assertThat(reactions.countForPost(fixture.postId(), "LIKE")).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentSamePetHelpfulPutLeavesExactlyOneReactionRow() throws Exception {
        Fixture fixture = fixture();
        TransactionTemplate tx = transactionTemplate();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = List.of(
                    executor.submit(() -> insertWhenStarted(tx, fixture, ready, start, "HELPFUL")),
                    executor.submit(() -> insertWhenStarted(tx, fixture, ready, start, "HELPFUL"))
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(results.stream().map(this::get).toList()).containsExactlyInAnyOrder(1, 0);
            assertThat(jdbc.queryForObject("""
                    select count(*) from board_post_reactions
                    where post_id = ? and reactor_pet_id = ? and reaction_type = 'HELPFUL'
                    """, Long.class, fixture.postId(), fixture.petId())).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void samePetHelpfulServiceCommandsSerializeOnTheActorLockAndLeaveOneRow() throws Exception {
        ReactionActorFixture fixture = reactionActorFixture();
        TransactionTemplate tx = transactionTemplate();
        CountDownLatch firstMutated = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicLong secondBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> capture(() -> tx.executeWithoutResult(status -> {
                postService.addReaction(fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.HELPFUL);
                firstMutated.countDown();
                await(allowFirstCommit);
            })));
            assertThat(firstMutated.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> second = executor.submit(() -> capture(() -> tx.executeWithoutResult(status -> {
                secondBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                secondStarted.countDown();
                postService.addReaction(fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.HELPFUL);
            })));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(secondBackendPid.get());
            allowFirstCommit.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            allowFirstCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from board_post_reactions
                where post_id = ? and reactor_pet_id = ? and reaction_type = 'HELPFUL'
                """, Long.class, fixture.postId(), fixture.firstPetId())).isEqualTo(1L);
    }

    @Test
    void activePetSwitchRaceRecordsOneOfTheTwoSerializedValidPets() throws Exception {
        ReactionActorFixture fixture = reactionActorFixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> reaction = executor.submit(() -> capture(() -> {
                ready.countDown();
                await(start);
                postService.addReaction(fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.LIKE);
            }));
            Future<Throwable> switcher = executor.submit(() -> capture(() -> {
                ready.countDown();
                await(start);
                activePetSelectionService.selectActivePet(fixture.reactorUserId(), fixture.secondPetId());
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(reaction.get(20, TimeUnit.SECONDS)).isNull();
            assertThat(switcher.get(20, TimeUnit.SECONDS)).isNull();
            assertThat(jdbc.queryForObject("select active_pet_id from users where id = ?", Long.class,
                    fixture.reactorUserId())).isEqualTo(fixture.secondPetId());
            assertThat(jdbc.queryForObject("select count(*) from board_post_reactions where post_id = ?",
                    Long.class, fixture.postId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select reactor_pet_id from board_post_reactions where post_id = ?",
                    Long.class, fixture.postId())).isIn(fixture.firstPetId(), fixture.secondPetId());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void samePetPutDeleteRaceLeavesZeroOrOneRowWhoseStateMatchesSubsequentQueries() throws Exception {
        ReactionActorFixture fixture = reactionActorFixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> put = executor.submit(() -> capture(() -> {
                ready.countDown();
                await(start);
                postService.addReaction(fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.LIKE);
            }));
            Future<Throwable> delete = executor.submit(() -> capture(() -> {
                ready.countDown();
                await(start);
                postService.removeReaction(fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.LIKE);
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(put.get(20, TimeUnit.SECONDS)).isNull();
            assertThat(delete.get(20, TimeUnit.SECONDS)).isNull();

            long rows = jdbc.queryForObject("""
                    select count(*) from board_post_reactions
                    where post_id = ? and reactor_pet_id = ? and reaction_type = 'LIKE'
                    """, Long.class, fixture.postId(), fixture.firstPetId());
            long subsequentCount = reactions.countForPost(fixture.postId(), "LIKE");
            boolean subsequentReacted = reactions.findReactedPostIds(fixture.firstPetId(),
                    List.of(fixture.postId()), "LIKE").contains(fixture.postId());
            assertThat(rows).isIn(0L, 1L);
            assertThat(subsequentCount).isEqualTo(rows);
            assertThat(subsequentReacted).isEqualTo(rows == 1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void multiplePetsConcurrentPutLeaveOneRowPerPetAndFinalCountEqualsPetCount() throws Exception {
        ReactionActorFixture fixture = reactionActorFixture();
        List<Reactor> reactors = new ArrayList<>();
        reactors.add(new Reactor(fixture.reactorUserId(), fixture.firstPetId()));
        reactors.add(additionalReactor());
        reactors.add(additionalReactor());
        reactors.add(additionalReactor());
        ExecutorService executor = Executors.newFixedThreadPool(reactors.size());
        CountDownLatch ready = new CountDownLatch(reactors.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Reactor reactor : reactors) {
                futures.add(executor.submit(() -> capture(() -> {
                    ready.countDown();
                    await(start);
                    postService.addReaction(reactor.userId(), fixture.postId(), BoardPostReactionType.LIKE);
                })));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Throwable> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS)).isNull();
            }

            long finalRows = jdbc.queryForObject("""
                    select count(*) from board_post_reactions
                    where post_id = ? and reaction_type = 'LIKE'
                    """, Long.class, fixture.postId());
            assertThat(finalRows).isEqualTo((long) reactors.size());
            assertThat(reactions.countForPost(fixture.postId(), "LIKE")).isEqualTo((long) reactors.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void postDeleteAfterPublishedReactionIsAllowedOutcomeAAndKeepsTheReactionRow() {
        ReactionActorFixture fixture = reactionActorFixture();

        postService.addReaction(fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.LIKE);
        postService.delete(fixture.authorUserId(), fixture.postId());

        assertThat(postStatus(fixture.postId())).isEqualTo("DELETED");
        assertThat(reactions.countForPost(fixture.postId(), "LIKE")).isEqualTo(1L);
    }

    @Test
    void helpfulFirstThenPostDeleteWaitsForTheActualPostShareLockAndPreservesHistory() throws Exception {
        ReactionActorFixture fixture = reactionActorFixture();
        TransactionTemplate tx = transactionTemplate();
        CountDownLatch reactionSaved = new CountDownLatch(1);
        CountDownLatch allowReactionCommit = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        AtomicLong deleteBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> reaction = executor.submit(() -> capture(() -> tx.executeWithoutResult(status -> {
                postService.addReaction(fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.HELPFUL);
                reactionSaved.countDown();
                await(allowReactionCommit);
            })));
            assertThat(reactionSaved.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> delete = executor.submit(() -> capture(() -> tx.executeWithoutResult(status -> {
                deleteBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                deleteStarted.countDown();
                postService.delete(fixture.authorUserId(), fixture.postId());
            })));
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(deleteBackendPid.get());
            allowReactionCommit.countDown();
            assertThat(reaction.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            allowReactionCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(postStatus(fixture.postId())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("""
                select count(*) from board_post_reactions
                where post_id = ? and reaction_type = 'HELPFUL'
                """, Long.class, fixture.postId())).isEqualTo(1L);
    }

    @Test
    void postDeleteFirstMakesWaitingHelpfulReevaluatePublishedPredicateAndCreateNoRow() throws Exception {
        ReactionActorFixture fixture = reactionActorFixture();
        TransactionTemplate tx = transactionTemplate();
        CountDownLatch deleteWriteLocked = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        CountDownLatch reactionStarted = new CountDownLatch(1);
        AtomicLong reactionBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> delete = executor.submit(() -> capture(() -> tx.executeWithoutResult(status -> {
                postService.delete(fixture.authorUserId(), fixture.postId());
                posts.flush();
                deleteWriteLocked.countDown();
                await(allowDeleteCommit);
            })));
            assertThat(deleteWriteLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> reaction = executor.submit(() -> capture(() -> tx.executeWithoutResult(status -> {
                reactionBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                reactionStarted.countDown();
                postService.addReaction(fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.HELPFUL);
            })));
            assertThat(reactionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(reactionBackendPid.get());
            allowDeleteCommit.countDown();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
            Throwable outcome = reaction.get(30, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(itda.common.exception.BusinessException.class);
            assertThat(((itda.common.exception.BusinessException) outcome).getErrorCode().name())
                    .isEqualTo("BOARD_POST_NOT_FOUND");
        } finally {
            allowDeleteCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from board_post_reactions
                where post_id = ? and reaction_type = 'HELPFUL'
                """, Long.class, fixture.postId())).isZero();
    }

    @Test
    void postDeleteBeforeReactionIsAllowedOutcomeBAndCreatesNoNewReactionRow() {
        ReactionActorFixture fixture = reactionActorFixture();

        postService.delete(fixture.authorUserId(), fixture.postId());

        assertThatThrownBy(() -> postService.addReaction(
                fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.LIKE
        )).isInstanceOf(itda.common.exception.BusinessException.class)
                .extracting(error -> ((itda.common.exception.BusinessException) error).getErrorCode().name())
                .isEqualTo("BOARD_POST_NOT_FOUND");
        assertThat(postStatus(fixture.postId())).isEqualTo("DELETED");
        assertThat(reactions.countForPost(fixture.postId(), "LIKE")).isZero();
    }

    @Test
    void concurrentPostDeleteAndReactionAllowOnlyTheIssueDefinedOutcomes() throws Exception {
        ReactionActorFixture fixture = reactionActorFixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> reaction = executor.submit(() -> capture(() -> {
                ready.countDown();
                await(start);
                postService.addReaction(fixture.reactorUserId(), fixture.postId(), BoardPostReactionType.LIKE);
            }));
            Future<Throwable> delete = executor.submit(() -> capture(() -> {
                ready.countDown();
                await(start);
                postService.delete(fixture.authorUserId(), fixture.postId());
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Throwable reactionOutcome = reaction.get(20, TimeUnit.SECONDS);
            assertThat(delete.get(20, TimeUnit.SECONDS)).isNull();
            assertThat(postStatus(fixture.postId())).isEqualTo("DELETED");

            if (reactionOutcome == null) {
                assertThat(reactionRows(fixture.postId())).isEqualTo(1L);
                assertThat(reactions.countForPost(fixture.postId(), "LIKE")).isEqualTo(1L);
            } else {
                assertThat(reactionOutcome).isInstanceOf(itda.common.exception.BusinessException.class);
                assertThat(((itda.common.exception.BusinessException) reactionOutcome).getErrorCode().name())
                        .isEqualTo("BOARD_POST_NOT_FOUND");
                assertThat(reactionRows(fixture.postId())).isZero();
                assertThat(reactions.countForPost(fixture.postId(), "LIKE")).isZero();
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int insertWhenStarted(
            TransactionTemplate tx,
            Fixture fixture,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return insertWhenStarted(tx, fixture, ready, start, "LIKE");
    }

    private int insertWhenStarted(
            TransactionTemplate tx,
            Fixture fixture,
            CountDownLatch ready,
            CountDownLatch start,
            String reactionType
    ) {
        ready.countDown();
        await(start);
        return tx.execute(status -> reactions.insertIgnore(fixture.postId(), fixture.petId(), reactionType));
    }

    private int get(Future<Integer> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private Throwable capture(ThrowingAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent test timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private void awaitDatabaseLockWait(long contenderBackendPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("""
                    select wait_event_type = 'Lock' and cardinality(pg_blocking_pids(pid)) > 0
                      from pg_stat_activity where pid = ?
                    """, Boolean.class, contenderBackendPid);
            if (Boolean.TRUE.equals(waiting)) return;
        }
        throw new AssertionError("contender did not block on a PostgreSQL lock");
    }

    private String deleteRule(String constraintName) {
        return jdbc.queryForObject("""
                select delete_rule
                from information_schema.referential_constraints
                where constraint_schema = current_schema()
                  and constraint_name = ?
                """, String.class, constraintName);
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private Fixture fixture() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        long userId = jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113165000') returning id
                """, Long.class, unique + "@test.com", "user" + unique.substring(0, 6), "user#" + unique.substring(0, 8));
        long petId = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, 'ACTIVE') returning id
                """, Long.class, userId, petTag(), "pet" + unique.substring(0, 6));
        long boardId = jdbc.queryForObject("insert into boards (name) values (?) returning id",
                Long.class, "board-" + unique.substring(0, 8));
        long postId = jdbc.queryForObject("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', 'content', 'PUBLISHED') returning id
                """, Long.class, boardId, userId, petId);
        return new Fixture(postId, petId);
    }

    private ReactionActorFixture reactionActorFixture() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        long authorUserId = createUser("a" + unique);
        long authorPetId = createPet(authorUserId, "a" + unique);
        long reactorUserId = createUser("r" + unique);
        long firstPetId = createPet(reactorUserId, "r1" + unique);
        long secondPetId = createPet(reactorUserId, "r2" + unique);
        jdbc.update("update users set active_pet_id = ? where id = ?", firstPetId, reactorUserId);
        long boardId = jdbc.queryForObject("insert into boards (name) values (?) returning id",
                Long.class, "board-race-" + unique.substring(0, 8));
        long postId = jdbc.queryForObject("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', 'content', 'PUBLISHED') returning id
                """, Long.class, boardId, authorUserId, authorPetId);
        jdbc.update("update users set active_pet_id = ? where id = ?", authorPetId, authorUserId);
        return new ReactionActorFixture(postId, authorUserId, reactorUserId, firstPetId, secondPetId);
    }

    private long createUser(String unique) {
        return jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113165000') returning id
                """, Long.class, unique + "@test.com", "user" + unique.substring(0, 6),
                "user#" + unique.substring(0, 8));
    }

    private long createPet(long userId, String unique) {
        return jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, 'ACTIVE') returning id
                """, Long.class, userId, petTag(), "pet" + unique.substring(0, 6));
    }

    private Reactor additionalReactor() {
        String unique = "m" + UUID.randomUUID().toString().replace("-", "");
        long userId = createUser(unique);
        long petId = createPet(userId, unique);
        jdbc.update("update users set active_pet_id = ? where id = ?", petId, userId);
        return new Reactor(userId, petId);
    }

    private String petTag() {
        return "pet#" + "%04d".formatted(PET_TAG_SEQUENCE.incrementAndGet());
    }

    private String postStatus(long postId) {
        return jdbc.queryForObject("select status from board_posts where id = ?", String.class, postId);
    }

    private long reactionRows(long postId) {
        return jdbc.queryForObject("select count(*) from board_post_reactions where post_id = ?",
                Long.class, postId);
    }

    private record Fixture(long postId, long petId) {
    }

    private record ReactionActorFixture(
            long postId,
            long authorUserId,
            long reactorUserId,
            long firstPetId,
            long secondPetId
    ) {
    }

    private record Reactor(long userId, long petId) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
