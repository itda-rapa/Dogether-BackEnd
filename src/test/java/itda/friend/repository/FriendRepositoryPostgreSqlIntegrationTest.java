package itda.friend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import itda.friend.repository.FriendRequestRepository.FriendRequestPairRow;
import itda.friend.repository.FriendRequestRepository.FriendRequestListRow;
import itda.friend.repository.FriendRequestRepository.PendingFriendRequestRelationshipRow;
import itda.friend.repository.FriendshipRepository.FriendshipCountRow;
import itda.friend.repository.FriendshipRepository.FriendshipListRow;
import itda.friend.repository.FriendshipRepository.FriendshipRelationshipRow;
import itda.friend.service.FriendBlockCleanupService;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
class FriendRepositoryPostgreSqlIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T07:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private FriendRequestRepository friendRequestRepository;

    @Autowired
    private FriendBlockCleanupService friendBlockCleanupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    void findsFriendshipsInBothDirectionsAndExcludesUnrelatedPairs() {
        Long ownerId = createUser();
        Long firstPetId = createPet(ownerId);
        Long sourcePetId = createPet(ownerId);
        Long thirdPetId = createPet(ownerId);
        Long unrelatedPetId = createPet(ownerId);
        Long unrelatedCounterpartPetId = createPet(ownerId);
        insertFriendship(firstPetId, sourcePetId);
        insertFriendship(sourcePetId, thirdPetId);
        insertFriendship(unrelatedPetId, unrelatedCounterpartPetId);

        List<FriendshipRelationshipRow> rows =
                friendshipRepository.findRelationships(
                        sourcePetId,
                        List.of(
                                firstPetId,
                                thirdPetId,
                                unrelatedPetId,
                                unrelatedCounterpartPetId
                        )
                );

        assertThat(rows)
                .extracting(row ->
                        row.getPetLowId() + ":" + row.getPetHighId()
                )
                .containsExactlyInAnyOrder(
                        firstPetId + ":" + sourcePetId,
                        sourcePetId + ":" + thirdPetId
                );
    }

    @Test
    void findsOnlyActivePendingRequestsAtStrictExpiryBoundary() {
        Long ownerId = createUser();
        Long sourcePetId = createPet(ownerId);
        Long sentTargetPetId = createPet(ownerId);
        Long receivedRequesterPetId = createPet(ownerId);
        Long equalExpiryTargetPetId = createPet(ownerId);
        Long expiredRequesterPetId = createPet(ownerId);
        Long acceptedTargetPetId = createPet(ownerId);
        Long unrelatedPetId = createPet(ownerId);
        Long unrelatedTargetPetId = createPet(ownerId);

        insertFriendRequest(
                sourcePetId,
                sentTargetPetId,
                "PENDING",
                NOW.plusSeconds(1)
        );
        insertFriendRequest(
                receivedRequesterPetId,
                sourcePetId,
                "PENDING",
                NOW.plusSeconds(2)
        );
        Long equalExpiryRequestId = insertFriendRequest(
                sourcePetId,
                equalExpiryTargetPetId,
                "PENDING",
                NOW
        );
        Long expiredRequestId = insertFriendRequest(
                expiredRequesterPetId,
                sourcePetId,
                "PENDING",
                NOW.minusSeconds(1)
        );
        insertFriendRequest(
                sourcePetId,
                acceptedTargetPetId,
                "ACCEPTED",
                NOW.plusSeconds(3)
        );
        insertFriendRequest(
                unrelatedPetId,
                unrelatedTargetPetId,
                "PENDING",
                NOW.plusSeconds(4)
        );

        List<PendingFriendRequestRelationshipRow> rows =
                friendRequestRepository.findActivePendingRelationships(
                        sourcePetId,
                        List.of(
                                sentTargetPetId,
                                receivedRequesterPetId,
                                equalExpiryTargetPetId,
                                expiredRequesterPetId,
                                acceptedTargetPetId,
                                unrelatedPetId,
                                unrelatedTargetPetId
                        ),
                        NOW
                );

        Set<String> directions = rows.stream()
                .map(row ->
                        row.getRequesterPetId() + "->" + row.getTargetPetId()
                )
                .collect(Collectors.toSet());
        assertThat(directions).containsExactlyInAnyOrder(
                sourcePetId + "->" + sentTargetPetId,
                receivedRequesterPetId + "->" + sourcePetId
        );
        assertThat(status(equalExpiryRequestId)).isEqualTo("PENDING");
        assertThat(status(expiredRequestId)).isEqualTo("PENDING");
    }

    @Test
    void locksPendingPairRegardlessOfDirectionAndExpiry() {
        Long ownerId = createUser();
        Long firstPetId = createPet(ownerId);
        Long secondPetId = createPet(ownerId);
        Long requestId = insertFriendRequest(
                secondPetId,
                firstPetId,
                "PENDING",
                NOW.minusSeconds(1)
        );

        var locked = new TransactionTemplate(transactionManager).execute(
                status -> friendRequestRepository.findPendingPairForUpdate(
                        Math.min(firstPetId, secondPetId),
                        Math.max(firstPetId, secondPetId)
                )
        );

        assertThat(locked).isPresent();
        assertThat(locked.orElseThrow().getId()).isEqualTo(requestId);
        assertThat(locked.orElseThrow().getRequesterPetId())
                .isEqualTo(secondPetId);
        assertThat(locked.orElseThrow().getTargetPetId())
                .isEqualTo(firstPetId);
    }

    @Test
    void pairProjectionDoesNotWaitForRequestRowLock() throws Exception {
        Long ownerId = createUser();
        Long requesterPetId = createPet(ownerId);
        Long targetPetId = createPet(ownerId);
        Long requestId = insertFriendRequest(
                requesterPetId,
                targetPetId,
                "PENDING",
                NOW.plusSeconds(60)
        );
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);
        CountDownLatch ownerLocked = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch projectionCompleted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> owner = executor.submit(() -> transaction.executeWithoutResult(
                    status -> {
                        assertThat(friendRequestRepository.findByIdForUpdate(
                                requestId
                        )).isPresent();
                        ownerLocked.countDown();
                        await(releaseOwner);
                    }
            ));
            assertThat(ownerLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<FriendRequestPairRow> projection = executor.submit(() ->
                    transaction.execute(status -> {
                        FriendRequestPairRow row = friendRequestRepository
                                .findPairById(requestId)
                                .orElseThrow();
                        projectionCompleted.countDown();
                        return row;
                    })
            );

            assertThat(projectionCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseOwner.getCount()).isEqualTo(1);
            FriendRequestPairRow row =
                    projection.get(10, TimeUnit.SECONDS);
            assertThat(row.getRequestId()).isEqualTo(requestId);
            assertThat(row.getRequesterPetId()).isEqualTo(requesterPetId);
            assertThat(row.getTargetPetId()).isEqualTo(targetPetId);

            releaseOwner.countDown();
            owner.get(10, TimeUnit.SECONDS);
        } finally {
            releaseOwner.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .isTrue();
        }
    }

    @Test
    void requestIdForUpdateWaitsForExistingRowLock() throws Exception {
        Long ownerId = createUser();
        Long requesterPetId = createPet(ownerId);
        Long targetPetId = createPet(ownerId);
        Long requestId = insertFriendRequest(
                requesterPetId,
                targetPetId,
                "PENDING",
                NOW.plusSeconds(60)
        );
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);
        CountDownLatch ownerLocked = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> owner = executor.submit(() -> transaction.executeWithoutResult(
                    status -> {
                        assertThat(friendRequestRepository.findByIdForUpdate(
                                requestId
                        )).isPresent();
                        ownerLocked.countDown();
                        await(releaseOwner);
                    }
            ));
            assertThat(ownerLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> competitor = executor.submit(() -> {
                try {
                    transaction.executeWithoutResult(status -> {
                        jdbcTemplate.execute(
                                "SET LOCAL lock_timeout = '500ms'"
                        );
                        friendRequestRepository.findByIdForUpdate(requestId);
                    });
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            Throwable failure = competitor.get(10, TimeUnit.SECONDS);
            SQLException postgresFailure = findPostgresFailure(failure);
            assertThat(postgresFailure.getClass().getName())
                    .isEqualTo("org.postgresql.util.PSQLException");
            assertThat(postgresFailure.getSQLState()).isEqualTo("55P03");

            releaseOwner.countDown();
            owner.get(10, TimeUnit.SECONDS);

            Boolean lockedAfterRelease = transaction.execute(status ->
                    friendRequestRepository.findByIdForUpdate(requestId)
                            .isPresent()
            );
            assertThat(lockedAfterRelease).isTrue();
        } finally {
            releaseOwner.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .isTrue();
        }
    }

    @Test
    void countsBothFriendshipDirectionsInOneProjectionQuery() {
        Long ownerId = createUser();
        Long firstPetId = createPet(ownerId);
        Long secondPetId = createPet(ownerId);
        Long counterpartOne = createPet(ownerId);
        Long counterpartTwo = createPet(ownerId);
        insertFriendship(firstPetId, counterpartOne);
        insertFriendship(counterpartTwo, firstPetId);
        insertFriendship(secondPetId, counterpartOne);

        List<FriendshipCountRow> rows =
                friendshipRepository.countRelationshipsByPetIds(
                        List.of(firstPetId, secondPetId)
                );

        assertThat(rows)
                .extracting(
                        FriendshipCountRow::getPetId,
                        FriendshipCountRow::getFriendCount
                )
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(firstPetId, 2L),
                        org.assertj.core.groups.Tuple.tuple(secondPetId, 1L)
                );
    }

    @Test
    void pagesReceivedAndSentPendingRequestsWithStrictExpiryAndStableCursor() {
        Long ownerId = createUser();
        Long activePetId = createPet(ownerId);
        Long firstCounterpart = createPet(ownerId);
        Long secondCounterpart = createPet(ownerId);
        Long thirdCounterpart = createPet(ownerId);
        Instant sameRequestedAt = NOW.minusSeconds(60);

        Long olderRequestId = insertFriendRequestAt(
                firstCounterpart,
                activePetId,
                "PENDING",
                sameRequestedAt.minusSeconds(1),
                NOW.plusSeconds(60)
        );
        Long firstTieId = insertFriendRequestAt(
                secondCounterpart,
                activePetId,
                "PENDING",
                sameRequestedAt,
                NOW.plusSeconds(60)
        );
        Long secondTieId = insertFriendRequestAt(
                thirdCounterpart,
                activePetId,
                "PENDING",
                sameRequestedAt,
                NOW.plusSeconds(60)
        );
        Long equalExpiryId = insertFriendRequestAt(
                activePetId,
                createPet(ownerId),
                "PENDING",
                NOW.minusSeconds(30),
                NOW
        );
        insertFriendRequestAt(
                activePetId,
                createPet(ownerId),
                "ACCEPTED",
                NOW.minusSeconds(20),
                NOW.plusSeconds(60)
        );

        List<FriendRequestListRow> firstPage =
                friendRequestRepository.findReceivedPendingPage(
                        activePetId,
                        NOW,
                        null,
                        null,
                        2
                );

        assertThat(firstPage)
                .extracting(FriendRequestListRow::getRequestId)
                .containsExactly(secondTieId, firstTieId);
        assertThat(firstPage)
                .allSatisfy(row -> {
                    assertThat(row.getStatus()).isEqualTo("PENDING");
                    assertThat(row.getRespondedAt()).isNull();
                    assertThat(row.getRequesterPetId()).isNotNull();
                    assertThat(row.getTargetPetId()).isEqualTo(activePetId);
                });

        List<FriendRequestListRow> secondPage =
                friendRequestRepository.findReceivedPendingPage(
                        activePetId,
                        NOW,
                        firstPage.get(1).getRequestedAt(),
                        firstPage.get(1).getRequestId(),
                        2
                );
        assertThat(secondPage)
                .extracting(FriendRequestListRow::getRequestId)
                .containsExactly(olderRequestId);

        List<FriendRequestListRow> sent =
                friendRequestRepository.findSentPendingPage(
                        activePetId,
                        NOW,
                        null,
                        null,
                        10
                );
        assertThat(sent)
                .extracting(FriendRequestListRow::getRequestId)
                .doesNotContain(equalExpiryId);
        assertThat(status(equalExpiryId)).isEqualTo("PENDING");
    }

    @Test
    void pagesFriendshipsAcrossLowAndHighDirectionsWithStableCursor() {
        Long ownerId = createUser();
        Long lowPetId = createPet(ownerId);
        Long sourcePetId = createPet(ownerId);
        Long highPetId = createPet(ownerId);
        Long unrelatedPetId = createPet(ownerId);
        Instant createdAt = NOW.minusSeconds(60);

        Long firstId = insertFriendshipAt(
                lowPetId,
                sourcePetId,
                createdAt
        );
        Long secondId = insertFriendshipAt(
                sourcePetId,
                highPetId,
                createdAt
        );
        insertFriendshipAt(
                highPetId,
                unrelatedPetId,
                NOW
        );

        List<FriendshipListRow> firstPage =
                friendshipRepository.findFriendPage(
                        sourcePetId,
                        null,
                        null,
                        1
                );
        assertThat(firstPage).hasSize(1);
        assertThat(firstPage.get(0).getFriendshipId()).isEqualTo(secondId);
        assertThat(firstPage.get(0).getCounterpartPetId())
                .isEqualTo(highPetId);

        List<FriendshipListRow> secondPage =
                friendshipRepository.findFriendPage(
                        sourcePetId,
                        firstPage.get(0).getCreatedAt(),
                        firstPage.get(0).getFriendshipId(),
                        2
                );
        assertThat(secondPage)
                .extracting(FriendshipListRow::getFriendshipId)
                .containsExactly(firstId);
        assertThat(secondPage.get(0).getCounterpartPetId())
                .isEqualTo(lowPetId);
    }

    @Test
    void blockCleanupCoversEveryPetPairAndPreservesHistoryAndUnrelatedRelations() {
        Long userA = createUser();
        Long userB = createUser();
        Long userC = createUser();
        Long petA1 = createPet(userA);
        Long petA2 = createPet(userA);
        Long petB1 = createPet(userB);
        Long petB2 = createPet(userB);
        Long petC1 = createPet(userC);

        insertFriendship(petA1, petB1);
        insertFriendship(petA2, petB2);
        insertFriendship(petA1, petC1);

        Long pendingAB = insertFriendRequest(
                petA1, petB2, "PENDING", NOW.plusSeconds(60));
        Long pendingBA = insertFriendRequest(
                petB1, petA2, "PENDING", NOW.plusSeconds(60));
        Long acceptedAB = insertFriendRequest(
                petA2, petB1, "ACCEPTED", NOW.plusSeconds(60));
        Long unrelated = insertFriendRequest(
                petA1, petC1, "PENDING", NOW.plusSeconds(60));

        FriendBlockCleanupService.CleanupResult result =
                friendBlockCleanupService.cleanupBetweenUsers(userA, userB);

        assertThat(result.deletedFriendships()).isEqualTo(2);
        assertThat(result.canceledPendingRequests()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from friendships", Integer.class)).isEqualTo(1);
        assertThat(status(pendingAB)).isEqualTo("CANCELED");
        assertThat(status(pendingBA)).isEqualTo("CANCELED");
        assertThat(status(acceptedAB)).isEqualTo("ACCEPTED");
        assertThat(status(unrelated)).isEqualTo("PENDING");
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
                ) values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500')
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

    private Long insertFriendRequestAt(
            Long requesterPetId,
            Long targetPetId,
            String status,
            Instant requestedAt,
            Instant expiresAt
    ) {
        return jdbcTemplate.queryForObject("""
                insert into friend_requests (
                    requester_pet_id,
                    target_pet_id,
                    status,
                    requested_at,
                    expires_at
                ) values (?, ?, ?, ?, ?)
                returning id
                """,
                Long.class,
                requesterPetId,
                targetPetId,
                status,
                requestedAt.atOffset(ZoneOffset.UTC),
                expiresAt.atOffset(ZoneOffset.UTC)
        );
    }

    private void insertFriendship(Long petLowId, Long petHighId) {
        jdbcTemplate.update("""
                insert into friendships (pet_low_id, pet_high_id)
                values (?, ?)
                """, Math.min(petLowId, petHighId), Math.max(petLowId, petHighId));
    }

    private Long insertFriendshipAt(
            Long petAId,
            Long petBId,
            Instant createdAt
    ) {
        return jdbcTemplate.queryForObject("""
                insert into friendships (
                    pet_low_id,
                    pet_high_id,
                    created_at
                ) values (?, ?, ?)
                returning id
                """,
                Long.class,
                Math.min(petAId, petBId),
                Math.max(petAId, petBId),
                createdAt.atOffset(ZoneOffset.UTC)
        );
    }

    private String status(Long requestId) {
        return jdbcTemplate.queryForObject("""
                select status
                  from friend_requests
                 where id = ?
                """, String.class, requestId);
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private SQLException findPostgresFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && current.getClass().getName()
                            .equals("org.postgresql.util.PSQLException")) {
                return sqlException;
            }
            current = current.getCause();
        }
        throw new AssertionError(
                "Expected PostgreSQL PSQLException in cause chain",
                failure
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
